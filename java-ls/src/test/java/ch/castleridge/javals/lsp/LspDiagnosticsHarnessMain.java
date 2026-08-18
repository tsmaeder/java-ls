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
package ch.castleridge.javals.lsp;

import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.lsp4j.Diagnostic;
import org.eclipse.lsp4j.DiagnosticSeverity;
import org.eclipse.lsp4j.Range;

/**
 * Thin CLI wrapper around {@link LspDiagnosticsHarness}.
 *
 * <p>Usage: {@code LspDiagnosticsHarnessMain <workspace-root> <file> [<file> ...]}
 */
public final class LspDiagnosticsHarnessMain {

    private static final Duration TIMEOUT = Duration.ofSeconds(600);
    private static final Duration DIAGNOSTICS_TIMEOUT = Duration.ofSeconds(120);

    private LspDiagnosticsHarnessMain() {}

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("Usage: LspDiagnosticsHarnessMain <workspace-root> <file> [<file> ...]");
            System.exit(2);
        }

        Path workspaceRoot = Path.of(args[0]).toAbsolutePath().normalize();
        boolean hasErrors = false;

        try (LspDiagnosticsHarness harness =
                LspDiagnosticsHarness.start(workspaceRoot, TIMEOUT, backendOptions())) {
            try {
                harness.awaitIndexReady(TIMEOUT);
            } catch (Exception e) {
                System.err.println("awaitIndexReady failed: " + e);
                System.err.println("--- server log messages ---");
                harness.logMessages().forEach(m -> System.err.println("  " + m));
                throw e;
            }
            System.err.println("--- index ready; server log messages ---");
            harness.logMessages().forEach(m -> System.err.println("  " + m));

            int logsAlreadyPrinted = harness.logMessages().size();
            for (int i = 1; i < args.length; i++) {
                Path file = Path.of(args[i]).toAbsolutePath().normalize();
                try {
                    List<Diagnostic> diagnostics = harness.openAndAwaitDiagnostics(file, DIAGNOSTICS_TIMEOUT);
                    printDiagnostics(file, diagnostics);
                    if (diagnostics.stream().anyMatch(d -> d.getSeverity() == DiagnosticSeverity.Error)) {
                        hasErrors = true;
                    }
                } catch (Exception e) {
                    System.err.println(file + ": no diagnostics received (" + e + ")");
                    hasErrors = true;
                } finally {
                    List<String> logs = harness.logMessages();
                    System.err.println("--- server log messages (compile phase) ---");
                    for (int j = logsAlreadyPrinted; j < logs.size(); j++) {
                        System.err.println("  " + logs.get(j));
                    }
                    logsAlreadyPrinted = logs.size();
                }
            }
        }

        System.exit(hasErrors ? 1 : 0);
    }

    /**
     * {@code -Dbackend.sourceIndexer=ecj -Dbackend.classIndexer=turbine -Dbackend.compiler=ecj}
     * select backend implementations.
     */
    private static Map<String, Object> backendOptions() {
        Map<String, Object> backend = new HashMap<>();
        String sourceIndexer = System.getProperty("backend.sourceIndexer");
        String classIndexer = System.getProperty("backend.classIndexer");
        String compiler = System.getProperty("backend.compiler");
        if (sourceIndexer != null) backend.put("sourceIndexer", sourceIndexer);
        if (classIndexer != null) backend.put("classIndexer", classIndexer);
        if (compiler != null) backend.put("compiler", compiler);
        return backend.isEmpty() ? Map.of() : Map.of("backend", backend);
    }

    private static void printDiagnostics(Path file, List<Diagnostic> diagnostics) {
        System.err.println(file + ": " + diagnostics.size() + " diagnostic(s)");
        for (Diagnostic d : diagnostics) {
            String severity = severityLabel(d.getSeverity());
            Range range = d.getRange();
            String location = range == null
                    ? "?:?"
                    : (range.getStart().getLine() + 1) + ":" + (range.getStart().getCharacter() + 1);
            String code = d.getCode() == null ? "" : " [" + d.getCode() + "]";
            System.err.println("  " + severity + " " + location + code + ": " + d.getMessage());
        }
    }

    private static String severityLabel(DiagnosticSeverity severity) {
        if (severity == null) {
            return "hint";
        }
        return switch (severity) {
            case Error -> "error";
            case Warning -> "warning";
            case Information -> "info";
            case Hint -> "hint";
        };
    }
}
