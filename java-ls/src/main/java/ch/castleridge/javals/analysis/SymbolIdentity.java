package ch.castleridge.javals.analysis;

import java.util.Objects;
import java.util.Optional;

/**
 * Backend-neutral symbol identity for cross-file reference matching.
 * Key format matches the historical javac {@code SymbolKey} encoding.
 */
public record SymbolIdentity(
        String matchKey,
        String simpleName,
        boolean fileLocal,
        Optional<String> originResourceUri) {

    public SymbolIdentity {
        Objects.requireNonNull(simpleName, "simpleName");
        Objects.requireNonNull(originResourceUri, "originResourceUri");
        if (!fileLocal) {
            Objects.requireNonNull(matchKey, "matchKey");
        }
    }

    public boolean matches(SymbolIdentity other) {
        if (other == null) return false;
        if (fileLocal || other.fileLocal) return false;
        return matchKey.equals(other.matchKey);
    }
}
