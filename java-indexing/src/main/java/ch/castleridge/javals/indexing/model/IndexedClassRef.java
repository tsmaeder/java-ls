package ch.castleridge.javals.indexing.model;

import ch.castleridge.javals.indexing.intern.Interner;

/**
 * Locator metadata shared by full {@link TypeEntry} and minimal
 * {@link ClassFileEntry} records: enough to map a compiled class back to
 * its originating resource and sources-jar companion.
 *
 * <p>{@link #resourcePath()} is compact relative to {@link #sourceUri()} when
 * possible; prefer {@link #resourceUri()} for the full location.
 */
public record IndexedClassRef(
        String resourcePath,
        String sourceUri,
        String jvmOwnerName) {

    public IndexedClassRef {
        sourceUri = sourceUri == null ? null : Interner.intern(sourceUri);
        resourcePath = ResourceUris.compact(resourcePath, sourceUri);
        jvmOwnerName = jvmOwnerName == null ? null : Interner.intern(jvmOwnerName);
    }

    public String resourceUri() {
        return ResourceUris.resolve(sourceUri, resourcePath);
    }

    public String jvmName() {
        return jvmOwnerName;
    }

    public static IndexedClassRef from(TypeEntry entry) {
        String path = switch (entry) {
            case ClassFileTypeEntry c -> c.resourcePath();
            case SourceTypeEntry s -> s.resourcePath();
        };
        return new IndexedClassRef(path, entry.sourceUri(), entry.jvmOwnerName());
    }

    public static IndexedClassRef from(ClassFileEntry entry) {
        return new IndexedClassRef(entry.resourcePath(), entry.sourceUri(), entry.jvmOwnerName());
    }

    public static IndexedClassRef from(PrunedSourceEntry entry, String jvmOwnerName) {
        return new IndexedClassRef(entry.resourcePath(), entry.sourceUri(), jvmOwnerName);
    }
}
