package ch.castleridge.javals.indexing.model;

import ch.castleridge.javals.indexing.intern.Interner;

/**
 * Minimal index record for a {@code module-info.class}: module name, declared
 * packages, and the resource URI of the real class file.
 *
 * <p>{@link #resourcePath()} is compact relative to {@link #sourceUri()} when
 * possible; prefer {@link #resourceUri()} for the full location.
 */
public record ModuleFileEntry(
        String resourcePath,
        String sourceUri,
        String name,
        String[] packages) {

    public ModuleFileEntry {
        if (name == null || name.isEmpty()) {
            throw new IllegalArgumentException("module name must be non-empty");
        }
        sourceUri = sourceUri == null ? null : Interner.intern(sourceUri);
        resourcePath = ResourceUris.compact(resourcePath, sourceUri);
        packages = EmptyArrays.orEmpty(packages, EmptyArrays.STRING);
    }

    public String resourceUri() {
        return ResourceUris.resolve(sourceUri, resourcePath);
    }
}
