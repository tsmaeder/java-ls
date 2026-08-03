package ch.castleridge.javals.javac;

import java.util.HashSet;
import java.util.Set;

import com.sun.tools.javac.code.Symbol.ClassSymbol;
import com.sun.tools.javac.code.Symbol.ModuleSymbol;
import com.sun.tools.javac.code.Symtab;
import com.sun.tools.javac.code.Type;
import com.sun.tools.javac.util.Names;

import ch.castleridge.javals.indexing.index.Index;
import ch.castleridge.javals.indexing.model.SourceResolutionHints;
import ch.castleridge.javals.indexing.model.SourceTypeEntry;
import ch.castleridge.javals.indexing.model.Type.Parameterized;
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

    // Memoizes simple-name resolution per (enclosing type, simple name). The
    // index and classpath are immutable for this resolver's lifetime, so the
    // member-type scope walk in resolveSimpleUncached is deterministic and its
    // result can be cached. Without this a source file with many unresolved
    // references re-walks the supertype graph for every occurrence.
    private final java.util.Map<String, String> simpleNameCache = new java.util.HashMap<>();

    TypeRefResolver(Symtab syms, Names names, Index index, ClasspathOrder classpath) {
        this.syms = syms;
        this.names = names;
        this.index = index;
        this.classpath = classpath;
    }

    ClassSymbol resolveTypeRef(TypeRef tr, ModuleSymbol module, TypeEntry entry) {
        ClassSymbol symbol = resolve(tr, module, entry);
        if (symbol != null) {
            attachClassfile(symbol);
            return symbol;
        }
        return null;
    }

    private void attachClassfile(ClassSymbol symbol) {
        IndexClassfileAttachment.attachIfMissing(symbol, index, classpath);
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
        if (!(enclosing instanceof SourceTypeEntry sourceEnclosing)) return jvm;

        SourceResolutionHints hints = sourceEnclosing.hints();
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
        if (!(enclosing instanceof SourceTypeEntry sourceEnclosing)) {
            return "java/lang/" + simple;
        }
        String cacheKey = sourceEnclosing.jvmName() + '\u0001' + simple;
        String cached = simpleNameCache.get(cacheKey);
        if (cached != null) {
            return cached;
        }
        String resolved = resolveSimpleUncached(simple, sourceEnclosing);
        simpleNameCache.put(cacheKey, resolved);
        return resolved;
    }

    private String resolveSimpleUncached(String simple, SourceTypeEntry enclosing) {
        // JLS 6.5.5.1: a simple type name is first matched against member
        // types in scope - the enclosing type's own nested types, those it
        // inherits from its supertypes, and (recursively) the same for every
        // lexically-enclosing type - before single-type imports, same-package
        // types, on-demand imports and the implicit java.lang import. The
        // source indexer cannot do this (it has no cross-file view and emits a
        // bare Unresolved leaf), so an inherited or sibling-scope nested type
        // referenced by its simple name would otherwise fall through to the
        // java.lang fallback and surface as a phantom java.lang.* type.
        String inScope = findMemberTypeInScope(simple, enclosing);
        if (inScope != null) {
            return inScope;
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

    /**
     * Resolve {@code simple} as a member type visible from the body of
     * {@code enclosing}: walk outwards through the lexically-enclosing types
     * (the type itself, then its outer type, and so on) and, at each level,
     * search that type and all of its supertypes for a nested type with a
     * matching simple name. Returns the winning JVM binary name, or
     * {@code null} when no member type matches.
     */
    private String findMemberTypeInScope(String simple, TypeEntry enclosing) {
        TypeEntry scope = enclosing;
        while (scope != null) {
            String hit = findInheritedMemberType(simple, scope, new HashSet<>());
            if (hit != null) {
                return hit;
            }
            scope = outerEntryOf(scope);
        }
        return null;
    }

    /**
     * Search {@code type} and its transitive supertypes (superclass and
     * interfaces) for a nested type whose simple name is {@code simple}.
     * {@code visited} guards against cycles in a (malformed or recursively
     * generic) supertype graph.
     */
    private String findInheritedMemberType(String simple, TypeEntry type, Set<String> visited) {
        if (type == null || !visited.add(type.jvmName())) {
            return null;
        }
        for (String nested : type.innerTypeJvmNames()) {
            if (simpleNameOf(nested).equals(simple)) {
                return nested;
            }
        }
        for (ch.castleridge.javals.indexing.model.Type superType : supertypesOf(type)) {
            TypeEntry superEntry = entryForSupertype(superType, type);
            String hit = findInheritedMemberType(simple, superEntry, visited);
            if (hit != null) {
                return hit;
            }
        }
        return null;
    }

    private java.util.List<ch.castleridge.javals.indexing.model.Type> supertypesOf(TypeEntry type) {
        java.util.List<ch.castleridge.javals.indexing.model.Type> out = new java.util.ArrayList<>();
        if (type.superRef() != null) {
            out.add(type.superRef());
        }
        out.addAll(java.util.Arrays.asList(type.interfaceRefs()));
        return out;
    }

    /** Resolve a supertype reference to its indexed {@link TypeEntry}, if any. */
    private TypeEntry entryForSupertype(ch.castleridge.javals.indexing.model.Type superType, TypeEntry context) {
        TypeRef ref = classRefOf(superType);
        if (ref == null) {
            return null;
        }
        String jvm;
        if (ref instanceof TypeRef.Resolved r) {
            jvm = qualifyResolved(r.jvmBinaryName(), context);
        } else if (ref instanceof TypeRef.Unresolved u) {
            // A supertype named by a bare simple name is resolved against the
            // subtype's imports / package only. We deliberately do NOT re-enter
            // the member-type search here: that would be mutually recursive with
            // findInheritedMemberType, and a supertype is in practice always a
            // single-import, fully-qualified, same-package or on-demand name.
            jvm = resolveSupertypeSimpleName(u.simpleName(), context);
        } else {
            return null;
        }
        if (jvm == null || jvm.isEmpty()) {
            return null;
        }
        return classpath.pick(index.getAll(jvm), TypeEntry::sourceUri);
    }

    private static TypeRef classRefOf(ch.castleridge.javals.indexing.model.Type type) {
        if (type instanceof TypeRef ref) {
            return ref;
        }
        if (type instanceof Parameterized p) {
            return p.raw();
        }
        return null;
    }

    /**
     * Lightweight resolution of a supertype's simple name against the
     * subtype's {@link SourceResolutionHints}: siblings, single-type imports,
     * same package, then on-demand imports. Returns {@code null} (rather than
     * a java.lang fallback) when nothing matches, so the inheritance walk
     * simply stops instead of chasing a phantom supertype.
     */
    private String resolveSupertypeSimpleName(String simple, TypeEntry context) {
        if (!(context instanceof SourceTypeEntry sourceContext)) {
            return null;
        }
        SourceResolutionHints hints = sourceContext.hints();
        if (hints.siblingSimpleNames().contains(simple)) {
            String pkg = hints.sourcePackage();
            return pkg.isEmpty() ? simple : pkg + "/" + simple;
        }
        String single = hints.singleTypeImports().get(simple);
        if (single != null) {
            return single;
        }
        String pkg = hints.sourcePackage();
        String samePkg = pkg.isEmpty() ? simple : pkg + "/" + simple;
        if (availableOnClasspath(samePkg)) {
            return samePkg;
        }
        for (String od : hints.onDemandImports()) {
            String candidate = od.isEmpty() ? simple : od + "/" + simple;
            if (availableOnClasspath(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    private TypeEntry outerEntryOf(TypeEntry entry) {
        String jvm = entry.jvmName();
        int dollar = jvm.lastIndexOf('$');
        if (dollar < 0) {
            return null;
        }
        return classpath.pick(index.getAll(jvm.substring(0, dollar)), TypeEntry::sourceUri);
    }

    private static String simpleNameOf(String jvmName) {
        int start = Math.max(jvmName.lastIndexOf('$'), jvmName.lastIndexOf('/')) + 1;
        return jvmName.substring(start);
    }
}
