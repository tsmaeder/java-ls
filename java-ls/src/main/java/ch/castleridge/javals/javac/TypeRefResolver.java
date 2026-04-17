package ch.castleridge.javals.javac;

import com.sun.tools.javac.code.Symbol.ClassSymbol;
import com.sun.tools.javac.code.Symbol.ModuleSymbol;
import com.sun.tools.javac.code.Symtab;
import com.sun.tools.javac.code.Type;
import com.sun.tools.javac.code.Type.ArrayType;
import com.sun.tools.javac.code.Type.MethodType;
import com.sun.tools.javac.util.ListBuffer;
import com.sun.tools.javac.util.Names;

import ch.castleridge.javals.indexing.index.Index;
import ch.castleridge.javals.indexing.model.FieldEntry;
import ch.castleridge.javals.indexing.model.MethodEntry;
import ch.castleridge.javals.indexing.model.SourceResolutionHints;
import ch.castleridge.javals.indexing.model.TypeEntry;
import ch.castleridge.javals.indexing.model.TypeRef;

/**
 * Resolves {@link TypeRef} instances - produced by the source and bytecode
 * indexers - into javac {@link Type} instances.
 *
 * <p>Bytecode-derived references are always {@link TypeRef.Resolved} /
 * {@link TypeRef.Primitive} / {@link TypeRef.Array} and resolve trivially.
 * Source-derived references may contain {@link TypeRef.Unresolved} leaves
 * whose final JVM binary name is only decided here, using the
 * {@link SourceResolutionHints} attached to the enclosing
 * {@link TypeEntry} and the full {@link Index}. Resolution follows the
 * JLS lookup order: declared-in-CU &gt; single-type-import &gt; same-package
 * &gt; on-demand-import &gt; {@code java.lang}.
 *
 * <p>The {@link ClasspathOrder} filters which index hits count as
 * "found": a candidate JVM name is only considered present if at least
 * one of its index entries comes from a source on the current classpath.
 */
final class TypeRefResolver {

    private final Symtab syms;
    private final Names names;
    private final Index index;
    private final ClasspathOrder classpath;

    TypeRefResolver(Symtab syms, Names names, Index index, ClasspathOrder classpath) {
        this.syms = syms;
        this.names = names;
        this.index = index;
        this.classpath = classpath;
    }

    /** Resolve a single {@link TypeRef} in the context of {@code enclosing}. */
    Type resolve(TypeRef ref, ModuleSymbol module, TypeEntry enclosing) {
        if (ref == null) return syms.errType;
        if (ref instanceof TypeRef.Primitive p) return primitive(p);
        if (ref instanceof TypeRef.Array a) {
            return new ArrayType(resolve(a.element(), module, enclosing), syms.arrayClass);
        }
        if (ref instanceof TypeRef.Resolved r) {
            return classType(module, r.jvmBinaryName());
        }
        if (ref instanceof TypeRef.Unresolved u) {
            return classType(module, resolveSimple(u.simpleName(), enclosing));
        }
        return syms.errType;
    }

    /** Resolve a whole method descriptor into a javac {@link MethodType}. */
    MethodType resolveMethod(MethodEntry m, ModuleSymbol module, TypeEntry enclosing) {
        ListBuffer<Type> params = new ListBuffer<>();
        for (TypeRef pr : m.paramTypes()) {
            params.add(resolve(pr, module, enclosing));
        }
        ListBuffer<Type> thrown = new ListBuffer<>();
        for (TypeRef tr : m.throwsTypes()) {
            thrown.add(resolve(tr, module, enclosing));
        }
        Type ret = resolve(m.returnType(), module, enclosing);
        return new MethodType(
                params.toList(),
                ret,
                thrown.toList(),
                syms.methodClass);
    }

    /** Resolve a field's declared type. */
    Type resolveField(FieldEntry f, ModuleSymbol module, TypeEntry enclosing) {
        return resolve(f.type(), module, enclosing);
    }

    /**
     * Turn a JVM binary name ({@code java/util/Map$Entry}) into the matching
     * javac {@link ClassSymbol#type}, creating the stub symbol on demand.
     */
    Type classType(ModuleSymbol module, String jvmBinaryName) {
        if (jvmBinaryName == null || jvmBinaryName.isEmpty()) return syms.errType;
        String dotted = jvmBinaryName.replace('/', '.');
        ClassSymbol sym = syms.enterClass(module, names.fromString(dotted));
        return sym.type;
    }

    private Type primitive(TypeRef.Primitive p) {
        return switch (p) {
            case VOID -> syms.voidType;
            case BOOLEAN -> syms.booleanType;
            case BYTE -> syms.byteType;
            case CHAR -> syms.charType;
            case SHORT -> syms.shortType;
            case INT -> syms.intType;
            case LONG -> syms.longType;
            case FLOAT -> syms.floatType;
            case DOUBLE -> syms.doubleType;
        };
    }

    /**
     * Apply the JLS resolution order to a simple name using the hints on
     * {@code enclosing} and the global {@link Index}, considering only
     * candidates that are actually on the current {@link ClasspathOrder}.
     * The result is a JVM binary name (slash-delimited).
     */
    private String resolveSimple(String simple, TypeEntry enclosing) {
        if (enclosing == null) {
            return "java/lang/" + simple;
        }

        // (0) Direct nested types of the enclosing type can be referenced
        //     by simple name. We trust the enclosing entry's own metadata
        //     here without consulting the classpath - nested types live in
        //     the same jar as their enclosing.
        for (String nested : enclosing.innerTypeJvmNames()) {
            int dollar = nested.lastIndexOf('$');
            int slash = nested.lastIndexOf('/');
            int start = Math.max(dollar, slash) + 1;
            if (nested.substring(start).equals(simple)) {
                return nested;
            }
        }

        SourceResolutionHints hints = enclosing.hints();
        if (hints == null) {
            return "java/lang/" + simple;
        }

        // (1) Types declared in the same compilation unit at top level.
        if (hints.siblingSimpleNames().contains(simple)) {
            String pkg = hints.sourcePackage();
            return pkg.isEmpty() ? simple : pkg + "/" + simple;
        }

        // (2) Single-type imports.
        String single = hints.singleTypeImports().get(simple);
        if (single != null) return single;

        // (3) Same-package lookup.
        String pkg = hints.sourcePackage();
        String samePkg = pkg.isEmpty() ? simple : pkg + "/" + simple;
        if (availableOnClasspath(samePkg)) return samePkg;

        // (4) On-demand imports.
        for (String od : hints.onDemandImports()) {
            String candidate = od.isEmpty() ? simple : od + "/" + simple;
            if (availableOnClasspath(candidate)) return candidate;
        }

        // (5) java.lang fallback.
        return "java/lang/" + simple;
    }

    private boolean availableOnClasspath(String jvmName) {
        return classpath.anyOnClasspath(index.getAll(jvmName));
    }
}
