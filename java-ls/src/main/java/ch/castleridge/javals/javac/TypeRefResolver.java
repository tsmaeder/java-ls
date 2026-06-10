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

    /**
     * Resolve a class-type reference. {@link TypeRef.Resolved} carries a
     * fully-qualified JVM binary name; {@link TypeRef.Unresolved} carries
     * only a simple name that is resolved against {@code enclosing}'s
     * {@link SourceResolutionHints}.
     */
    Type resolve(TypeRef ref, ModuleSymbol module, TypeEntry enclosing) {
        if (ref instanceof TypeRef.Resolved r) {
            return classType(module, r.jvmBinaryName());
        }
        if (ref instanceof TypeRef.Unresolved u) {
            return classType(module, resolveSimple(u.simpleName(), enclosing));
        }
        return syms.errType;
    }

    Type classType(ModuleSymbol module, String jvmBinaryName) {
        if (jvmBinaryName == null || jvmBinaryName.isEmpty()) return syms.errType;
        String dotted = jvmBinaryName.replace('/', '.');
        ClassSymbol sym = syms.enterClass(module, names.fromString(dotted));
        return sym.type;
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
