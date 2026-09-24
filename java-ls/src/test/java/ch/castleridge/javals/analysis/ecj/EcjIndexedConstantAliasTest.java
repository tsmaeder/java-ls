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
 * Source-indexed {@code static final} fields often alias another constant
 * ({@code DOUBLE = DoubleType.NAME}) instead of a literal. ECJ needs the
 * folded value or every {@code @SqlType(StandardTypes.DOUBLE)} use is
 * reported as a non-constant annotation member.
 */
class EcjIndexedConstantAliasTest {
    private static final String CLASSPATH_URI = "index:///constants/";

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
    void foldsAliasedStringConstantAsAnnotationValue() {
        indexSource("p/Names.java", """
                package p;
                public final class Names {
                    public static final String VALUE = "double";
                }
                """);
        indexSource("p/Aliases.java", """
                package p;
                public final class Aliases {
                    public static final String NAME = Names.VALUE;
                }
                """);
        indexSource("p/Ann.java", """
                package p;
                public @interface Ann {
                    String value();
                }
                """);

        AnalysisSession session = new EcjWorkspaceCompiler().analyze(
                "file:///workspace/q/Use.java",
                """
                        package q;
                        import p.Aliases;
                        import p.Ann;
                        @Ann(Aliases.NAME)
                        class Use {}
                        """,
                index, classpath);
        assertNoErrors(session);
    }

    @Test
    void foldsAliasedIntConstantAsSwitchCase() {
        indexSource("p/Names.java", """
                package p;
                public final class Names {
                    public static final int INSERT = 1;
                }
                """);
        indexSource("p/Aliases.java", """
                package p;
                public final class Aliases {
                    public static final int INSERT_OPERATION_NUMBER = Names.INSERT;
                }
                """);

        AnalysisSession session = new EcjWorkspaceCompiler().analyze(
                "file:///workspace/q/Use.java",
                """
                        package q;
                        import p.Aliases;
                        class Use {
                            void m(int op) {
                                switch (op) {
                                    case Aliases.INSERT_OPERATION_NUMBER -> {}
                                    default -> {}
                                }
                            }
                        }
                        """,
                index, classpath);
        assertNoErrors(session);
    }
}
