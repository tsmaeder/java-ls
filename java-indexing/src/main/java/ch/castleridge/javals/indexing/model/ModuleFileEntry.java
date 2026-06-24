package ch.castleridge.javals.indexing.model;

import java.util.List;

/**
 * Minimal index record for a {@code module-info.class}: module name, declared
 * packages, and the resource URI of the real class file.
 */
public record ModuleFileEntry(
        String resourceUri,
        String sourceUri,
        String name,
        List<String> packages) {

    public ModuleFileEntry {
        if (name == null || name.isEmpty()) {
            throw new IllegalArgumentException("module name must be non-empty");
        }
        packages = packages == null ? List.of() : List.copyOf(packages);
    }
}
