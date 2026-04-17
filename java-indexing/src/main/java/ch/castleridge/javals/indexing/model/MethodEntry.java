package ch.castleridge.javals.indexing.model;

import java.net.URI;
import java.util.List;

/**
 * A single method (or constructor) declaration attached to a
 * {@link TypeEntry}. Return type, parameter types and thrown types are
 * {@link TypeRef}s; for source-derived methods they may include
 * {@link TypeRef.Unresolved} leaves that the class reader resolves later
 * using the enclosing type's {@link SourceResolutionHints}.
 */
public record MethodEntry(
        URI resourceUri,
        String jvmOwnerName,
        int accessFlags,
        String name,
        TypeRef returnType,
        List<TypeRef> paramTypes,
        List<TypeRef> throwsTypes,
        String signatureOrNull,
        List<AnnotationRef> annotations) implements IndexEntry {

    public MethodEntry {
        paramTypes = paramTypes == null ? List.of() : List.copyOf(paramTypes);
        throwsTypes = throwsTypes == null ? List.of() : List.copyOf(throwsTypes);
        annotations = annotations == null ? List.of() : List.copyOf(annotations);
    }

    @Override
    public EntryKind kind() {
        return EntryKind.METHOD;
    }
}
