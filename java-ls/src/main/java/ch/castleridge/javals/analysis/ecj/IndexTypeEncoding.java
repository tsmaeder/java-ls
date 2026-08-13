/**
 * Copyright 2026 by Anysphere Inc.
 * 
 * Licensed under the MIT License.
 * 
 * SPDX-License-Identifier: MIT
 *
 * Author: Thomas Mäder, Castle Ridge Software
 *
 */
package ch.castleridge.javals.analysis.ecj;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import ch.castleridge.javals.classpath.ClasspathOrder;
import ch.castleridge.javals.indexing.index.Index;
import ch.castleridge.javals.indexing.model.MethodEntry;
import ch.castleridge.javals.indexing.model.SourceResolutionHints;
import ch.castleridge.javals.indexing.model.SourceTypeEntry;
import ch.castleridge.javals.indexing.model.Type;
import ch.castleridge.javals.indexing.model.Type.Annotated;
import ch.castleridge.javals.indexing.model.Type.Array;
import ch.castleridge.javals.indexing.model.Type.Parameterized;
import ch.castleridge.javals.indexing.model.Type.Primitive;
import ch.castleridge.javals.indexing.model.Type.TypeVariable;
import ch.castleridge.javals.indexing.model.Type.Wildcard;
import ch.castleridge.javals.indexing.model.TypeDeclKind;
import ch.castleridge.javals.indexing.model.TypeEntry;
import ch.castleridge.javals.indexing.model.TypeParamRef;
import ch.castleridge.javals.indexing.model.TypeRef;

/**
 * Encodes indexed {@link Type} trees as JVMS descriptors and signatures for ECJ
 * {@code IBinary*} adapters, resolving unresolved source refs against the active
 * classpath view.
 */
final class IndexTypeEncoding {
    private final TypeEntry owner;
    private final Index index;
    private final ClasspathOrder classpath;
    // A type's members mention the same simple names over and over, and the
    // member-type scope walk below re-reads the supertype graph every time.
    private final Map<String, String> resolvedSimpleNames = new HashMap<>();

    IndexTypeEncoding(TypeEntry owner, Index index, ClasspathOrder classpath) {
        this.owner = owner;
        this.index = index;
        this.classpath = classpath == null ? ClasspathOrder.UNRESTRICTED : classpath;
    }

    /**
     * Interfaces and annotation types name {@code java/lang/Object} as their
     * superclass, matching the class file convention. ECJ dereferences the
     * superclass of every binary type it sorts, so leaving it unset yields an
     * internal compiler error rather than a missing supertype.
     */
    String superName() {
        if (owner.superRef() != null) return erasedJvm(owner.superRef());
        if ("java/lang/Object".equals(owner.jvmOwnerName())) return null;
        if (owner instanceof SourceTypeEntry source) {
            if (source.declKind() == TypeDeclKind.ENUM) return "java/lang/Enum";
            if (source.declKind() == TypeDeclKind.RECORD) return "java/lang/Record";
        }
        return "java/lang/Object";
    }

    String[] interfaceNames() {
        Type[] refs = owner.interfaceRefs();
        boolean annotation = isAnnotationType();
        int extra = 0;
        if (annotation && !hasInterface("java/lang/annotation/Annotation")) extra = 1;
        if (refs.length == 0 && extra == 0) return null;
        String[] names = new String[refs.length + extra];
        for (int i = 0; i < refs.length; i++) names[i] = erasedJvm(refs[i]);
        if (extra == 1) names[refs.length] = "java/lang/annotation/Annotation";
        return names;
    }

    String classSignature() {
        if (owner.typeParams().length == 0
                && !needsSignature(owner.superRef())
                && !anyNeedsSignature(owner.interfaceRefs())) {
            return null;
        }
        StringBuilder out = new StringBuilder();
        appendTypeParameters(out, owner.typeParams());
        Type superType = owner.superRef() == null
                ? (superName() == null ? null : TypeRef.resolved(superName()))
                : owner.superRef();
        if (superType != null) out.append(signature(superType));
        else out.append("Ljava/lang/Object;");
        for (Type iface : owner.interfaceRefs()) out.append(signature(iface));
        if (isAnnotationType() && !hasInterface("java/lang/annotation/Annotation")) {
            out.append("Ljava/lang/annotation/Annotation;");
        }
        return out.toString();
    }

