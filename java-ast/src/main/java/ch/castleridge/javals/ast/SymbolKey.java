/**
 * Copyright 2026 by Castle Ridge Software GmbH
 *
 * Author: Thomas Mäder, Castle Ridge Software
 *
 */
package ch.castleridge.javals.ast;

import java.util.Objects;
import java.util.Optional;

/**
 * Cross-file symbol identity ({@code T:}/{@code M:}/{@code F:} origin|binary...).
 */
public record SymbolKey(
        String matchKey,
        String simpleName,
        boolean fileLocal,
        Optional<String> originResourceUri) {

    public SymbolKey {
        Objects.requireNonNull(simpleName, "simpleName");
        originResourceUri = originResourceUri == null ? Optional.empty() : originResourceUri;
        if (!fileLocal) {
            Objects.requireNonNull(matchKey, "matchKey");
        }
    }

    public static SymbolKey local(String simpleName) {
        return new SymbolKey(null, simpleName, true, Optional.empty());
    }

    public static SymbolKey of(String matchKey, String simpleName, String originResourceUri) {
        return new SymbolKey(matchKey, simpleName, false, Optional.ofNullable(originResourceUri));
    }

    public boolean matches(SymbolKey other) {
        if (other == null) return false;
        if (fileLocal || other.fileLocal) return false;
        return matchKey.equals(other.matchKey);
    }
}
