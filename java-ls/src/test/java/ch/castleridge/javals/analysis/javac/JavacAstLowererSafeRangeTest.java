/**
 * Copyright 2026 by Castle Ridge Software GmbH
 *
 * Author: Thomas Mäder, Castle Ridge Software
 *
 */
package ch.castleridge.javals.analysis.javac;

import org.junit.jupiter.api.Test;

import ch.castleridge.javals.ast.SourceRange;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JavacAstLowererSafeRangeTest {

    @Test
    void startPastEofYieldsEmptyRangeAtEof() {
        SourceRange range = assertDoesNotThrow(() -> JavacAstLowerer.safeRange(100, 105, 10));
        assertTrue(range.isPresent());
        assertEquals(10, range.start());
        assertEquals(10, range.end());
    }

    @Test
    void startAtEofYieldsEmptyRange() {
        SourceRange range = JavacAstLowerer.safeRange(5, 8, 5);
        assertEquals(5, range.start());
        assertEquals(5, range.end());
    }

    @Test
    void negativeStartIsNone() {
        SourceRange range = JavacAstLowerer.safeRange(-1, 3, 10);
        assertFalse(range.isPresent());
        assertEquals(SourceRange.NONE, range);
    }

    @Test
    void endClampedToSourceLength() {
        SourceRange range = JavacAstLowerer.safeRange(2, 100, 10);
        assertEquals(2, range.start());
        assertEquals(10, range.end());
    }

    @Test
    void invertedEndRaisedToStartThenClamped() {
        SourceRange range = JavacAstLowerer.safeRange(4, 1, 10);
        assertEquals(4, range.start());
        assertEquals(4, range.end());
    }
}
