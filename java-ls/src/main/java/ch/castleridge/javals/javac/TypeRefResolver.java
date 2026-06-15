package ch.castleridge.javals.javac;

import com.sun.tools.javac.code.Symbol.ClassSymbol;
import com.sun.tools.javac.code.Symbol.ModuleSymbol;
import com.sun.tools.javac.code.Symtab;
import com.sun.tools.javac.code.Type;
import com.sun.tools.javac.util.Names;

import ch.castleridge.javals.indexing.index.Index;
import ch.castleridge.javals.indexing.model.SourceResolutionHints;
import ch.castleridge.javals.indexing.model.TypeEntry;
import ch.castleridge.javals.indexing.model.TypeRef;

/**
 * Resolves a {@link TypeRef} - the only indexed type shape that carries a
 * class name needing classpath/compilation-unit resolution - into a javac
 * {@link Type}.
 *
 * <p>Structural type shapes (primitives, arrays, wildcards, parameterized
 * types, type variables and type-use annotations) are resolved by
 * {@link IndexClassReader}, which composes this resolver for the
 * class-reference leaves it encounters.
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

    ClassSymbol resolveTypeRef(TypeRef tr, ModuleSymbol module, TypeEntry entry) {
        ClassSymbol symbol = resolve(tr, module, entry);
        if (symbol != null) {
            if (symbol.classfile == null) {
                // Index buckets are keyed by JVM binary name (package separated by
                // '/', nested types by '$'). flatName() yields "pkg.Outer$Inner";
                // className() (the canonical "pkg.Outer.Inner") would never match a
                // nested type, leaving it without a class file and unloadable.
                String jvmName = symbol.flatName().toString().replace('.', '/');
                TypeEntry refEntry =  classpath.pick(index.getAll(jvmName), TypeEntry::sourceUri);
                if (refEntry != null) {
                    symbol.classfile = new IndexClassFileObject(refEntry);
                }
            }
            return symbol;
        }
        return null;
    }

    /**
     * Resolve a class-type reference. {@link TypeRef.Resolved} carries a
     * fully-qualified JVM binary name; {@link TypeRef.Unresolved} carries
     * only a simple name that is resolved against {@code enclosing}'s
     * {@link SourceResolutionHints}.
     */
    private ClassSymbol resolve(TypeRef ref, ModuleSymbol module, TypeEntry enclosing) {
        if (ref instanceof TypeRef.Resolved r) {
            return classSymbol(module, qualifyResolved(r.jvmBinaryName(), enclosing));
        }
        if (ref instanceof TypeRef.Unresolved u) {
            return classSymbol(module, resolveSimple(u.simpleName(), enclosing));
        }
        return null;
    }

    /**
     * Source indexing emits member selects rooted at a simple name (e.g.
     * {@code Base64.Encoder} under {@code import java.util.*}) as
     * {@code Base64$Encoder} without a package prefix. Re-qualify those
     * using the enclosing source entry's import hints and the active index.
     */
    private String qualifyResolved(String jvm, TypeEntry enclosing) {
        if (jvm.contains("/")) return jvm;
        if (enclosing == null || !enclosing.isSourceEntry()) return jvm;

        SourceResolutionHints hints = enclosing.hints();
        int dollar = jvm.indexOf('$');
        String outerSimple = dollar < 0 ? jvm : jvm.substring(0, dollar);
        String nestedSuffix = dollar < 0 ? "" : jvm.substring(dollar);

        String single = hints.singleTypeImports().get(outerSimple);
        if (single != null) {
            String candidate = single + nestedSuffix;
            if (availableOnClasspath(candidate)) return candidate;
        }

        if (hints.siblingSimpleNames().contains(outerSimple)) {
            String pkg = hints.sourcePackage();
            String candidate = pkg.isEmpty() ? jvm : pkg + "/" + jvm;
            if (availableOnClasspath(candidate)) return candidate;
        }

        for (String od : hints.onDemandImports()) {
            String candidate = od.isEmpty() ? jvm : od + "/" + jvm;
            if (availableOnClasspath(candidate)) return candidate;
        }

        String pkg = hints.sourcePackage();
        if (!pkg.isEmpty()) {
            String candidate = pkg + "/" + jvm;
            if (availableOnClasspath(candidate)) return candidate;
        }

        return jvm;
    }

    ClassSymbol classSymbol(ModuleSymbol module, String jvmBinaryName) {
        if (jvmBinaryName == null || jvmBinaryName.isEmpty()) return null;
        String dotted = jvmBinaryName.replace('/', '.');
        return syms.enterClass(module, names.fromString(dotted));
    }

    private String resolveSimple(String simple, TypeEntry enclosing) {
        if (enclosing == null) {
            return "java/lang/" + simple;
        }

        for (String nested : enclosing.innerTypeJvmNames()) {
            int dollar = nested.lastIndexOf('$');
            int slash = nested.lastIndexOf('/');
            int start = Math.max(dollar, slash) + 1;
            if (nested.substring(start).equals(simple)) {
                return nested;
            }
        }

        if (!enclosing.isSourceEntry()) {
            return "java/lang/" + simple;
        }
        SourceResolutionHints hints = enclosing.hints();

        if (hints.siblingSimpleNames().contains(simple)) {
            String pkg = hints.sourcePackage();
            return pkg.isEmpty() ? simple : pkg + "/" + simple;
        }

        String single = hints.singleTypeImports().get(simple);
        if (single != null) return single;

        String pkg = hints.sourcePackage();
        String samePkg = pkg.isEmpty() ? simple : pkg + "/" + simple;
        if (availableOnClasspath(samePkg)) return samePkg;

        for (String od : hints.onDemandImports()) {
            String candidate = od.isEmpty() ? simple : od + "/" + simple;
            if (availableOnClasspath(candidate)) return candidate;
        }

        return "java/lang/" + simple;
    }

    private boolean availableOnClasspath(String jvmName) {
        return classpath.pick(index.getAll(jvmName), TypeEntry::sourceUri) != null;
    }
}
