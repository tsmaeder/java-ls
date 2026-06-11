package ch.castleridge.javals.lsp;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import org.eclipse.lsp4j.Diagnostic;
import org.eclipse.lsp4j.DiagnosticSeverity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LspDiagnosticsHarnessTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(120);

    @Test
    void cleanSourceProducesNoErrors(@TempDir Path workspace) throws Exception {
        Path sourceDir = workspace.resolve("src/main/java/com/example");
        Files.createDirectories(sourceDir);
        writeMbtJson(workspace);
        Path sourceFile = sourceDir.resolve("Hello.java");
        Files.writeString(sourceFile, """
                package com.example;

                public class Hello {
                    public String greet() {
                        return "hello";
                    }
                }
                """);

        try (LspDiagnosticsHarness harness = LspDiagnosticsHarness.start(workspace)) {
            harness.awaitIndexReady(TIMEOUT);
            List<Diagnostic> diagnostics = harness.openAndAwaitDiagnostics(sourceFile, TIMEOUT);
            assertFalse(hasError(diagnostics), () -> "unexpected errors: " + diagnostics);
        }
    }

    @Test
    void brokenSourceProducesErrorDiagnostic(@TempDir Path workspace) throws Exception {
        Path sourceDir = workspace.resolve("src/main/java/com/example");
        Files.createDirectories(sourceDir);
        writeMbtJson(workspace);
        Path sourceFile = sourceDir.resolve("Broken.java");
        Files.writeString(sourceFile, """
                package com.example;

                public class Broken {
                    public void fail() {
                        UndefinedType x = null;
                    }
                }
                """);

        try (LspDiagnosticsHarness harness = LspDiagnosticsHarness.start(workspace)) {
            harness.awaitIndexReady(TIMEOUT);
            List<Diagnostic> diagnostics = harness.openAndAwaitDiagnostics(sourceFile, TIMEOUT);
            assertTrue(hasError(diagnostics), () -> "expected error diagnostic, got: " + diagnostics);
        }
    }

    private static boolean hasError(List<Diagnostic> diagnostics) {
        return diagnostics.stream().anyMatch(d -> d.getSeverity() == DiagnosticSeverity.Error);
    }

    private static void writeMbtJson(Path workspace) throws Exception {
        Files.writeString(workspace.resolve("mbt.json"), """
                {
                  "namespaces": {
                    "org.example:demo:1.0:main": {
                      "compilerOptions": ["-source", "21"],
                      "sources": ["src/main/java"],
                      "classes": ["target/classes"],
                      "dependencyModules": []
                    }
                  },
                  "dependencyModules": []
                }
                """);
    }
}
