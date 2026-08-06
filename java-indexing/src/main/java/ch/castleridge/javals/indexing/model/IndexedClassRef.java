package ch.castleridge.javals.indexing.model;

/**
 * Locator metadata for an indexed {@link TypeEntry}: enough to map a compiled
 * class back to its originating resource and sources-jar companion.
 *
 * <p>{@link #resourcePath()} is compact relative to {@link #sourceUri()} when
 * possible; prefer {@link #resourceUri()} for the full location.
 */
public record IndexedClassRef(
        String resourcePath,
        String sourceUri,
        String jvmOwnerName) {

    public IndexedClassRef {
        resourcePath = ResourceUris.compact(resourcePath, sourceUri);
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
}
