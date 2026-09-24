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
 * A source-indexed enum is {@code Enum<This>} in the class file. Without
 * that signature, {@code Class<Color>} is not a {@code Class<? extends Enum<?>>}
 * and {@code EnumSet} / {@code @EnumSource} reject it.
 */
class EcjIndexedEnumBoundTest {
    private static final String CLASSPATH_URI = "index:///enums/";

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
    void enumClassLiteralSatisfiesEnumBound() {
        indexSource("demo/Color.java", "package demo; public enum Color { RED, BLUE }");
        indexSource("demo/EnumSource.java", """
                package demo;
                import java.lang.annotation.ElementType;
                import java.lang.annotation.Retention;
                import java.lang.annotation.RetentionPolicy;
                import java.lang.annotation.Target;
                @Retention(RetentionPolicy.RUNTIME)
                @Target(ElementType.METHOD)
                public @interface EnumSource {
                    Class<? extends Enum<?>> value();
                }
                """);

        AnalysisSession session = new EcjWorkspaceCompiler().analyze(
                "file:///workspace/demo/Use.java",
                """
                        package demo;
                        class Use {
                            @EnumSource(Color.class)
                            void test() {}
                        }
                        """,
                index, classpath);
        assertNoErrors(session);
    }
}
