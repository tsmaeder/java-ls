package ch.castleridge.javals.indexing.model;

import ch.castleridge.javals.indexing.intern.Interner;

/**
 * A single indexed Java source file reduced to an API stub: package,
 * imports, and externally visible type declarations with method bodies
 * replaced by default returns. Keyed by resource URI rather than JVM
 * binary name because one file may declare several top-level types.
 *
 * <p>{@link #resourcePath()} is compact relative to {@link #sourceUri()} when
 * possible; prefer {@link #resourceUri()} for the full location.
 */
public record PrunedSourceEntry(
        String resourcePath,
        String sourceUri,
        String packageJvm,
        String relativeName,
        String primaryBinaryName,
        String[] topLevelBinaryNames,
        CharSequence prunedText) {

    public PrunedSourceEntry {
        sourceUri = sourceUri == null ? null : Interner.intern(sourceUri);
        resourcePath = ResourceUris.compact(resourcePath, sourceUri);
        topLevelBinaryNames = EmptyArrays.orEmpty(topLevelBinaryNames, EmptyArrays.STRING);
    }

    public String resourceUri() {
        return ResourceUris.resolve(sourceUri, resourcePath);
    }

    public String jvmOwnerName() {
        return primaryBinaryName;
    }

    public boolean declaresType(String jvmName) {
        if (jvmName == null) return false;
        if (jvmName.equals(primaryBinaryName)) return true;
        for (String name : topLevelBinaryNames) {
            if (jvmName.equals(name)) return true;
        }
        return false;
    }
}
