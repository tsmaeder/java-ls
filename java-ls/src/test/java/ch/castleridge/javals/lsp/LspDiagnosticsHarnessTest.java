package ch.castleridge.javals.lsp;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import org.eclipse.lsp4j.Diagnostic;
import org.eclipse.lsp4j.DiagnosticSeverity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
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
    @EnabledIf("vertxWorkspacePresent")
    void vertxJsonObjectBase64EncoderImportHasNoErrors() throws Exception {
        Path workspace = Path.of("../test-projects/vert.x").toAbsolutePath().normalize();
        Path jsonObject = workspace.resolve("vertx-core/src/main/java/io/vertx/core/json/JsonObject.java");

        try (LspDiagnosticsHarness harness = LspDiagnosticsHarness.start(workspace)) {
            harness.awaitIndexReady(Duration.ofSeconds(600));
            List<Diagnostic> diagnostics = harness.openAndAwaitDiagnostics(jsonObject, Duration.ofSeconds(120));
            List<Diagnostic> errors = diagnostics.stream()
                    .filter(d -> d.getSeverity() == DiagnosticSeverity.Error)
                    .toList();
            List<Diagnostic> encoderErrors = errors.stream()
                    .filter(d -> d.getMessage() != null && d.getMessage().contains("Encoder"))
                    .toList();
            List<Diagnostic> linkedHashMapErrors = errors.stream()
                    .filter(d -> d.getMessage() != null && d.getMessage().contains("LinkedHashMap"))
                    .toList();
            assertTrue(encoderErrors.isEmpty(), () -> "unexpected Encoder errors: " + encoderErrors);
            assertTrue(linkedHashMapErrors.isEmpty(),
                    () -> "unexpected LinkedHashMap errors: " + linkedHashMapErrors);
        }
    }

    static boolean vertxWorkspacePresent() {
        Path workspace = Path.of("../test-projects/vert.x").toAbsolutePath().normalize();
        Path jsonObject = workspace.resolve("vertx-core/src/main/java/io/vertx/core/json/JsonObject.java");
        return Files.isDirectory(workspace) && Files.isRegularFile(jsonObject);
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
