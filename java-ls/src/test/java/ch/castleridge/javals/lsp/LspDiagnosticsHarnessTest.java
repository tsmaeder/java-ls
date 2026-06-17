package ch.castleridge.javals.lsp;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.eclipse.lsp4j.Diagnostic;
import org.eclipse.lsp4j.DiagnosticSeverity;
import org.eclipse.lsp4j.Location;
import org.eclipse.lsp4j.Position;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LspDiagnosticsHarnessTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(120);

    @Test
    void referencesFindsCrossFileAndSameFileUsages(@TempDir Path workspace) throws Exception {
        Path sourceDir = workspace.resolve("src/main/java/com/example");
        Files.createDirectories(sourceDir);
        writeMbtJson(workspace);

        Path greeterFile = sourceDir.resolve("Greeter.java");
        Files.writeString(greeterFile, """
                package com.example;

                public class Greeter {
                    private int count;

                    public String greet() {
                        count++;
                        return "hi";
                    }
                }
                """);

        Path mainFile = sourceDir.resolve("Main.java");
        Files.writeString(mainFile, """
                package com.example;

                public class Main {
                    void run() {
                        Greeter g = new Greeter();
                        g.greet();
                    }
                }
                """);

        try (LspDiagnosticsHarness harness = LspDiagnosticsHarness.start(workspace)) {
            harness.awaitIndexReady(TIMEOUT);
            harness.openAndAwaitDiagnostics(greeterFile, TIMEOUT);
            harness.openAndAwaitDiagnostics(mainFile, TIMEOUT);

            List<String> greeterLines = Files.readAllLines(greeterFile);
            int greetDeclLine = -1;
            for (int i = 0; i < greeterLines.size(); i++) {
                if (greeterLines.get(i).contains("public String greet()")) {
                    greetDeclLine = i;
                    break;
                }
            }
            assertTrue(greetDeclLine >= 0);
            int greetDeclCol = greeterLines.get(greetDeclLine).indexOf("greet");
            assertTrue(greetDeclCol >= 0);

            List<Location> greetRefs = harness.referencesAt(
                    greeterFile.toUri(), new Position(greetDeclLine, greetDeclCol), true);
            assertTrue(greetRefs.size() >= 2,
                    () -> "expected declaration + usage references, got: " + greetRefs);

            boolean hasMainUsage = greetRefs.stream()
                    .anyMatch(loc -> loc.getUri().contains("Main.java"));
            boolean hasGreeterDecl = greetRefs.stream()
                    .anyMatch(loc -> loc.getUri().contains("Greeter.java"));
            assertTrue(hasMainUsage, () -> "expected usage in Main.java, got: " + greetRefs);
            assertTrue(hasGreeterDecl, () -> "expected declaration in Greeter.java, got: " + greetRefs);

            List<String> mainLines = Files.readAllLines(mainFile);
            int greeterUseLine = -1;
            for (int i = 0; i < mainLines.size(); i++) {
                if (mainLines.get(i).contains("Greeter g = new Greeter()")) {
                    greeterUseLine = i;
                    break;
                }
            }
            assertTrue(greeterUseLine >= 0);
            int typeUseCol = mainLines.get(greeterUseLine).indexOf("Greeter");
            assertTrue(typeUseCol >= 0);

            List<Location> typeRefs = harness.referencesAt(
                    greeterFile.toUri(), new Position(2, "public class Greeter".indexOf("Greeter")),
                    false);
            assertTrue(typeRefs.size() >= 2,
                    () -> "expected type references in Main.java, got: " + typeRefs);
            assertTrue(typeRefs.stream().anyMatch(loc -> loc.getUri().contains("Main.java")));

            List<String> logs = harness.logMessages();
            assertTrue(logs.stream().anyMatch(m -> m != null && m.contains("bloom hits")),
                    () -> "expected bloom candidate logging, got: " + logs);
            assertTrue(logs.stream().anyMatch(m -> m != null && m.contains("resolved") && m.contains("references")),
                    () -> "expected timing logging, got: " + logs);
        }
    }

    @Test
    void referencesFindsBinaryTypeAcrossFiles(@TempDir Path workspace) throws Exception {
        Path sourceDir = workspace.resolve("src/main/java/com/example");
        Files.createDirectories(sourceDir);
        writeMbtJson(workspace);

        Path alphaFile = sourceDir.resolve("Alpha.java");
        Files.writeString(alphaFile, """
                package com.example;

                public class Alpha {
                    String name = "a";
                }
                """);

        Path betaFile = sourceDir.resolve("Beta.java");
        Files.writeString(betaFile, """
                package com.example;

                public class Beta {
                    String label = "b";
                }
                """);

        try (LspDiagnosticsHarness harness = LspDiagnosticsHarness.start(workspace)) {
            harness.awaitIndexReady(TIMEOUT);
            harness.openAndAwaitDiagnostics(alphaFile, TIMEOUT);
            harness.openAndAwaitDiagnostics(betaFile, TIMEOUT);

            List<String> alphaLines = Files.readAllLines(alphaFile);
            int stringDeclLine = -1;
            for (int i = 0; i < alphaLines.size(); i++) {
                if (alphaLines.get(i).contains("String name")) {
                    stringDeclLine = i;
                    break;
                }
            }
            assertTrue(stringDeclLine >= 0);
            int stringCol = alphaLines.get(stringDeclLine).indexOf("String");
            assertTrue(stringCol >= 0);

            List<Location> stringRefs = harness.referencesAt(
                    alphaFile.toUri(), new Position(stringDeclLine, stringCol), false);
            assertTrue(stringRefs.size() >= 2,
                    () -> "expected String references in Alpha.java and Beta.java, got: " + stringRefs);
            assertTrue(stringRefs.stream().anyMatch(loc -> loc.getUri().contains("Alpha.java")),
                    () -> "expected usage in Alpha.java, got: " + stringRefs);
            assertTrue(stringRefs.stream().anyMatch(loc -> loc.getUri().contains("Beta.java")),
                    () -> "expected usage in Beta.java, got: " + stringRefs);
        }
    }

    @Test
    void referencesRespectsCandidateCap(@TempDir Path workspace) throws Exception {
        Path sourceDir = workspace.resolve("src/main/java/com/example");
        Files.createDirectories(sourceDir);
        writeMbtJson(workspace);

        Path alphaFile = sourceDir.resolve("Alpha.java");
        Files.writeString(alphaFile, """
                package com.example;

                public class Alpha {
                    String name = "a";
                }
                """);

        Path betaFile = sourceDir.resolve("Beta.java");
        Files.writeString(betaFile, """
                package com.example;

                public class Beta {
                    String label = "b";
                }
                """);

        List<String> alphaLines = Files.readAllLines(alphaFile);
        int stringDeclLine = -1;
        for (int i = 0; i < alphaLines.size(); i++) {
            if (alphaLines.get(i).contains("String name")) {
                stringDeclLine = i;
                break;
            }
        }
        assertTrue(stringDeclLine >= 0);
        int stringCol = alphaLines.get(stringDeclLine).indexOf("String");
        assertTrue(stringCol >= 0);
        Position stringPosition = new Position(stringDeclLine, stringCol);

        try (LspDiagnosticsHarness cappedHarness = LspDiagnosticsHarness.start(
                workspace, TIMEOUT, Map.of("referencesCandidateCap", 1))) {
            cappedHarness.awaitIndexReady(TIMEOUT);
            cappedHarness.openAndAwaitDiagnostics(alphaFile, TIMEOUT);
            cappedHarness.openAndAwaitDiagnostics(betaFile, TIMEOUT);

            cappedHarness.referencesAt(alphaFile.toUri(), stringPosition, false);

            List<String> cappedLogs = cappedHarness.logMessages();
            assertTrue(cappedLogs.stream().anyMatch(m -> m != null && m.contains("capped ")),
                    () -> "expected capped candidate logging, got: " + cappedLogs);
        }

        try (LspDiagnosticsHarness uncappedHarness = LspDiagnosticsHarness.start(
                workspace, TIMEOUT, Map.of("referencesCandidateCap", 0))) {
            uncappedHarness.awaitIndexReady(TIMEOUT);
            uncappedHarness.openAndAwaitDiagnostics(alphaFile, TIMEOUT);
            uncappedHarness.openAndAwaitDiagnostics(betaFile, TIMEOUT);

            List<Location> stringRefs = uncappedHarness.referencesAt(
                    alphaFile.toUri(), stringPosition, false);
            assertTrue(stringRefs.size() >= 2,
                    () -> "expected String references with no cap, got: " + stringRefs);
            assertTrue(stringRefs.stream().anyMatch(loc -> loc.getUri().contains("Beta.java")),
                    () -> "expected usage in Beta.java with no cap, got: " + stringRefs);

            List<String> uncappedLogs = uncappedHarness.logMessages();
            assertFalse(uncappedLogs.stream().anyMatch(m -> m != null && m.contains("capped ")),
                    () -> "did not expect capped logging with cap=0, got: " + uncappedLogs);
        }
    }

    @Test
    void referencesFindsLocalVariableInSameFile(@TempDir Path workspace) throws Exception {
        Path sourceDir = workspace.resolve("src/main/java/com/example");
        Files.createDirectories(sourceDir);
        writeMbtJson(workspace);
        Path sourceFile = sourceDir.resolve("Locals.java");
        Files.writeString(sourceFile, """
                package com.example;

                public class Locals {
                    void run() {
                        int value = 1;
                        System.out.println(value);
                        value++;
                    }
                }
                """);

        try (LspDiagnosticsHarness harness = LspDiagnosticsHarness.start(workspace)) {
            harness.awaitIndexReady(TIMEOUT);
            harness.openAndAwaitDiagnostics(sourceFile, TIMEOUT);

            List<String> lines = Files.readAllLines(sourceFile);
            int declLine = -1;
            for (int i = 0; i < lines.size(); i++) {
                if (lines.get(i).contains("int value = 1")) {
                    declLine = i;
                    break;
                }
            }
            assertTrue(declLine >= 0);
            int declCol = lines.get(declLine).indexOf("value");
            assertTrue(declCol >= 0);

            List<Location> refs = harness.referencesAt(
                    sourceFile.toUri(), new Position(declLine, declCol), true);
            assertEquals(3, refs.size(), () -> "expected declaration + 2 usages, got: " + refs);
        }
    }

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
    void vertxJsonObjectOpenedBeforeIndexReadyRecompilesWithoutNpe() throws Exception {
        Path workspace = Path.of("../../test-projects/vert.x").toAbsolutePath().normalize();
        Path jsonObject = workspace.resolve("vertx-core/src/main/java/io/vertx/core/json/JsonObject.java");

        try (LspDiagnosticsHarness harness = LspDiagnosticsHarness.start(workspace)) {
            CompletableFuture<List<Diagnostic>> diagnosticsFuture = harness.openBeforeIndexReady(jsonObject);

            if (!harness.isIndexReady()) {
                assertFalse(harness.hasDiagnosticsFor(jsonObject),
                        "no diagnostics should publish before index is ready");
                assertFalse(diagnosticsFuture.isDone(),
                        "diagnostics future should not complete before index is ready");
            }

            harness.awaitIndexReady(Duration.ofSeconds(600));

            List<Diagnostic> diagnostics = diagnosticsFuture.get(120, TimeUnit.SECONDS);
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

            boolean npeInLogs = harness.logMessages().stream()
                    .anyMatch(m -> m != null && m.contains("NullPointerException"));
            assertFalse(npeInLogs, () -> "server logs should not contain NPE: " + harness.logMessages());
        }
    }

    @Test
    @EnabledIf("vertxWorkspacePresent")
    void vertxJsonObjectBase64EncoderImportHasNoErrors() throws Exception {
        Path workspace = Path.of("../../test-projects/vert.x").toAbsolutePath().normalize();
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
            List<Diagnostic> suppressWarningsErrors = errors.stream()
                    .filter(d -> d.getMessage() != null
                            && d.getMessage().contains("duplicate element")
                            && d.getMessage().contains("SuppressWarnings"))
                    .toList();
            assertTrue(suppressWarningsErrors.isEmpty(),
                    () -> "unexpected @SuppressWarnings errors: " + suppressWarningsErrors);
        }
    }

    @Test
    @EnabledIf("vertxWorkspacePresent")
    void vertxJsonObjectBase64EncoderImportNavigatesToDefinition() throws Exception {
        Path workspace = Path.of("../../test-projects/vert.x").toAbsolutePath().normalize();
        Path jsonObject = workspace.resolve("vertx-core/src/main/java/io/vertx/core/json/JsonObject.java");

        try (LspDiagnosticsHarness harness = LspDiagnosticsHarness.start(workspace)) {
            harness.awaitIndexReady(Duration.ofSeconds(600));
            harness.openAndAwaitDiagnostics(jsonObject, Duration.ofSeconds(120));

            List<String> lines = Files.readAllLines(jsonObject);
            int importLine = -1;
            for (int i = 0; i < lines.size(); i++) {
                if (lines.get(i).equals("import java.util.Base64.Encoder;")) {
                    importLine = i;
                    break;
                }
            }
            assertTrue(importLine >= 0, "expected Base64.Encoder import in JsonObject.java");

            String line = lines.get(importLine);
            int encoderCol = line.indexOf("Encoder");
            assertTrue(encoderCol >= 0);

            List<Location> encoderDefs = harness.definitionAt(
                    jsonObject.toUri(), new Position(importLine, encoderCol));
            assertFalse(encoderDefs.isEmpty(),
                    "go-to-definition on Encoder in import java.util.Base64.Encoder should resolve");

            int base64Col = line.indexOf("Base64");
            List<Location> base64Defs = harness.definitionAt(
                    jsonObject.toUri(), new Position(importLine, base64Col));
            assertFalse(base64Defs.isEmpty(),
                    "go-to-definition on Base64 in import java.util.Base64.Encoder should resolve");

            assertTrue(encoderDefs.get(0).getUri().contains("Base64"),
                    () -> "Encoder definition should target Base64 source, got: " + encoderDefs.get(0).getUri());
        }
    }

    @Test
    @EnabledIf("vertxWorkspacePresent")
    void vertxJsonObjectIsoInstantStaticImportNavigatesToDefinition() throws Exception {
        Path workspace = Path.of("../../test-projects/vert.x").toAbsolutePath().normalize();
        Path jsonObject = workspace.resolve("vertx-core/src/main/java/io/vertx/core/json/JsonObject.java");

        try (LspDiagnosticsHarness harness = LspDiagnosticsHarness.start(workspace)) {
            harness.awaitIndexReady(Duration.ofSeconds(600));
            harness.openAndAwaitDiagnostics(jsonObject, Duration.ofSeconds(120));

            List<String> lines = Files.readAllLines(jsonObject);
            int importLine = -1;
            for (int i = 0; i < lines.size(); i++) {
                if (lines.get(i).contains("import static java.time.format.DateTimeFormatter.ISO_INSTANT")) {
                    importLine = i;
                    break;
                }
            }
            assertTrue(importLine >= 0, "expected ISO_INSTANT static import in JsonObject.java");

            String line = lines.get(importLine);
            int isoInstantCol = line.indexOf("ISO_INSTANT");
            assertTrue(isoInstantCol >= 0);

            List<Location> isoInstantDefs = harness.definitionAt(
                    jsonObject.toUri(), new Position(importLine, isoInstantCol));
            assertFalse(isoInstantDefs.isEmpty(),
                    "go-to-definition on ISO_INSTANT in static import should resolve");

            int formatterCol = line.indexOf("DateTimeFormatter");
            List<Location> formatterDefs = harness.definitionAt(
                    jsonObject.toUri(), new Position(importLine, formatterCol));
            assertFalse(formatterDefs.isEmpty(),
                    "go-to-definition on DateTimeFormatter in static import should resolve");
        }
    }

    @Test
    @EnabledIf("vertxWorkspacePresent")
    void vertxStringReferencesDoNotCrash() throws Exception {
        Path workspace = Path.of("../../test-projects/vert.x").toAbsolutePath().normalize();
        Path jsonObject = workspace.resolve("vertx-core/src/main/java/io/vertx/core/json/JsonObject.java");

        try (LspDiagnosticsHarness harness = LspDiagnosticsHarness.start(workspace)) {
            harness.awaitIndexReady(Duration.ofSeconds(600));
            harness.openAndAwaitDiagnostics(jsonObject, Duration.ofSeconds(120));

            List<String> lines = Files.readAllLines(jsonObject);
            int stringLine = -1;
            int stringCol = -1;
            for (int i = 0; i < lines.size(); i++) {
                String line = lines.get(i);
                String trimmed = line.trim();
                if (!trimmed.startsWith("*") && !trimmed.startsWith("//") && !trimmed.startsWith("import")) {
                    int col = line.indexOf("String");
                    if (col >= 0) {
                        stringLine = i;
                        stringCol = col;
                        break;
                    }
                }
            }
            assertTrue(stringLine >= 0, "expected a 'String' usage in " + jsonObject);

            // find-references on String triggers compilation of ALL candidate files
            // in the workspace, including complex vert.x impl files that reference
            // missing transitive Netty dependencies.
            harness.referencesAt(jsonObject.toUri(), new Position(stringLine, stringCol), false);

            boolean npeInLogs = harness.logMessages().stream()
                    .anyMatch(m -> m != null && m.contains("NullPointerException"));
            assertFalse(npeInLogs,
                    () -> "find-references on String triggered NPE: " + harness.logMessages());
        }
    }

    static boolean vertxWorkspacePresent() {
        Path workspace = Path.of("../../test-projects/vert.x").toAbsolutePath().normalize();
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
