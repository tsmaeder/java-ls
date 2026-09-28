/**
 * Copyright 2026 by Castle Ridge Software GmbH
 *
 * Author: Thomas Mäder, Castle Ridge Software
 *
 */
package ch.castleridge.javals.ast;

/** Java modifier flag. Stored on AST decls as an {@code int} bitset of {@link #bit()}. */
public enum Modifier {
    PUBLIC,
    PROTECTED,
    PRIVATE,
    ABSTRACT,
    STATIC,
    FINAL,
    STRICTFP,
    TRANSIENT,
    VOLATILE,
    SYNCHRONIZED,
    NATIVE,
    DEFAULT,
    SEALED,
    NON_SEALED;

    public int bit() {
        return 1 << ordinal();
    }

    public static boolean has(int bits, Modifier modifier) {
        return (bits & modifier.bit()) != 0;
    }
}
