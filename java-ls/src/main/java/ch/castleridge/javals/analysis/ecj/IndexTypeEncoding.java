package ch.castleridge.javals.analysis.ecj;

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
        if (ref instanceof TypeRef.Resolved resolved) return resolved.jvmBinaryName();
        String simple = ((TypeRef.Unresolved) ref).simpleName();
        if (!(owner instanceof SourceTypeEntry source)) return simple.replace('.', '/');
        SourceResolutionHints hints = source.hints();
        String imported = hints.singleTypeImports().get(simple);
        if (available(imported)) return imported;
        if (hints.siblingSimpleNames().contains(simple)) {
            String candidate = join(hints.sourcePackage(), simple);
            if (available(candidate)) return candidate;
        }
        String samePackage = join(hints.sourcePackage(), simple);
        if (available(samePackage)) return samePackage;
        for (String packageName : hints.onDemandImports()) {
            String candidate = join(packageName, simple);
            if (available(candidate)) return candidate;
        }
        String javaLang = "java/lang/" + simple;
        return available(javaLang) ? javaLang : samePackage;
    }

    private void appendTypeParameters(StringBuilder out, TypeParamRef[] params) {
        if (params.length == 0) return;
        out.append('<');
        for (TypeParamRef param : params) {
            out.append(param.name()).append(':');
            Type[] bounds = param.bounds();
            if (bounds.length == 0) {
                out.append("Ljava/lang/Object;");
            } else {
                for (int i = 0; i < bounds.length; i++) {
                    if (i > 0) out.append(':');
                    out.append(signature(bounds[i]));
                }
            }
        }
        out.append('>');
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
