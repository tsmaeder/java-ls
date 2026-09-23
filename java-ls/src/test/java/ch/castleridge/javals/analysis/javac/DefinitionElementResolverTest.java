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
import ch.castleridge.javals.analysis.ResolvedSymbol;
import ch.castleridge.javals.classpath.ClasspathOrder;
import ch.castleridge.javals.classpath.UriClasspathEntry;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;

import org.eclipse.lsp4j.Position;
import org.junit.jupiter.api.Test;

import ch.castleridge.javals.indexing.index.Index;
import ch.castleridge.javals.indexing.index.InMemoryIndex;
import ch.castleridge.javals.indexing.scan.JrtInput;
import ch.castleridge.javals.indexing.scan.Scanner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DefinitionElementResolverTest {

    @Test
    void resolvesStaticImportMemberName() throws Exception {
        Path jdk = Path.of(System.getProperty("java.home"));
        org.junit.jupiter.api.Assumptions.assumeTrue(
                Files.isDirectory(jdk),
                "JDK not present");

        JrtInput jrt = new JrtInput(jdk);
        Index index = new InMemoryIndex();
        List<Throwable> failures = new Scanner().scanAll(List.of(jrt), index);
        assertTrue(failures.isEmpty(), () -> "JRT scan failures: " + failures);

        String source = """
                import static java.time.format.DateTimeFormatter.ISO_INSTANT;

                class Use {
                }
                """;
        AnalysisSession session = analyze(source, jrt);
        String importLine = "import static java.time.format.DateTimeFormatter.ISO_INSTANT;";
        ResolvedSymbol resolved = session.resolveAt(new Position(0, importLine.indexOf("ISO_INSTANT"))).orElseThrow();
        assertEquals("ISO_INSTANT", resolved.simpleName());
        assertFalse(resolved.fileLocal());
    }

    @Test
    void elidedAnnotationArgumentResolvesToFieldNotAnnotationType() throws Exception {
        assertAnnotationArgumentResolvesToField(
                """
                        @interface Ann { String value(); }
                        class Names { static final String X = "x"; }
                        @Ann(Names.X)
                        class Use {}
                        """,
                "@Ann(Names.X)");
    }

    @Test
    void explicitAnnotationArgumentResolvesToField() throws Exception {
        assertAnnotationArgumentResolvesToField(
                """
                        @interface Ann { String value(); }
                        class Names { static final String X = "x"; }
                        @Ann(value = Names.X)
                        class Use {}
                        """,
                "@Ann(value = Names.X)");
    }

    private static void assertAnnotationArgumentResolvesToField(String source, String annotationLine)
            throws Exception {
        Path jdk = Path.of(System.getProperty("java.home"));
        org.junit.jupiter.api.Assumptions.assumeTrue(
                Files.isDirectory(jdk),
                "JDK not present");

        JrtInput jrt = new JrtInput(jdk);
        AnalysisSession session = analyze(source, jrt);
        ResolvedSymbol resolved = session.resolveAt(new Position(2, annotationLine.indexOf('X'))).orElseThrow();
        assertEquals("X", resolved.simpleName());
        assertFalse(resolved.fileLocal());
    }

    private static AnalysisSession analyze(String source, JrtInput jrt) throws Exception {
        Index index = new InMemoryIndex();
        List<Throwable> failures = new Scanner().scanAll(List.of(jrt), index);
        assertTrue(failures.isEmpty(), () -> "JRT scan failures: " + failures);
        ClasspathOrder cp = new ClasspathOrder(
                List.of(jrt.sourceUri()).stream().map(UriClasspathEntry::of).collect(Collectors.toList()),
                false);
        return new JavacWorkspaceCompiler().analyze(URI.create("mem:///Use.java"), source, index, cp);
    }
}
