package ch.castleridge.javals.indexing.model;

import java.util.List;

/**
 * A single field declaration attached to a {@link TypeEntry}.
 *
 * <p>The declared type is captured as a {@link TypeRef}; for source-derived
 * fields this may be {@link TypeRef.Unresolved}, to be resolved later by
 * the class reader using the {@link SourceResolutionHints} on the
 * enclosing type.
 */
public record FieldEntry(
        String resourceUri,
        String jvmOwnerName,
        int accessFlags,
        String name,
        TypeRef type,
        List<AnnotationRef> annotations) implements IndexEntry {

    public FieldEntry {
        annotations = annotations == null ? List.of() : List.copyOf(annotations);
    }

    @Override
    public EntryKind kind() {
        return EntryKind.FIELD;
    }
}
