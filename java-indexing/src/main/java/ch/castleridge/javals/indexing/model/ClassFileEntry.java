package ch.castleridge.javals.indexing.model;

import ch.castleridge.javals.indexing.intern.Interner;

/**
 * Minimal index record for a {@code .class} file: enough to enumerate packages,
 * resolve classpath shadowing, and locate the real resource for bytecode reading.
 *
 * <p>{@link #resourcePath()} is compact relative to {@link #sourceUri()} when
 * possible; prefer {@link #resourceUri()} for the full location.
 */
public record ClassFileEntry(
        String resourcePath,
        String sourceUri,
        String jvmOwnerName) {

    public ClassFileEntry {
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

    public String packageJvm() {
        int slash = jvmOwnerName.lastIndexOf('/');
        return slash < 0 ? "" : jvmOwnerName.substring(0, slash);
    }
}