    String descriptor(Type type) {
        Type plain = unwrap(type);
        if (plain instanceof Primitive primitive) {
            return switch (primitive) {
                case VOID -> "V";
                case BOOLEAN -> "Z";
                case BYTE -> "B";
                case CHAR -> "C";
                case SHORT -> "S";
                case INT -> "I";
                case LONG -> "J";
                case FLOAT -> "F";
                case DOUBLE -> "D";
            };
        }
        if (plain instanceof Array array) return "[" + descriptor(array.element());
        if (plain instanceof TypeVariable) return "Ljava/lang/Object;";
        return "L" + erasedJvm(plain) + ";";
    }

    String signature(Type type) {
        Type plain = unwrap(type);
        if (plain instanceof TypeVariable variable) return "T" + variable.name() + ";";
        if (plain instanceof Array array) return "[" + signature(array.element());
        if (plain instanceof Parameterized parameterized) {
            StringBuilder out = new StringBuilder("L")
                    .append(resolve(parameterized.raw())).append('<');
            for (Type argument : parameterized.typeArgs()) {
                Type arg = unwrap(argument);
                if (arg instanceof Wildcard wildcard) {
                    switch (wildcard.kind()) {
                        case UNBOUNDED -> out.append('*');
                        case EXTENDS -> out.append('+').append(signature(wildcard.bound()));
                        case SUPER -> out.append('-').append(signature(wildcard.bound()));
                    }
                } else {
                    out.append(signature(arg));
                }
            }
            return out.append(">;").toString();
        }
        if (plain instanceof Wildcard wildcard) {
            return wildcard.bound() == null ? "Ljava/lang/Object;" : signature(wildcard.bound());
        }
        return descriptor(plain);
    }

    String fieldSignature(Type type) {
        return needsSignature(type) ? signature(type) : null;
    }

    String methodDescriptor(MethodEntry method) {
        StringBuilder out = new StringBuilder("(");
        for (Type parameter : method.paramTypes()) out.append(descriptor(parameter));
        return out.append(')').append(descriptor(method.returnType())).toString();
    }

    String methodSignature(MethodEntry method) {
        if (method.typeParams().length == 0
                && !anyNeedsSignature(method.paramTypes())
                && !needsSignature(method.returnType())
                && !anyNeedsSignature(method.throwsTypes())) {
            return null;
        }
        StringBuilder out = new StringBuilder();
        appendTypeParameters(out, method.typeParams());
        out.append('(');
        for (Type parameter : method.paramTypes()) out.append(signature(parameter));
        out.append(')').append(signature(method.returnType()));
        for (Type thrown : method.throwsTypes()) out.append('^').append(signature(thrown));
        return out.toString();
    }

    String erasedJvm(Type type) {
        Type plain = unwrap(type);
        if (plain instanceof Parameterized parameterized) return resolve(parameterized.raw());
        if (plain instanceof TypeRef ref) return resolve(ref);
        if (plain instanceof Array) return descriptor(plain);
        return "java/lang/Object";
    }

    String resolve(TypeRef ref) {
        if (ref instanceof TypeRef.Resolved resolved) return qualify(resolved.jvmBinaryName());
        String simple = ((TypeRef.Unresolved) ref).simpleName();
        if (!(owner instanceof SourceTypeEntry)) return simple.replace('.', '/');
        return resolveCached(simple);
    }

    /**
     * Source indexing emits a member select rooted at a simple name (e.g.
     * {@code Storage.Builder} under {@code import ...metastore.Storage}) as the
     * package-less {@code Storage$Builder}. Re-attach the package by resolving
     * the outermost segment the same way a bare simple name is resolved.
     */
    private String qualify(String jvmBinaryName) {
        if (jvmBinaryName.indexOf('/') >= 0 || !(owner instanceof SourceTypeEntry)) {
            return jvmBinaryName;
        }
        int dollar = jvmBinaryName.indexOf('$');
        String outerSimple = dollar < 0 ? jvmBinaryName : jvmBinaryName.substring(0, dollar);
        String nested = dollar < 0 ? "" : jvmBinaryName.substring(dollar);
        return resolveCached(outerSimple) + nested;
    }

