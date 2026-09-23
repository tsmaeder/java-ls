/**
 * Copyright 2026 by Anysphere Inc.
 *
 * Licensed under the MIT License.
 *
 * SPDX-License-Identifier: MIT
 *
 * Author: Thomas Mäder, Castle Ridge Software
 *
 */
package ch.castleridge.javals.analysis.javac;

import ch.castleridge.javals.analysis.AnalysisSession;
import ch.castleridge.javals.analysis.AstDeclarationLocator;
import ch.castleridge.javals.analysis.ResolvedSymbol;
import ch.castleridge.javals.classpath.ClasspathOrder;
import ch.castleridge.javals.classpath.UriClasspathEntry;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.eclipse.lsp4j.Location;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.junit.jupiter.api.Test;

import ch.castleridge.javals.indexing.index.Index;
import ch.castleridge.javals.indexing.index.InMemoryIndex;
import ch.castleridge.javals.indexing.scan.JrtInput;
import ch.castleridge.javals.indexing.scan.Scanner;
import ch.castleridge.javals.indexing.source.javac.JavacSourceIndexer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SymbolLocatorRecordAccessorTest {

    @Test
    void recordAccessorNavigatesToComponentInHeader() throws Exception {
        Path jdk = Path.of(System.getProperty("java.home"));
        org.junit.jupiter.api.Assumptions.assumeTrue(
                Files.isDirectory(jdk),
                "JDK not present");

        JrtInput jrt = new JrtInput(jdk);
        String jrtUri = jrt.sourceUri();

        Path dir = Files.createTempDirectory("record-goto");
        String pointSource = """
                package p;
                public record Point(int x, int y) {}
                """;
        Path pointFile = dir.resolve("Point.java");
        Files.writeString(pointFile, pointSource);
        String dirUri = dir.toUri().toString();
        String pointUri = pointFile.toUri().toString();

        Index index = new InMemoryIndex();
        List<Throwable> failures = new Scanner().scanAll(List.of(jrt), index);
        assertTrue(failures.isEmpty(), () -> "JRT scan failures: " + failures);
        JavacSourceIndexer.index(pointUri, dirUri, pointSource, index);

        String useSource = """
                package q;
                import p.Point;
                class Use {
                    int n(Point p) { return p.x(); }
                }
                """;
        AnalysisSession session = analyze(useSource, dirUri, jrtUri, index);
        String callLine = "    int n(Point p) { return p.x(); }";
        ResolvedSymbol resolved = session.resolveAt(new Position(3, callLine.indexOf(".x") + 1)).orElseThrow();
        Location loc = session.definitionOf(resolved).orElseThrow();
        assertTrue(loc.getUri().contains("Point.java"),
                () -> "definition should open Point.java, got: " + loc.getUri());
        assertEquals("x", snippet(pointSource, loc),
                "accessor should land on the record component name, not the whole type");
    }

    @Test
    void enumValuesNavigatesToEnumType() throws Exception {
        Path jdk = Path.of(System.getProperty("java.home"));
        org.junit.jupiter.api.Assumptions.assumeTrue(
                Files.isDirectory(jdk),
                "JDK not present");

        JrtInput jrt = new JrtInput(jdk);
        String jrtUri = jrt.sourceUri();

        Path dir = Files.createTempDirectory("enum-goto");
        String colorSource = """
                package p;
                public enum Color { RED, GREEN, BLUE }
                """;
        Path colorFile = dir.resolve("Color.java");
        Files.writeString(colorFile, colorSource);
        String dirUri = dir.toUri().toString();
        String colorUri = colorFile.toUri().toString();

        Index index = new InMemoryIndex();
        List<Throwable> failures = new Scanner().scanAll(List.of(jrt), index);
        assertTrue(failures.isEmpty(), () -> "JRT scan failures: " + failures);
        JavacSourceIndexer.index(colorUri, dirUri, colorSource, index);

        String useSource = """
                package q;
                import p.Color;
                class Use {
                    Color[] all() { return Color.values(); }
                }
                """;
        AnalysisSession session = analyze(useSource, dirUri, jrtUri, index);
        String callLine = "    Color[] all() { return Color.values(); }";
        ResolvedSymbol resolved = session.resolveAt(new Position(3, callLine.indexOf("values"))).orElseThrow();
        Location loc = session.definitionOf(resolved).orElseThrow();
        assertTrue(loc.getUri().contains("Color.java"),
                () -> "definition should open Color.java, got: " + loc.getUri());
        String landed = snippet(colorSource, loc);
        assertTrue(landed.contains("enum Color") || "Color".equals(landed),
                () -> "values() should land on the enum type, got: " + landed);
    }

    private static AnalysisSession analyze(String source, String dirUri, String jrtUri, Index index) {
        ClasspathOrder cp = new ClasspathOrder(
                List.of(dirUri, jrtUri).stream().map(UriClasspathEntry::of).collect(Collectors.toList()),
                false);
        return new JavacWorkspaceCompiler(new AstDeclarationLocator(JavacDietSources::lower), Map.of())
                .analyze(URI.create("mem:///Use.java"), source, index, cp);
    }

    private static String snippet(String source, Location loc) {
        Range range = loc.getRange();
        String[] lines = source.split("\n", -1);
        int startLine = range.getStart().getLine();
        int endLine = range.getEnd().getLine();
        int startCol = range.getStart().getCharacter();
        int endCol = range.getEnd().getCharacter();
        if (startLine == endLine) {
            return lines[startLine].substring(startCol, endCol);
        }
        StringBuilder sb = new StringBuilder();
        sb.append(lines[startLine].substring(startCol));
        for (int i = startLine + 1; i < endLine; i++) {
            sb.append('\n').append(lines[i]);
        }
        sb.append('\n').append(lines[endLine], 0, endCol);
        return sb.toString();
    }
}
