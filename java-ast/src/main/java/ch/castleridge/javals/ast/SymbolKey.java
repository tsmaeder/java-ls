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
 *
 * <p>Method keys normally include an erased parameter descriptor
 * ({@code M:origin|Owner#name(param,…)}). A static import of a method name
 * covers every overload, so those identifiers use a name-only key
 * ({@code M:origin|Owner#name}) that {@link #matches(SymbolKey) matches} any
 * overload of the same owner and simple name.
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

    /**
     * Name-only method key for static imports: matches every overload of
     * {@code simpleName} on {@code ownerBinary}.
     */
    public static SymbolKey methodName(String originResourceUri, String ownerBinary, String simpleName) {
        Objects.requireNonNull(originResourceUri, "originResourceUri");
        Objects.requireNonNull(ownerBinary, "ownerBinary");
        Objects.requireNonNull(simpleName, "simpleName");
        return of("M:" + originResourceUri + "|" + ownerBinary + "#" + simpleName,
                simpleName, originResourceUri);
    }

    public boolean matches(SymbolKey other) {
        if (other == null) return false;
        if (fileLocal || other.fileLocal) return false;
        if (matchKey.equals(other.matchKey)) return true;
        return methodNameKeysCompatible(matchKey, other.matchKey);
    }

    /**
     * A name-only method key {@code M:…#name} matches an overload key
     * {@code M:…#name(…)} and vice versa. Rejects prefixes of longer names
     * ({@code #name} vs {@code #name2(…)}).
     */
    private static boolean methodNameKeysCompatible(String a, String b) {
        if (a == null || b == null || a.charAt(0) != 'M' || b.charAt(0) != 'M') return false;
        int aParen = a.indexOf('(');
        int bParen = b.indexOf('(');
        if (aParen < 0 && bParen >= 0) {
            return b.startsWith(a) && b.charAt(a.length()) == '(';
        }
        if (bParen < 0 && aParen >= 0) {
            return a.startsWith(b) && a.charAt(b.length()) == '(';
        }
        return false;
    }
}
