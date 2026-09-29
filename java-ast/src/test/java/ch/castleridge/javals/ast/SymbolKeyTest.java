/**
 * Copyright 2026 by Castle Ridge Software GmbH
 *
 * Author: Thomas Mäder, Castle Ridge Software
 *
 */
package ch.castleridge.javals.ast;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class SymbolKeyTest {

    @Test
    void methodNameKeyMatchesOverloadsBidirectionally() {
        SymbolKey nameOnly = SymbolKey.methodName("jrt:/java.base", "java.util.Objects", "requireNonNull");
        SymbolKey oneArg = SymbolKey.of(
                "M:jrt:/java.base|java.util.Objects#requireNonNull(java.lang.Object)",
                "requireNonNull",
                "jrt:/java.base");
        SymbolKey twoArg = SymbolKey.of(
                "M:jrt:/java.base|java.util.Objects#requireNonNull(java.lang.Object,java.lang.String)",
                "requireNonNull",
                "jrt:/java.base");

        assertTrue(nameOnly.matches(oneArg));
        assertTrue(oneArg.matches(nameOnly));
        assertTrue(nameOnly.matches(twoArg));
        assertTrue(twoArg.matches(nameOnly));
        assertFalse(oneArg.matches(twoArg));
    }

    @Test
    void methodNameKeyDoesNotMatchLongerMethodName() {
        SymbolKey name = SymbolKey.methodName("file:///A.java", "demo.A", "name");
        SymbolKey name2 = SymbolKey.of(
                "M:file:///A.java|demo.A#name2(int)",
                "name2",
                "file:///A.java");
        assertFalse(name.matches(name2));
        assertFalse(name2.matches(name));
    }

    @Test
    void exactKeysStillMatch() {
        SymbolKey a = SymbolKey.of("F:u|demo.A#X", "X", "u");
        SymbolKey b = SymbolKey.of("F:u|demo.A#X", "X", "u");
        assertTrue(a.matches(b));
    }
}
