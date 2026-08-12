package ch.castleridge.javals.analysis.ecj;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.nio.file.Path;
import java.util.List;

import org.eclipse.jdt.internal.compiler.env.IBinaryType;
import org.eclipse.lsp4j.DiagnosticSeverity;
import org.junit.jupiter.api.Test;

import ch.castleridge.javals.analysis.AnalysisSession;
import ch.castleridge.javals.classpath.ClasspathOrder;
import ch.castleridge.javals.classpath.UriClasspathEntry;
import ch.castleridge.javals.indexing.index.InMemoryIndex;
import ch.castleridge.javals.indexing.scan.JrtInput;
import ch.castleridge.javals.indexing.scan.Scanner;
import ch.castleridge.javals.indexing.source.ecj.EcjSourceIndexer;

class EcjIndexedInterfaceHierarchyTest {
    private static final String CLASSPATH_URI = "index:///hierarchy/";

    @Test
    void indexedInterfacesReportObjectAsSuperclass() throws Exception {
        InMemoryIndex index = new InMemoryIndex();
        EcjSourceIndexer.index(
                "demo/Plain.java",
                CLASSPATH_URI,
                "package demo; public interface Plain { void a(); }",
                index);
        EcjSourceIndexer.index(
                "demo/Marker.java",
                CLASSPATH_URI,
                "package demo; public @interface Marker { }",
                index);

        ClasspathOrder classpath = new ClasspathOrder(List.of(UriClasspathEntry.of(CLASSPATH_URI)), false);
        IBinaryType plain = IndexBinaryType.of(index.getAll("demo/Plain").get(0), index, classpath);
        IBinaryType marker = IndexBinaryType.of(index.getAll("demo/Marker").get(0), index, classpath);

        assertEquals("java/lang/Object", new String(plain.getSuperclassName()));
        assertEquals("java/lang/Object", new String(marker.getSuperclassName()));
    }

    /**
     * ECJ sorts the collected super interfaces of a verified type and assumes
     * every one of them has a superclass, so index-backed interfaces have to
     * mirror the class file convention of naming {@code java/lang/Object}.
     */
    @Test
    void verifiesTypeImplementingSeveralIndexedInterfaces() throws Exception {
        InMemoryIndex index = new InMemoryIndex();
        JrtInput jrt = new JrtInput(Path.of(System.getProperty("java.home")));
        assertTrue(new Scanner().scanAll(List.of(jrt), index).isEmpty());
        EcjSourceIndexer.index(
                "demo/First.java",
                CLASSPATH_URI,
                "package demo; public interface First { void first(); }",
                index);
        EcjSourceIndexer.index(
                "demo/Second.java",
                CLASSPATH_URI,
                "package demo; public interface Second extends First { void second(); }",
                index);

        ClasspathOrder classpath = new ClasspathOrder(
                List.of(UriClasspathEntry.of(CLASSPATH_URI), UriClasspathEntry.of(jrt.sourceUri().toString())),
                false);
        String source = """
                package demo;
                class Use implements First, Second {
                    public void first() {}
                    public void second() {}
                }
                """;

        AnalysisSession session = new EcjWorkspaceCompiler().analyze(
                URI.create("file:///workspace/demo/Use.java"), source, index, classpath);

        assertTrue(session.isUsable());
        assertFalse(session.diagnostics().stream()
                .anyMatch(diagnostic -> diagnostic.severity() == DiagnosticSeverity.Error),
                () -> "Unexpected ECJ diagnostics: " + session.diagnostics());
    }
}
