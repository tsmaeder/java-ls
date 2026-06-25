package ch.castleridge.javals.indexing.model;

import java.util.List;

/**
 * A single indexed Java source file reduced to an API stub: package,
 * imports, and externally visible type declarations with method bodies
 * replaced by default returns. Keyed by resource URI rather than JVM
 * binary name because one file may declare several top-level types.
 */
public record PrunedSourceEntry(
        String resourceUri,
        String sourceUri,
        String packageJvm,
        String relativeName,
        String primaryBinaryName,
        List<String> topLevelBinaryNames,
        CharSequence prunedText) {

    public PrunedSourceEntry {
        topLevelBinaryNames = topLevelBinaryNames == null ? List.of() : List.copyOf(topLevelBinaryNames);
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
