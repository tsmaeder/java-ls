/**
 * Copyright 2026 by Castle Ridge Software GmbH
 */
package ch.castleridge.javals.mbtimport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BuildSystemImporterTest {

    @TempDir
    Path temp;

    @Test
    void runsJarPreferenceWithJavaJar() throws Exception {
        Path workspace = temp.resolve("ws");
        Files.createDirectories(workspace);
        Path fakeJar = temp.resolve("mavenimporter.jar");
        Files.writeString(fakeJar, "fake");

        AtomicInteger runs = new AtomicInteger();
        AtomicReference<List<String>> seen = new AtomicReference<>();
        BuildSystemImporter importer = new BuildSystemImporter(
                Map.of(BuildSystems.MAVEN, fakeJar.toString()),
                (command, log) -> {
                    runs.incrementAndGet();
                    seen.set(List.copyOf(command));
                    assertTrue(command.contains("-jar"));
                    assertTrue(command.contains(fakeJar.toString()));
                    Path output = Path.of(command.get(command.indexOf("--output") + 1));
                    Files.createDirectories(output.getParent());
                    Files.writeString(output, """
                            {
                              "namespaces": { "x:y:1": { "sources": ["/s"], "javacOptions": ["-g"] } },
                              "dependencyModules": []
                            }
                            """);
                    return 0;
                });

        importer.importAndMerge(workspace, (t, m) -> {});

        assertEquals(1, runs.get());
        assertTrue(Files.isRegularFile(BuildSystems.mergedPath(workspace)));
        assertTrue(Files.readString(BuildSystems.mergedPath(workspace)).contains("javacOptions"));
        assertTrue(ImporterCommand.isJar(fakeJar.toString()));
        assertEquals(ImporterCommand.javaExecutable(), seen.get().get(0));
    }

    @Test
    void runsNonJarPreferenceViaShell() throws Exception {
        Path workspace = temp.resolve("ws");
        Files.createDirectories(workspace);
        String script = temp.resolve("import.sh").toString();

        AtomicReference<List<String>> seen = new AtomicReference<>();
        BuildSystemImporter importer = new BuildSystemImporter(
                Map.of(BuildSystems.MAVEN, script),
                (command, log) -> {
                    seen.set(List.copyOf(command));
                    Path output = BuildSystems.fragmentPath(workspace, BuildSystems.MAVEN);
                    Files.createDirectories(output.getParent());
                    Files.writeString(output, """
                            {"namespaces":{},"dependencyModules":[]}
                            """);
                    return 0;
                });

        importer.importAndMerge(workspace, (t, m) -> {});

        List<String> command = seen.get();
        assertFalse(command.contains("-jar"));
        boolean windows = System.getProperty("os.name", "").toLowerCase().contains("win");
        if (windows) {
            assertEquals("cmd.exe", command.get(0));
            assertTrue(command.get(command.size() - 1).contains(script));
        } else {
            assertEquals("/bin/sh", command.get(0));
            assertEquals("-c", command.get(1));
            assertTrue(command.get(2).startsWith(script));
        }
    }

    @Test
    void resolveScriptsOverlaysPreferencesOnDefaults() {
        Map<String, String> resolved = BuildSystemImporter.resolveScripts(
                Map.of(BuildSystems.MAVEN, "/custom/importer.jar"));
        assertEquals("/custom/importer.jar", resolved.get(BuildSystems.MAVEN));
    }

    @Test
    void writesGeneratedSourceRulesAndPassesFlag() throws Exception {
        Path workspace = temp.resolve("ws");
        Files.createDirectories(workspace);
        Path fakeJar = temp.resolve("mavenimporter.jar");
        Files.writeString(fakeJar, "fake");

        AtomicReference<List<String>> seen = new AtomicReference<>();
        String rulesJson = "[{\"pluginKey\":\"org.example:x\",\"enabled\":false}]";
        BuildSystemImporter importer = new BuildSystemImporter(
                Map.of(BuildSystems.MAVEN, fakeJar.toString()),
                rulesJson,
                (command, log) -> {
                    seen.set(List.copyOf(command));
                    Path output = Path.of(command.get(command.indexOf("--output") + 1));
                    Files.createDirectories(output.getParent());
                    Files.writeString(output, """
                            {"namespaces":{},"dependencyModules":[]}
                            """);
                    return 0;
                });

        importer.importAndMerge(workspace, (t, m) -> {});

        Path rulesFile = workspace.resolve(".javals").resolve(BuildSystemImporter.GENERATED_SOURCE_RULES_FILE);
        assertTrue(Files.isRegularFile(rulesFile));
        assertEquals(rulesJson, Files.readString(rulesFile));
        List<String> command = seen.get();
        assertTrue(command.contains("--generated-source-rules"));
        assertEquals(
                rulesFile.toAbsolutePath().normalize().toString(),
                command.get(command.indexOf("--generated-source-rules") + 1));
    }
}
