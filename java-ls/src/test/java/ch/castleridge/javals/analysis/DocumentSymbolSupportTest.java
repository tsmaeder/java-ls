/**
 * Copyright 2026 by Castle Ridge Software GmbH
 *
 * Author: Thomas Mäder, Castle Ridge Software
 *
 */
package ch.castleridge.javals.analysis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.eclipse.lsp4j.DocumentSymbol;
import org.eclipse.lsp4j.SymbolKind;
import org.eclipse.lsp4j.SymbolTag;
import org.junit.jupiter.api.Test;

import ch.castleridge.javals.analysis.ecj.EcjDietSources;
import ch.castleridge.javals.analysis.javac.JavacDietSources;
import ch.castleridge.javals.ast.CompilationUnit;

class DocumentSymbolSupportTest {

    private static final String SOURCE = """
            package demo;

            public class Outline {
                int x, y;

                @Deprecated
                void old() {}

                void fresh() {}

                class Nested {
                    void nestedMethod() {}
                }

                enum Color {
                    RED,
                    GREEN
                }
            }

            record Point(int x, int y) {}
            """;

    @Test
    void hierarchicalOutlineFromInMemoryEcjDiet() {
        CompilationUnit cu = EcjDietSources.lower("file:///workspace/demo/Outline.java", SOURCE);
        assertNotNull(cu);
        assertOutline(DocumentSymbolSupport.from(cu));
    }

    @Test
    void hierarchicalOutlineFromInMemoryJavacDiet() {
        CompilationUnit cu = JavacDietSources.lower("file:///workspace/demo/Outline.java", SOURCE);
        assertNotNull(cu);
        assertOutline(DocumentSymbolSupport.from(cu));
    }

    private static void assertOutline(List<DocumentSymbol> roots) {
        assertEquals(2, roots.size());

        DocumentSymbol outline = find(roots, "Outline");
        assertEquals(SymbolKind.Class, outline.getKind());
        assertSelectionInsideRange(outline);
        assertNotNull(outline.getChildren());

        DocumentSymbol x = find(outline.getChildren(), "x");
        assertEquals(SymbolKind.Field, x.getKind());
        DocumentSymbol y = find(outline.getChildren(), "y");
        assertEquals(SymbolKind.Field, y.getKind());

        DocumentSymbol old = find(outline.getChildren(), "old");
        assertEquals(SymbolKind.Method, old.getKind());
        assertNotNull(old.getTags());
        assertTrue(old.getTags().contains(SymbolTag.Deprecated));

        DocumentSymbol fresh = find(outline.getChildren(), "fresh");
        assertEquals(SymbolKind.Method, fresh.getKind());
        assertTrue(fresh.getTags() == null || !fresh.getTags().contains(SymbolTag.Deprecated));

        DocumentSymbol nested = find(outline.getChildren(), "Nested");
        assertEquals(SymbolKind.Class, nested.getKind());
        assertNotNull(nested.getChildren());
        DocumentSymbol nestedMethod = find(nested.getChildren(), "nestedMethod");
        assertEquals(SymbolKind.Method, nestedMethod.getKind());

        DocumentSymbol color = find(outline.getChildren(), "Color");
        assertEquals(SymbolKind.Enum, color.getKind());
        assertNotNull(color.getChildren());
        assertEquals(SymbolKind.EnumMember, find(color.getChildren(), "RED").getKind());
        assertEquals(SymbolKind.EnumMember, find(color.getChildren(), "GREEN").getKind());

        DocumentSymbol point = find(roots, "Point");
        assertEquals(SymbolKind.Class, point.getKind());
        assertNotNull(point.getChildren());
        assertEquals(SymbolKind.Field, find(point.getChildren(), "x").getKind());
        assertEquals(SymbolKind.Field, find(point.getChildren(), "y").getKind());
    }

    private static DocumentSymbol find(List<DocumentSymbol> symbols, String name) {
        assertNotNull(symbols);
        for (DocumentSymbol symbol : symbols) {
            if (name.equals(symbol.getName())) {
                return symbol;
            }
        }
        throw new AssertionError("symbol not found: " + name + " in " + names(symbols));
    }

    private static List<String> names(List<DocumentSymbol> symbols) {
        return symbols.stream().map(DocumentSymbol::getName).toList();
    }

    private static void assertSelectionInsideRange(DocumentSymbol symbol) {
        assertNotNull(symbol.getRange());
        assertNotNull(symbol.getSelectionRange());
        assertFalse(symbol.getSelectionRange().getStart().getLine() < symbol.getRange().getStart().getLine());
    }
}