    private String resolveCached(String simple) {
        String cached = resolvedSimpleNames.get(simple);
        if (cached != null) return cached;
        String resolved = resolveSimple(simple);
        resolvedSimpleNames.put(simple, resolved);
        return resolved;
    }

    /**
     * Resolve a simple type name against {@code owner}'s compilation unit,
     * following JLS 6.5.5.1: member types in scope first, then types declared
     * in the same compilation unit, single-type imports, the same package,
     * on-demand imports and finally the implicit {@code java.lang} import.
     * Falls back to the same package so an unresolvable name is reported
     * against the package that was actually searched.
     */
    private String resolveSimple(String simple) {
        String inScope = memberTypeInScope(simple, owner, new HashSet<>());
        if (inScope != null) return inScope;

        SourceResolutionHints hints = ((SourceTypeEntry) owner).hints();
        String samePackage = join(hints.sourcePackage(), simple);
        if (hints.siblingSimpleNames().contains(simple) && available(samePackage)) {
            return samePackage;
        }
        String imported = hints.singleTypeImports().get(simple);
        if (available(imported)) return imported;
        if (available(samePackage)) return samePackage;
        for (String packageName : hints.onDemandImports()) {
            String candidate = join(packageName, simple);
            if (available(candidate)) return candidate;
        }
        String javaLang = "java/lang/" + simple;
        return available(javaLang) ? javaLang : samePackage;
    }

    /**
     * Search for {@code simple} as a member type visible from the body of
     * {@code scope}: walk outwards through the lexically enclosing types and,
     * at each level, search that type and all of its supertypes for a nested
     * type with a matching simple name. The indexer has no cross-file view and
     * emits such a reference as a bare simple name, so without this walk a
     * nested or inherited type like {@code Table.Builder} referenced as
     * {@code Builder} would be looked for in the wrong package.
     */
    private String memberTypeInScope(String simple, TypeEntry scope, Set<String> visited) {
        for (TypeEntry current = scope; current != null; current = outerOf(current)) {
            String hit = inheritedMemberType(simple, current, visited);
            if (hit != null) return hit;
        }
        return null;
    }

    private String inheritedMemberType(String simple, TypeEntry type, Set<String> visited) {
        if (type == null || !visited.add(type.jvmOwnerName())) return null;
        for (String nested : type.innerTypeJvmNames()) {
            if (simpleNameOf(nested).equals(simple)) return nested;
        }
        for (Type superType : supertypesOf(type)) {
            String hit = inheritedMemberType(simple, entryForSupertype(superType, type), visited);
            if (hit != null) return hit;
        }
        return null;
    }

    private List<Type> supertypesOf(TypeEntry type) {
        List<Type> out = new ArrayList<>();
        if (type.superRef() != null) out.add(type.superRef());
        out.addAll(Arrays.asList(type.interfaceRefs()));
        return out;
    }

    /**
     * Resolve a supertype reference to its indexed entry. A supertype named by
     * a bare simple name is resolved against the subtype's imports and package
     * only: re-entering the member-type search here would be mutually recursive
     * with {@link #inheritedMemberType}, and a supertype is in practice always
     * an import, a fully-qualified name, a same-package type or an on-demand
     * import.
     */
    private TypeEntry entryForSupertype(Type superType, TypeEntry context) {
        Type plain = unwrap(superType);
        TypeRef ref = plain instanceof Parameterized parameterized ? parameterized.raw()
                : plain instanceof TypeRef typeRef ? typeRef
                : null;
        if (ref == null || !(context instanceof SourceTypeEntry source)) return null;
        String jvmName = switch (ref) {
            case TypeRef.Resolved resolved -> resolved.jvmBinaryName();
            case TypeRef.Unresolved unresolved -> importedName(unresolved.simpleName(), source.hints());
        };
        if (jvmName == null || jvmName.isEmpty()) return null;
        return classpath.pick(index.getAll(jvmName), TypeEntry::sourceUri);
    }

