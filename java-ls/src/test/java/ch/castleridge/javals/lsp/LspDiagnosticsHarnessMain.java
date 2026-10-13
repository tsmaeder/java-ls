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

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import org.eclipse.lsp4j.Diagnostic;
import org.eclipse.lsp4j.DiagnosticSeverity;
import org.eclipse.lsp4j.Range;

/**
 * Thin CLI wrapper around {@link LspDiagnosticsHarness}.
 *
 * <p>Usage: {@code LspDiagnosticsHarnessMain <workspace-root> <file-or-uri> [<file-or-uri> ...]}
 *
 * <p>Each path argument may be a filesystem path or a URI ({@code file:},
 * {@code jar:}, …) including attached JDK sources such as
 * {@code jar:file:///…/lib/src.zip!/java.base/java/lang/Object.java}.
 */
public final class LspDiagnosticsHarnessMain {

    private static final Duration TIMEOUT = Duration.ofSeconds(600);
    private static final Duration DIAGNOSTICS_TIMEOUT = Duration.ofSeconds(120);

    private LspDiagnosticsHarnessMain() {}

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println(
                    "Usage: LspDiagnosticsHarnessMain <workspace-root> <file-or-uri> [<file-or-uri> ...]");
            System.exit(2);
        }

        Path workspaceRoot = Path.of(args[0]).toAbsolutePath().normalize();
        boolean hasErrors = false;

        try (LspDiagnosticsHarness harness =
                LspDiagnosticsHarness.start(workspaceRoot, TIMEOUT,
                        LspDiagnosticsHarness.backendOptionsFromSystemProperties())) {
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
                String target = resolveOpenTarget(args[i]);
                try {
                    List<Diagnostic> diagnostics =
                            harness.openAndAwaitDiagnostics(target, DIAGNOSTICS_TIMEOUT);
                    printDiagnostics(target, diagnostics);
                    if (diagnostics.stream().anyMatch(d -> d.getSeverity() == DiagnosticSeverity.Error)) {
                        hasErrors = true;
                    }
                } catch (Exception e) {
                    System.err.println(target + ": no diagnostics received (" + e + ")");
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
     * Filesystem path → absolute {@code file:} URI; otherwise keep as URI string
     * when it has a scheme or a jar entry separator.
     */
    static String resolveOpenTarget(String arg) {
        if (arg == null || arg.isBlank()) {
            throw new IllegalArgumentException("blank file-or-uri argument");
        }
        if (looksLikeUri(arg)) {
            return URI.create(arg).toString();
        }
        return Path.of(arg).toAbsolutePath().normalize().toUri().toString();
    }

    private static boolean looksLikeUri(String arg) {
        if (arg.contains("!/")) {
            return true;
        }
        int schemeEnd = arg.indexOf(':');
        if (schemeEnd <= 0) {
            return false;
        }
        // Windows drive letters are a single letter before ':'; URIs need a scheme.
        if (schemeEnd == 1) {
            return false;
        }
        String scheme = arg.substring(0, schemeEnd);
        for (int i = 0; i < scheme.length(); i++) {
            char c = scheme.charAt(i);
            if (!(c >= 'a' && c <= 'z')
                    && !(c >= 'A' && c <= 'Z')
                    && !(c >= '0' && c <= '9')
                    && c != '+' && c != '-' && c != '.') {
                return false;
            }
        }
        return true;
    }

    private static void printDiagnostics(String uri, List<Diagnostic> diagnostics) {
        System.err.println(uri + ": " + diagnostics.size() + " diagnostic(s)");
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
