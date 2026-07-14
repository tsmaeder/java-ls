package ch.castleridge.javals.indexing.model;

/**
 * Minimal index record for a {@code module-info.class}: module name, declared
 * packages, and the resource URI of the real class file.
 */
public record ModuleFileEntry(
        String resourceUri,
        String sourceUri,
        String name,
        String[] packages) {

    public ModuleFileEntry {
        if (name == null || name.isEmpty()) {
            throw new IllegalArgumentException("module name must be non-empty");
        }
        packages = EmptyArrays.copyOrEmpty(packages, EmptyArrays.STRING);
    }
}
