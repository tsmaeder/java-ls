package ch.castleridge.javals.indexing.model;

import java.util.List;

/**
 * A single method (or constructor) declaration attached to a
 * {@link TypeEntry}. Return type, parameter types and thrown types are
 * {@link TypeRef}s; for source-derived methods they may include
 * {@link TypeRef.Unresolved} leaves that the class reader resolves later
 * using the enclosing type's {@link SourceResolutionHints}.
 *
 * <p>{@link #varargs()} and {@link #hasBody()} are meaningful for
 * source-derived methods; bytecode entries use defaults and rely on
 * {@link #modifiers()} from ASM.
 *
 * <p>{@link #annotationDefault()} is non-null when this method is an
 * annotation element with a default value (either an
 * {@code AnnotationDefault} attribute in bytecode or a {@code default}
 * clause in source). The actual default value is preserved so that
 * {@code IndexClassReader} can construct an accurate
 * {@code MethodSymbol.defaultValue} attribute; for source-derived
 * defaults the indexer falls back to {@link AnnotationValue.Unsupported}
 * when the default expression cannot be evaluated without a symbol
 * table.
 */
public record MethodEntry(
        String resourceUri,
        String jvmOwnerName,
        int modifiers,
        String name,
        TypeRef returnType,
        List<TypeRef> paramTypes,
        List<TypeRef> throwsTypes,
        List<TypeParamRef> typeParams,
        boolean varargs,
        boolean hasBody,
        AnnotationValue annotationDefault,
        List<AnnotationRef> annotations) implements IndexEntry {

    public MethodEntry {
        paramTypes = paramTypes == null ? List.of() : List.copyOf(paramTypes);
        throwsTypes = throwsTypes == null ? List.of() : List.copyOf(throwsTypes);
        typeParams = typeParams == null ? List.of() : List.copyOf(typeParams);
        annotations = annotations == null ? List.of() : List.copyOf(annotations);
    }

    /**
     * {@code true} when this method has a default value attached. Kept
     * for backward compatibility with the previous boolean field; new
     * code should consult {@link #annotationDefault()} directly to
     * recover the actual value.
     */
    public boolean hasAnnotationDefault() {
        return annotationDefault != null;
    }

    /** Backward-compatible constructor without method type parameters. */
    public MethodEntry(
            String resourceUri,
            String jvmOwnerName,
            int modifiers,
            String name,
            TypeRef returnType,
            List<TypeRef> paramTypes,
            List<TypeRef> throwsTypes,
            List<AnnotationRef> annotations) {
        this(resourceUri, jvmOwnerName, modifiers, name, returnType,
                paramTypes, throwsTypes, List.of(), false, true, null, annotations);
    }

    /** Backward-compatible constructor without varargs/hasBody. */
    public MethodEntry(
            String resourceUri,
            String jvmOwnerName,
            int modifiers,
            String name,
            TypeRef returnType,
            List<TypeRef> paramTypes,
            List<TypeRef> throwsTypes,
            List<TypeParamRef> typeParams,
            List<AnnotationRef> annotations) {
        this(resourceUri, jvmOwnerName, modifiers, name, returnType,
                paramTypes, throwsTypes, typeParams, false, true, null, annotations);
    }

    /** Backward-compatible constructor without annotationDefault. */
    public MethodEntry(
            String resourceUri,
            String jvmOwnerName,
            int modifiers,
            String name,
            TypeRef returnType,
            List<TypeRef> paramTypes,
            List<TypeRef> throwsTypes,
            List<TypeParamRef> typeParams,
            boolean varargs,
            boolean hasBody,
            List<AnnotationRef> annotations) {
        this(resourceUri, jvmOwnerName, modifiers, name, returnType,
                paramTypes, throwsTypes, typeParams, varargs, hasBody, null, annotations);
    }

    @Override
    public EntryKind kind() {
        return EntryKind.METHOD;
    }
}
