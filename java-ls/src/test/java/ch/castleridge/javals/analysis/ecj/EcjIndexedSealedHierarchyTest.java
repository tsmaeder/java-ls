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
package ch.castleridge.javals.analysis.ecj;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;

import org.eclipse.lsp4j.DiagnosticSeverity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import ch.castleridge.javals.analysis.AnalysisSession;
import ch.castleridge.javals.classpath.ClasspathOrder;
import ch.castleridge.javals.classpath.UriClasspathEntry;
import ch.castleridge.javals.indexing.index.InMemoryIndex;
import ch.castleridge.javals.indexing.scan.JrtInput;
import ch.castleridge.javals.indexing.scan.Scanner;
import ch.castleridge.javals.indexing.source.javac.JavacSourceIndexer;

/**
 * A source-indexed {@code permits} clause names same-package types by simple
 * name. ECJ's binary sealed-type check looks those names up as binary types,
 * so they have to be resolved to JVM names or every permitted subtype reports
 * "indirectly referenced from required type".
 */
class EcjIndexedSealedHierarchyTest {
    private static final String CLASSPATH_URI = "index:///sealed/";

    private InMemoryIndex index;
    private ClasspathOrder classpath;

    @BeforeEach
    void indexJdk() throws Exception {
        index = new InMemoryIndex();
        JrtInput jrt = new JrtInput(Path.of(System.getProperty("java.home")));
        assertTrue(new Scanner().scanAll(List.of(jrt), index).isEmpty());
        classpath = new ClasspathOrder(
                List.of(UriClasspathEntry.of(CLASSPATH_URI), UriClasspathEntry.of(jrt.sourceUri().toString())),
                false);
    }

    private void indexSource(String path, String source) {
        JavacSourceIndexer.index(path, CLASSPATH_URI, source, index);
    }

    private static void assertNoErrors(AnalysisSession session) {
        assertTrue(session.isUsable());
        assertTrue(session.diagnostics().stream()
                        .noneMatch(diagnostic -> diagnostic.severity() == DiagnosticSeverity.Error),
                () -> "Unexpected ECJ diagnostics: " + session.diagnostics());
    }

    @Test
    void analyzesPermittedRecordAgainstIndexedSealedInterface() {
        indexSource("demo/Shape.java", """
                package demo;
                public sealed interface Shape permits Circle, Square {
                    int size();
                }
                """);
        indexSource("demo/Circle.java", """
                package demo;
                public record Circle(int r) implements Shape {
                    @Override
                    public int size() { return r; }
                }
                """);
        indexSource("demo/Square.java", """
                package demo;
                public record Square(int s) implements Shape {
                    @Override
                    public int size() { return s; }
                }
                """);

        AnalysisSession session = new EcjWorkspaceCompiler().analyze(
                "file:///workspace/demo/Circle.java",
                """
                        package demo;
                        public record Circle(int r) implements Shape {
                            @Override
                            public int size() { return r; }
                        }
                        """,
                index, classpath);
        assertNoErrors(session);
    }

    @Test
    void exhaustiveSwitchOnIndexedSealedTypeNeedsNoDefault() {
        indexSource("demo/Shape.java", """
                package demo;
                public sealed interface Shape permits Circle, Square {}
                """);
        indexSource("demo/Circle.java", "package demo; public record Circle(int r) implements Shape {}");
        indexSource("demo/Square.java", "package demo; public record Square(int s) implements Shape {}");

        AnalysisSession session = new EcjWorkspaceCompiler().analyze(
                "file:///workspace/demo/Use.java",
                """
                        package demo;
                        class Use {
                            int size(Shape shape) {
                                return switch (shape) {
                                    case Circle c -> c.r();
                                    case Square s -> s.s();
                                };
                            }
                        }
                        """,
                index, classpath);
        assertNoErrors(session);
    }
}
