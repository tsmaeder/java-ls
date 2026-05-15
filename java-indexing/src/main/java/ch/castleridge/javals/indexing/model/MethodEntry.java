package ch.castleridge.javals.indexing.model;

import java.util.List;

/**
 * A single method (or constructor) declaration attached to a
 * {@link TypeEntry}. Return type, parameter types and thrown types are
 * {@link TypeRef}s; for source-derived methods they may include
 * {@link TypeRef.Unresolved} leaves that the class reader resolves later
 * using the enclosing type's {@link SourceResolutionHints}.
 */
public record MethodEntry(
        String resourceUri,
        String jvmOwnerName,
        int accessFlags,
        String name,
        TypeRef returnType,
        List<TypeRef> paramTypes,
        List<TypeRef> throwsTypes,
        List<TypeParamRef> typeParams,
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
            int accessFlags,
            String name,
            TypeRef returnType,
            List<TypeRef> paramTypes,
            List<TypeRef> throwsTypes,
            List<AnnotationRef> annotations) {
        this(resourceUri, jvmOwnerName, accessFlags, name, returnType,
                paramTypes, throwsTypes, List.of(), annotations);
    }

    @Override
    public EntryKind kind() {
        return EntryKind.METHOD;
    }
}
