/**
 * Copyright 2026 by Castle Ridge Software GmbH
 *
 * Author: Thomas Mäder, Castle Ridge Software
 *
 */
package ch.castleridge.javals.ast;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class SourceFileJavadocTest {

    @Test
    void findsJavadocThroughWhitespace() {
        String text = """
                /** Hello. */
                class A {}
                """;
        SourceFile source = new SourceFile("mem://A.java", text);
        int start = text.indexOf("class");
        assertTrue(source.javadocBefore(start).contains("Hello."));
    }

    @Test
    void findsJavadocThroughReturnTypeBeforeName() {
        String text = """
                /**
                 * Adds one.
                 */
                int inc(int x) { return x + 1; }
                """;
        SourceFile source = new SourceFile("mem://A.java", text);
        int name = text.indexOf("inc(");
        String doc = source.javadocBefore(name);
        assertTrue(doc.contains("Adds one."), doc);
        assertEquals(text.indexOf("/**"), doc.isEmpty() ? -1 : text.indexOf(doc));
    }

    @Test
    void doesNotCrossStatementBoundary() {
        String text = """
                /** Unrelated. */
                int other = 1;
                int inc(int x) { return x + 1; }
                """;
        SourceFile source = new SourceFile("mem://A.java", text);
        int name = text.indexOf("inc(");
        assertEquals("", source.javadocBefore(name));
    }
}
