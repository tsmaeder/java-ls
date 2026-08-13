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

import java.net.URI;
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
import ch.castleridge.javals.indexing.source.ecj.EcjSourceIndexer;

/**
 * A record indexed from source only declares its header. The accessors and the
 * canonical constructor that javac writes into the class file have to be
 * synthesized when the entry is handed to ECJ, or every use of a workspace
 * record fails to resolve.
 */
class EcjIndexedRecordTest {
    private static final String CLASSPATH_URI = "index:///records/";

    private InMemoryIndex index;
    private ClasspathOrder classpath;

    @BeforeEach
    void indexJdkAndRecords() throws Exception {
        index = new InMemoryIndex();
        JrtInput jrt = new JrtInput(Path.of(System.getProperty("java.home")));
        assertTrue(new Scanner().scanAll(List.of(jrt), index).isEmpty());
        classpath = new ClasspathOrder(
                List.of(UriClasspathEntry.of(CLASSPATH_URI), UriClasspathEntry.of(jrt.sourceUri().toString())),
                false);
    }

    private void indexSource(String path, String source) {
        EcjSourceIndexer.index(path, CLASSPATH_URI, source, index);
    }

    private AnalysisSession analyze(String source) {
        return new EcjWorkspaceCompiler().analyze(
                URI.create("file:///workspace/demo/Use.java"), source, index, classpath);
    }

    private static void assertNoErrors(AnalysisSession session) {
        assertTrue(session.isUsable());
        assertTrue(session.diagnostics().stream()
                        .noneMatch(diagnostic -> diagnostic.severity() == DiagnosticSeverity.Error),
                () -> "Unexpected ECJ diagnostics: " + session.diagnostics());
    }

    @Test
    void resolvesAccessorsAndCanonicalConstructorOfIndexedRecord() {
        indexSource("demo/Point.java", "package demo; public record Point(int x, int y) {}");

        assertNoErrors(analyze("""
                package demo;
                class Use {
                    int sum() {
                        Point point = new Point(1, 2);
                        return point.x() + point.y();
                    }
                }
                """));
    }

    /**
     * The accessor's generic signature has to survive, not just its erasure:
     * without it {@code tags()} would only be usable as a raw {@code List}.
     */
    @Test
    void keepsGenericComponentTypeOnAccessorAndConstructor() {
        indexSource("demo/Tagged.java", """
                package demo;
                import java.util.List;
                public record Tagged(String name, List<String> tags) {}
                """);

        assertNoErrors(analyze("""
                package demo;
                import java.util.List;
                class Use {
                    String first() {
                        Tagged tagged = new Tagged("n", List.of("a"));
                        return tagged.tags().get(0);
                    }
                }
                """));
    }

    @Test
    void keepsExplicitlyDeclaredAccessorAndConstructor() {
        indexSource("demo/Ranged.java", """
                package demo;
                public record Ranged(int low, int high) {
                    public Ranged {
                        if (low > high) throw new IllegalArgumentException();
                    }
                    public int low() {
                        return low;
                    }
                }
                """);

        assertNoErrors(analyze("""
                package demo;
                class Use {
                    int low() {
                        return new Ranged(1, 2).low();
                    }
                }
                """));
    }

    /**
     * A record never gets a no-argument constructor unless it declares no
     * components, so the synthetic default constructor must not be added.
     */
    @Test
    void rejectsNoArgumentConstructionOfRecordWithComponents() {
        indexSource("demo/Pair.java", "package demo; public record Pair(int a, int b) {}");

        AnalysisSession session = analyze("""
                package demo;
                class Use {
                    Pair make() {
                        return new Pair();
                    }
                }
                """);

        assertTrue(session.diagnostics().stream()
                        .anyMatch(diagnostic -> diagnostic.severity() == DiagnosticSeverity.Error),
                "Expected an error for the missing no-argument constructor");
    }
}