    private String importedName(String simple, SourceResolutionHints hints) {
        String samePackage = join(hints.sourcePackage(), simple);
        if (hints.siblingSimpleNames().contains(simple) && available(samePackage)) return samePackage;
        String imported = hints.singleTypeImports().get(simple);
        if (available(imported)) return imported;
        if (available(samePackage)) return samePackage;
        for (String packageName : hints.onDemandImports()) {
            String candidate = join(packageName, simple);
            if (available(candidate)) return candidate;
        }
        return null;
    }

    private TypeEntry outerOf(TypeEntry entry) {
        String jvmName = entry.jvmOwnerName();
        int dollar = jvmName.lastIndexOf('$');
        if (dollar <= 0) return null;
        return classpath.pick(index.getAll(jvmName.substring(0, dollar)), TypeEntry::sourceUri);
    }

    private static String simpleNameOf(String jvmName) {
        return jvmName.substring(Math.max(jvmName.lastIndexOf('$'), jvmName.lastIndexOf('/')) + 1);
    }

    /**
     * Emit {@code <T:Lcls;:Lifc;>} per JVMS 4.7.9.1: a type parameter has one
     * optional class bound followed by any number of interface bounds, and a
     * parameter bounded only by interfaces writes an empty class bound
     * ({@code T::Lifc;}). Indexing records a bound list without saying which
     * kind each bound is - the source indexer cannot know, since telling a
     * class from an interface needs resolution - so the split is recovered
     * here by asking the index what the first bound actually is. Declaring an
     * interface bound as a class bound makes ECJ reject otherwise valid type
     * arguments against that parameter.
     */
    private void appendTypeParameters(StringBuilder out, TypeParamRef[] params) {
        if (params.length == 0) return;
        out.append('<');
        for (TypeParamRef param : params) {
            out.append(param.name()).append(':');
            Type[] bounds = param.bounds();
            int next = 0;
            if (bounds.length > 0 && !isInterfaceBound(bounds[0])) {
                out.append(signature(bounds[0]));
                next = 1;
            }
            for (int i = next; i < bounds.length; i++) {
                out.append(':').append(signature(bounds[i]));
            }
        }
        out.append('>');
    }

    private boolean isInterfaceBound(Type bound) {
        Type plain = unwrap(bound);
        if (plain instanceof TypeVariable || plain instanceof Array) return false;
        TypeEntry entry = classpath.pick(index.getAll(erasedJvm(plain)), TypeEntry::sourceUri);
        return entry != null && IndexBinaryAccessFlags.isInterfaceOwner(entry);
    }

    private boolean hasInterface(String jvmName) {
        for (Type iface : owner.interfaceRefs()) {
            if (jvmName.equals(erasedJvm(iface))) return true;
        }
        return false;
    }

    private boolean isAnnotationType() {
        if (owner instanceof SourceTypeEntry source) {
            return source.declKind() == TypeDeclKind.ANNOTATION;
        }
        return (IndexBinaryAccessFlags.rawModifiers(owner) & IndexBinaryAccessFlags.ACC_ANNOTATION) != 0;
    }

    private boolean available(String jvmName) {
        return jvmName != null
                && classpath.pick(index.getAll(jvmName), TypeEntry::sourceUri) != null;
    }

    private static String join(String packageJvm, String simple) {
        return packageJvm == null || packageJvm.isEmpty() ? simple : packageJvm + "/" + simple;
    }

    static boolean needsSignature(Type type) {
        Type plain = unwrap(type);
        return plain instanceof TypeVariable
                || plain instanceof Parameterized
                || plain instanceof Wildcard
                || plain instanceof Array array && needsSignature(array.element());
    }

    static boolean anyNeedsSignature(Type[] types) {
        for (Type type : types) if (needsSignature(type)) return true;
        return false;
    }

    static Type unwrap(Type type) {
        Type current = type;
        while (current instanceof Annotated annotated) current = annotated.inner();
        return current;
    }

    static String sourceFile(TypeEntry entry) {
        String uri = entry.resourceUri();
        int slash = Math.max(uri == null ? -1 : uri.lastIndexOf('/'),
                uri == null ? -1 : uri.lastIndexOf('\\'));
        return slash < 0 ? entry.jvmOwnerName() + ".java" : uri.substring(slash + 1);
    }
}
