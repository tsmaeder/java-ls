/**
 * Copyright 2026 by Castle Ridge Software GmbH
 *
 * Author: Thomas Mäder, Castle Ridge Software
 *
 */
package ch.castleridge.javals.mbtimport;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BiConsumer;

import org.eclipse.lsp4j.MessageType;

/**
 * Runs registered build-system importers for a workspace directory.
 *
 * <p>Each build system has an importer <em>script</em> preference (jar path or
 * shell command). Missing entries use {@link #defaultScripts()}.
 */
public final class BuildSystemImporter implements WorkspaceBuildImport {

    /** Written under {@code .javals/} when {@code maven.generatedSourceRules} is non-empty. */
    public static final String GENERATED_SOURCE_RULES_FILE = "generated-source-rules.json";

    @FunctionalInterface
    public interface ProcessRunner {
        /**
         * Runs {@code command} and returns the process exit code. Implementations should
         * stream combined stdout/stderr via {@code log}.
         */
        int run(List<String> command, BiConsumer<MessageType, String> log) throws IOException, InterruptedException;
    }

    private final Map<String, String> scripts;
    private final String mavenGeneratedSourceRulesJson;
    private final ProcessRunner processRunner;

    public BuildSystemImporter(Map<String, String> scripts) {
        this(scripts, null, BuildSystemImporter::runProcess);
    }

    public BuildSystemImporter(Map<String, String> scripts, String mavenGeneratedSourceRulesJson) {
        this(scripts, mavenGeneratedSourceRulesJson, BuildSystemImporter::runProcess);
    }

    public BuildSystemImporter(Map<String, String> scripts, ProcessRunner processRunner) {
        this(scripts, null, processRunner);
    }

    public BuildSystemImporter(
            Map<String, String> scripts, String mavenGeneratedSourceRulesJson, ProcessRunner processRunner) {
        this.scripts = Map.copyOf(scripts);
        this.mavenGeneratedSourceRulesJson =
                mavenGeneratedSourceRulesJson == null || mavenGeneratedSourceRulesJson.isBlank()
                        ? null
                        : mavenGeneratedSourceRulesJson;
        this.processRunner = Objects.requireNonNull(processRunner);
    }

    /**
     * Defaults: {@code maven} → bundled {@code mavenimporter.jar} when found beside java-ls.
     */
    public static Map<String, String> defaultScripts() {
        Map<String, String> defaults = new HashMap<>();
        ImporterJarLocator.locateMavenImporter(ch.castleridge.javals.App.class)
                .ifPresent(jar -> defaults.put(BuildSystems.MAVEN, jar.toString()));
        return defaults;
    }

    /**
     * Merges user preferences over {@link #defaultScripts()}. Blank values are ignored
     * (default kept). Explicit blank-after-merge removal is not supported; omit the key
     * to keep the default.
     */
    public static Map<String, String> resolveScripts(Map<String, String> preferences) {
        Map<String, String> resolved = new HashMap<>(defaultScripts());
        if (preferences != null) {
            for (Map.Entry<String, String> e : preferences.entrySet()) {
                if (e.getKey() == null || e.getValue() == null || e.getValue().isBlank()) {
                    continue;
                }
                resolved.put(e.getKey(), e.getValue().trim());
            }
        }
        return resolved;
    }

    @Override
    public void importAndMerge(Path workspace, BiConsumer<MessageType, String> log) {
        Path ws = workspace.toAbsolutePath().normalize();
        for (String system : BuildSystems.all()) {
            runOne(system, ws, log);
        }
        try {
            MbtFragmentMerge.mergeIfNeeded(ws, BuildSystems.all(), log);
        } catch (IOException e) {
            log.accept(MessageType.Error, "Failed to merge mbt fragments: " + e.getMessage());
        }
    }

    private void runOne(String system, Path workspace, BiConsumer<MessageType, String> log) {
        String script = scripts.get(system);
        if (script == null || script.isBlank()) {
            log.accept(MessageType.Warning, "No importer script for build system '" + system + "'; skipping");
            return;
        }

        Path output = BuildSystems.fragmentPath(workspace, system);
        Path rulesFile = null;
        if (BuildSystems.MAVEN.equals(system)) {
            Optional<Path> written = writeMavenGeneratedSourceRules(workspace, log);
            rulesFile = written.orElse(null);
        }
        List<String> command = ImporterCommand.build(script, workspace, output, rulesFile);
        log.accept(MessageType.Info, "Running " + system + " importer: " + String.join(" ", command));
        try {
            int code = processRunner.run(command, log);
            if (code != 0) {
                log.accept(MessageType.Error, system + " importer exited with code " + code);
            }
        } catch (IOException e) {
            log.accept(MessageType.Error, "Failed to run " + system + " importer: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.accept(MessageType.Error, system + " importer interrupted");
        }
    }

    private Optional<Path> writeMavenGeneratedSourceRules(Path workspace, BiConsumer<MessageType, String> log) {
        if (mavenGeneratedSourceRulesJson == null) {
            return Optional.empty();
        }
        Path rulesFile = workspace.resolve(".javals").resolve(GENERATED_SOURCE_RULES_FILE);
        try {
            if (Files.isRegularFile(rulesFile)
                    && mavenGeneratedSourceRulesJson.equals(Files.readString(rulesFile, StandardCharsets.UTF_8))) {
                return Optional.of(rulesFile);
            }
            Files.createDirectories(rulesFile.getParent());
            Files.writeString(rulesFile, mavenGeneratedSourceRulesJson, StandardCharsets.UTF_8);
            return Optional.of(rulesFile);
        } catch (IOException e) {
            log.accept(MessageType.Error, "Failed to write generated-source-rules: " + e.getMessage());
            return Optional.empty();
        }
    }

    static int runProcess(List<String> command, BiConsumer<MessageType, String> log)
            throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectErrorStream(true);
        Process process = pb.start();
        try (BufferedReader reader =
                new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                log.accept(MessageType.Log, line);
            }
        }
        return process.waitFor();
    }
}
