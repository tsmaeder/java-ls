package ch.castleridge.javals.analysis.javac;

import ch.castleridge.javals.classpath.ClasspathOrder;
import ch.castleridge.javals.classpath.UriClasspathEntry;

import java.net.URI;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import javax.lang.model.element.Element;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;

import org.eclipse.lsp4j.Location;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.junit.jupiter.api.Test;

import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.LineMap;
import com.sun.source.util.TreePath;
import com.sun.source.util.Trees;

import ch.castleridge.javals.indexing.index.Index;
import ch.castleridge.javals.indexing.index.InMemoryIndex;
import ch.castleridge.javals.indexing.scan.JrtInput;
import ch.castleridge.javals.indexing.scan.Scanner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression: lines with leading tabs + a tab after a type name used to report
 * reference ranges shifted onto the following identifier, because LSP columns
 * were taken from javac's tab-expanded {@code LineMap.getColumnNumber}.
 */
class ReferenceFinderTabRangeTest {

    @Test
    void stringFieldTypeRangeIgnoresTabExpansion() throws Exception {
        JrtInput jrt = new JrtInput(java.nio.file.Path.of(System.getProperty("java.home")));
        Index index = new InMemoryIndex();
        List<Throwable> failures = new Scanner().scanAll(List.of(jrt), index);
        assertTrue(failures.isEmpty(), () -> "JRT scan failures: " + failures);

        // Leading tab + tab between type and field name (OSGi / Eclipse style).
        String fieldLine = "\tpublic final static String\tCAPABILITY_USES_DIRECTIVE\t\t= \"uses\";";
        String source = """
                package com.example;

                public class Constants {
                %s
                }
                """.formatted(fieldLine);

        URI docUri = URI.create("mem:///Constants.java");
        ClasspathOrder cp = new ClasspathOrder(
                List.of(jrt.sourceUri()).stream().map(UriClasspathEntry::of).collect(Collectors.toList()),
                false);
        JavacWorkspaceCompiler.Result compiled = JavacWorkspaceCompiler.compile(docUri, source, index, cp);
        CompilationUnitTree cu = compiled.cu();
        assertNotNull(cu);
        Trees trees = compiled.trees();
        Elements elements = compiled.task().getElements();
        Types types = compiled.task().getTypes();
        LineMap lm = cu.getLineMap();

        int stringCol = fieldLine.indexOf("String");
        assertTrue(stringCol >= 0);
        // Line is 0-based index 3 in the source ("package", blank, "public class", field).
        long stringOffset = LspPositions.offsetAt(lm, new Position(3, stringCol));
        assertTrue(stringOffset >= 0);

        TreePath path = TreePathLocator.findAt(trees, cu, stringOffset);
        assertNotNull(path);
        Element element = DefinitionElementResolver.resolve(trees, path);
        assertNotNull(element, "String type must resolve");

        SymbolKey key = SymbolKey.of(element, elements, types, trees).orElse(null);
        assertNotNull(key);

        Set<Location> refs = ReferenceFinder.findReferences(
                cu, trees, elements, types, docUri.toString(), key, element);
        assertFalse(refs.isEmpty(), "expected at least the field-type usage of String");

        Location fieldTypeRef = refs.stream()
                .filter(loc -> loc.getRange().getStart().getLine() == 3)
                .findFirst()
                .orElseThrow(() -> new AssertionError("no reference on field line, got: " + refs));

        Range range = fieldTypeRef.getRange();
        assertEquals(stringCol, range.getStart().getCharacter(),
                () -> "start should be UTF-16 index of String, got " + range);
        assertEquals(stringCol + "String".length(), range.getEnd().getCharacter(),
                () -> "end should be UTF-16 index after String, got " + range);

        // Sanity: the old tab-expanded mapping would have landed on CAPABILITY…
        int capabilityCol = fieldLine.indexOf("CAPABILITY_USES_DIRECTIVE");
        assertTrue(capabilityCol > stringCol);
        assertFalse(
                range.getStart().getCharacter() == capabilityCol
                        && range.getEnd().getCharacter() == capabilityCol + "String".length(),
                "range must not highlight CAPABILITY_USES_DIRECTIVE");
    }

    @Test
    void lspPositionsRoundTripOnTabLine() throws Exception {
        JrtInput jrt = new JrtInput(java.nio.file.Path.of(System.getProperty("java.home")));
        Index index = new InMemoryIndex();
        assertTrue(new Scanner().scanAll(List.of(jrt), index).isEmpty());

        String line = "\tString\tx;";
        String source = "class T {\n" + line + "\n}\n";
        URI docUri = URI.create("mem:///T.java");
        ClasspathOrder cp = new ClasspathOrder(
                List.of(jrt.sourceUri()).stream().map(UriClasspathEntry::of).collect(Collectors.toList()),
                false);
        JavacWorkspaceCompiler.Result compiled = JavacWorkspaceCompiler.compile(docUri, source, index, cp);
        LineMap lm = compiled.cu().getLineMap();

        int stringCol = line.indexOf("String");
        long offset = LspPositions.offsetAt(lm, new Position(1, stringCol));
        Position back = LspPositions.positionAt(lm, offset);
        assertEquals(1, back.getLine());
        assertEquals(stringCol, back.getCharacter());

        // Contrast: tab-expanded column differs from UTF-16 index when a tab precedes.
        long tabExpandedCol = lm.getColumnNumber(offset) - 1;
        assertTrue(tabExpandedCol != stringCol,
                "test setup requires a line where tab expansion differs from UTF-16");
    }
}
