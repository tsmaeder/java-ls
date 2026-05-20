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
 * <p>{@link #hasAnnotationDefault()} is true when this method is an
 * annotation element with a default value (either an
 * {@code AnnotationDefault} attribute in bytecode or a {@code default}
 * clause in source). The actual default value is not retained because
 * the LSP only needs the presence flag to satisfy
 * {@code Check.validateAnnotation}.
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
        boolean hasAnnotationDefault,
        List<AnnotationRef> annotations) implements IndexEntry {

    public MethodEntry {
        paramTypes = paramTypes == null ? List.of() : List.copyOf(paramTypes);
        throwsTypes = throwsTypes == null ? List.of() : List.copyOf(throwsTypes);
        typeParams = typeParams == null ? List.of() : List.copyOf(typeParams);
        annotations = annotations == null ? List.of() : List.copyOf(annotations);
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
                paramTypes, throwsTypes, List.of(), false, true, false, annotations);
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
                paramTypes, throwsTypes, typeParams, false, true, false, annotations);
    }

    /** Backward-compatible constructor without hasAnnotationDefault. */
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
                paramTypes, throwsTypes, typeParams, varargs, hasBody, false, annotations);
    }

    @Override
    public EntryKind kind() {
        return EntryKind.METHOD;
    }
}
