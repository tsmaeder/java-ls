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
 * ECJ decides whether an annotation may appear on a type argument from the
 * {@code @Target} tag bits on the annotation type. Those bits have to be
 * copied from the indexed meta-annotation; otherwise every
 * {@code List<@FileExists File>} use is rejected.
 */
class EcjIndexedAnnotationTargetTest {
    private static final String CLASSPATH_URI = "index:///targets/";

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
    void typeUseTargetAllowsAnnotationOnTypeArgument() {
        indexSource("p/Exists.java", """
                package p;
                import java.lang.annotation.ElementType;
                import java.lang.annotation.Retention;
                import java.lang.annotation.RetentionPolicy;
                import java.lang.annotation.Target;
                @Retention(RetentionPolicy.RUNTIME)
                @Target({ElementType.TYPE_USE, ElementType.PARAMETER})
                public @interface Exists {}
                """);

        AnalysisSession session = new EcjWorkspaceCompiler().analyze(
                "file:///workspace/q/Use.java",
                """
                        package q;
                        import java.util.List;
                        import p.Exists;
                        class Use {
                            List<@Exists String> names;
                        }
                        """,
                index, classpath);
        assertNoErrors(session);
    }
}
