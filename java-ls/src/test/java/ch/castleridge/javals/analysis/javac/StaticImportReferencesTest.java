/**
 * Copyright 2026 by Castle Ridge Software GmbH
 *
 * Author: Thomas Mäder, Castle Ridge Software
 *
 */
package ch.castleridge.javals.analysis.javac;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.eclipse.lsp4j.Location;
import org.eclipse.lsp4j.Position;
import org.junit.jupiter.api.Test;

import ch.castleridge.javals.analysis.AnalysisSession;
import ch.castleridge.javals.analysis.ResolvedSymbol;
import ch.castleridge.javals.classpath.ClasspathOrder;
import ch.castleridge.javals.classpath.UriClasspathEntry;
import ch.castleridge.javals.indexing.index.Index;
import ch.castleridge.javals.indexing.index.InMemoryIndex;
import ch.castleridge.javals.indexing.scan.JrtInput;
import ch.castleridge.javals.indexing.scan.Scanner;

class StaticImportReferencesTest {

    @Test
    void referencesFromRequireNonNullStaticImportFindsAllOverloads() throws Exception {
        Path jdk = Path.of(System.getProperty("java.home"));
        org.junit.jupiter.api.Assumptions.assumeTrue(Files.isDirectory(jdk), "JDK not present");

        String source = """
                import static java.util.Objects.requireNonNull;

                class Use {
                    Object one(Object value) {
                        return requireNonNull(value);
                    }

                    Object two(Object value) {
                        return requireNonNull(value, "missing");
                    }
                }
                """;
        AnalysisSession session = analyze(source);

        String importLine = "import static java.util.Objects.requireNonNull;";
        ResolvedSymbol resolved = session.resolveAt(new Position(0, importLine.indexOf("requireNonNull")))
                .orElseThrow(() -> new AssertionError("expected resolve on static import member"));
        assertEquals("requireNonNull", resolved.simpleName());
        assertFalse(resolved.fileLocal());

        List<Location> references = session.findReferencesTo(resolved.key());
        Set<Integer> lines = references.stream()
                .map(loc -> loc.getRange().getStart().getLine())
                .collect(Collectors.toCollection(LinkedHashSet::new));
        assertEquals(Set.of(0, 4, 8), lines,
                () -> "expected import + both overload call sites, got " + references);
    }

    @Test
    void usageKeyStillMatchesStaticImport() throws Exception {
        Path jdk = Path.of(System.getProperty("java.home"));
        org.junit.jupiter.api.Assumptions.assumeTrue(Files.isDirectory(jdk), "JDK not present");

        String source = """
                import static java.util.Objects.requireNonNull;

                class Use {
                    Object one(Object value) {
                        return requireNonNull(value);
                    }
                }
                """;
        AnalysisSession session = analyze(source);

        ResolvedSymbol fromUse = session.resolveAt(tokenAt(source, "requireNonNull(value)"))
                .orElseThrow();
        assertEquals("requireNonNull", fromUse.simpleName());

        List<Location> references = session.findReferencesTo(fromUse.key());
        Set<Integer> lines = references.stream()
                .map(loc -> loc.getRange().getStart().getLine())
                .collect(Collectors.toCollection(LinkedHashSet::new));
        assertTrue(lines.contains(0),
                () -> "usage key should find the static import, got " + references);
        assertTrue(lines.contains(4),
                () -> "usage key should find the call site, got " + references);
    }

    private static AnalysisSession analyze(String source) throws Exception {
        JrtInput jrt = new JrtInput(Path.of(System.getProperty("java.home")));
        Index index = new InMemoryIndex();
        List<Throwable> failures = new Scanner().scanAll(List.of(jrt), index);
        assertTrue(failures.isEmpty(), () -> "JRT scan failures: " + failures);
        ClasspathOrder cp = new ClasspathOrder(
                List.of(UriClasspathEntry.of(jrt.sourceUri())),
                false);
        return new JavacWorkspaceCompiler().analyze("mem:///Use.java", source, index, cp);
    }

    private static Position tokenAt(String source, String token) {
        int offset = source.indexOf(token);
        assertTrue(offset >= 0, "token not found: " + token);
        // Prefer the call-site occurrence (skip the import line).
        int second = source.indexOf(token, offset + 1);
        if (second >= 0) offset = second;
        int line = 0;
        int column = 0;
        for (int i = 0; i < offset; i++) {
            if (source.charAt(i) == '\n') {
                line++;
                column = 0;
            } else {
                column++;
            }
        }
        return new Position(line, column);
    }
}
