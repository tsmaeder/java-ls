package ch.castleridge.javals.analysis.ecj;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.nio.file.Path;
import java.util.List;

import org.eclipse.lsp4j.DiagnosticSeverity;
import org.junit.jupiter.api.Test;

import ch.castleridge.javals.analysis.AnalysisSession;
import ch.castleridge.javals.classpath.ClasspathOrder;
import ch.castleridge.javals.classpath.UriClasspathEntry;
import ch.castleridge.javals.indexing.index.InMemoryIndex;
import ch.castleridge.javals.indexing.scan.JrtInput;
import ch.castleridge.javals.indexing.scan.Scanner;
import ch.castleridge.javals.indexing.source.javac.JavacSourceIndexer;

class EcjWorkspaceCompilerSmokeTest {
    private static final String CLASSPATH_URI = "index:///smoke/";

    @Test
    void analyzesSourceAgainstIndexedType() throws Exception {
        InMemoryIndex index = new InMemoryIndex();
        JrtInput jrt = new JrtInput(Path.of(System.getProperty("java.home")));
        assertTrue(new Scanner().scanAll(List.of(jrt), index).isEmpty());
        JavacSourceIndexer.index(
                "demo/Hello.java",
                CLASSPATH_URI,
                "package demo; public class Hello { public int value() { return 1; } }",
                index);

        ClasspathOrder classpath = new ClasspathOrder(
                List.of(UriClasspathEntry.of(CLASSPATH_URI), UriClasspathEntry.of(jrt.sourceUri().toString())),
                false);
        IndexNameEnvironment environment = new IndexNameEnvironment(index, classpath);
        assertNotNull(environment.findType(new char[][] {
                "java".toCharArray(), "lang".toCharArray(), "Object".toCharArray()
        }));
        String source = """
                package demo;
                class Use {
                    Hello hello;
                    int read() { return hello.value(); }
                }
                """;

        AnalysisSession session = new EcjWorkspaceCompiler().analyze(
                URI.create("file:///workspace/demo/Use.java"), source, index, classpath);

        assertTrue(session.isUsable());
        assertFalse(session.diagnostics().stream()
                .anyMatch(diagnostic -> diagnostic.severity() == DiagnosticSeverity.Error),
                () -> "Unexpected ECJ diagnostics: " + session.diagnostics());

        // Hello hello; — resolve the type reference on the field
        var resolved = session.resolveAt(new org.eclipse.lsp4j.Position(2, 8));
        assertTrue(resolved.isPresent(), "expected to resolve Hello at field type");
        assertFalse(resolved.get().fileLocal());

        var completions = session.complete(source, new org.eclipse.lsp4j.Position(3, 8), index, classpath);
        assertFalse(completions.isEmpty(), "expected some completions");
    }
}
