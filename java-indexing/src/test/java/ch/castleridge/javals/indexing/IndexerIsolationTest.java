package ch.castleridge.javals.indexing;

import org.junit.jupiter.api.Test;

import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaFileObject;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IndexerIsolationTest {

    @Test
    void defaultMode_noErrorsForJdkTypes() throws Exception {
        Path fixture = fixturePath("UsesString.java");
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        new Indexer().compileOnly(fixture, false, diagnostics);
        long errors =
                diagnostics.getDiagnostics().stream().filter(d -> d.getKind() == Diagnostic.Kind.ERROR).count();
        assertEquals(0, errors, diagnostics.getDiagnostics().toString());
    }

    @Test
    void isolateMode_javaLangStillResolves() throws Exception {
        Path fixture = fixturePath("UsesString.java");
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        boolean completed = new Indexer().compileOnly(fixture, true, diagnostics);
        assertTrue(completed, "IsolatingJavaFileManager passes through java.lang so analysis should finish");
        long errors =
                diagnostics.getDiagnostics().stream().filter(d -> d.getKind() == Diagnostic.Kind.ERROR).count();
        assertEquals(0, errors, diagnostics.getDiagnostics().toString());
    }

    @Test
    void isolateMode_nonJavaLangJdkTypesStillHidden() throws Exception {
        Path fixture = fixturePath("UsesArrayList.java");
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        boolean completed = new Indexer().compileOnly(fixture, true, diagnostics);
        long errors =
                diagnostics.getDiagnostics().stream().filter(d -> d.getKind() == Diagnostic.Kind.ERROR).count();
        assertFalse(
                completed && errors == 0,
                "JDK types outside java.lang should not fully resolve in isolate mode: "
                        + diagnostics.getDiagnostics());
    }

    private static Path fixturePath(String name) throws URISyntaxException {
        var url = IndexerIsolationTest.class.getResource("/fixtures/" + name);
        if (url == null) {
            throw new IllegalStateException("missing test resource: fixtures/" + name);
        }
        return Paths.get(url.toURI());
    }
}
