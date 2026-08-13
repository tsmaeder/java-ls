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

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.eclipse.lsp4j.Diagnostic;
import org.eclipse.lsp4j.DiagnosticSeverity;
import org.eclipse.lsp4j.Range;

/**
 * Walks an entire workspace and collects error diagnostics by driving the
 * in-process {@link LspDiagnosticsHarness} exactly the way an editor would,
 * with NO batching:
 *
 * <ol>
 *   <li>start the server and wait for indexing to finish,</li>
 *   <li>for every {@code .java} file: open it, wait for diagnostics, then
 *       close it again,</li>
 *   <li>keep the single server instance running across all files.</li>
 * </ol>
 *
 * <p>Error diagnostics are coalesced: identical problems (same diagnostic
 * code + normalized message, ignoring the per-usage {@code location:} line)
 * are merged and counted. A JSON report is written so the results can drive
 * one planning task per distinct problem.
 *
 * <p>Usage:
 * {@code LspWorkspaceDiagnosticsMain <workspace-root> <report.json> [<file-list-out.txt>]}
 */
public final class LspWorkspaceDiagnosticsMain {

    private static final Duration INDEX_TIMEOUT = Duration.ofSeconds(900);
    private static final Duration DIAGNOSTICS_TIMEOUT = Duration.ofSeconds(120);

    private LspWorkspaceDiagnosticsMain() {}

    /** A coalesced group of identical error diagnostics. */
    private static final class Problem {
        final String code;
        final String normalizedMessage;
        String representativeMessage;
        int count;
        final List<Occurrence> occurrences = new ArrayList<>();

        Problem(String code, String normalizedMessage, String representativeMessage) {
            this.code = code;
            this.normalizedMessage = normalizedMessage;
            this.representativeMessage = representativeMessage;
        }
    }

    private record Occurrence(String file, int line, int character) {}

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("Usage: LspWorkspaceDiagnosticsMain <workspace-root> <report.json> [<file-list-out.txt>]");
            System.exit(2);
        }

        Path workspaceRoot = Path.of(args[0]).toAbsolutePath().normalize();
        Path reportPath = Path.of(args[1]).toAbsolutePath().normalize();
        Path fileListOut = args.length >= 3 ? Path.of(args[2]).toAbsolutePath().normalize() : null;

        List<Path> javaFiles = collectJavaFiles(workspaceRoot);
        // Optional path-substring filter (system property) to restrict the run
        // to a subset of files for fast iteration/diagnosis without changing the
        // index. Example: -Ddiag.filter=jacksonv3
        String filter = System.getProperty("diag.filter");
        if (filter != null && !filter.isEmpty()) {
            javaFiles = javaFiles.stream()
                    .filter(p -> p.toString().replace('\\', '/').contains(filter))
                    .toList();
            System.err.println("Filter '" + filter + "' -> " + javaFiles.size() + " file(s)");
        }
        System.err.println("Discovered " + javaFiles.size() + " .java files under " + workspaceRoot);
        if (fileListOut != null) {
            Files.write(fileListOut, javaFiles.stream().map(Path::toString).toList());
        }

        // Insertion-ordered so the report lists problems in first-seen order
        // before we re-sort by frequency at the end.
        Map<String, Problem> problems = new LinkedHashMap<>();
        int filesWithErrors = 0;
        int totalErrorDiagnostics = 0;
        int filesProcessed = 0;
        int filesFailed = 0;

        try (LspDiagnosticsHarness harness = LspDiagnosticsHarness.start(workspaceRoot, Duration.ofSeconds(120))) {
            System.err.println("Server started; waiting for indexing to finish...");
            harness.awaitIndexReady(INDEX_TIMEOUT);
            System.err.println("Index ready. Opening files one at a time.");

            for (Path file : javaFiles) {
                filesProcessed++;
                String relative = relativize(workspaceRoot, file);
                List<Diagnostic> diagnostics;
                try {
                    diagnostics = harness.openAndAwaitDiagnostics(file, DIAGNOSTICS_TIMEOUT);
                } catch (Exception e) {
                    filesFailed++;
                    System.err.println("[" + filesProcessed + "/" + javaFiles.size()
                            + "] FAILED " + relative + " : " + e);
                    if (Boolean.getBoolean("diag.dumpOnTimeout")) {
                        dumpAllThreads();
                    }
                    // Record the failure to receive diagnostics as its own problem
                    // so it is not silently lost.
                    recordProblem(problems,
                            "harness-no-diagnostics",
                            "Harness received no diagnostics for file (" + e.getClass().getSimpleName() + ")",
                            relative, 0, 0);
                    safeClose(harness, file);
                    continue;
                }

                List<Diagnostic> errors = diagnostics.stream()
                        .filter(d -> d.getSeverity() == DiagnosticSeverity.Error)
                        .toList();

                if (!errors.isEmpty()) {
                    filesWithErrors++;
                    totalErrorDiagnostics += errors.size();
                    for (Diagnostic d : errors) {
                        String code = codeOf(d);
                        String message = d.getMessage() == null ? "" : d.getMessage();
                        Range range = d.getRange();
                        int line = range != null && range.getStart() != null ? range.getStart().getLine() + 1 : 0;
                        int col = range != null && range.getStart() != null ? range.getStart().getCharacter() + 1 : 0;
                        recordProblemFull(problems, code, message, relative, line, col);
                    }
                }

                System.err.println("[" + filesProcessed + "/" + javaFiles.size() + "] "
                        + relative + " : " + errors.size() + " error(s), "
                        + diagnostics.size() + " total diagnostic(s)");

                safeClose(harness, file);
            }
        }

        List<Problem> sorted = new ArrayList<>(problems.values());
        sorted.sort(Comparator.comparingInt((Problem p) -> p.count).reversed()
                .thenComparing(p -> p.normalizedMessage));

        writeReport(reportPath, workspaceRoot, javaFiles.size(), filesProcessed, filesFailed,
                filesWithErrors, totalErrorDiagnostics, sorted);

        System.err.println();
        System.err.println("==== SUMMARY ====");
        System.err.println("Files processed : " + filesProcessed + " / " + javaFiles.size());
        System.err.println("Files that failed to produce diagnostics: " + filesFailed);
        System.err.println("Files with >=1 error : " + filesWithErrors);
        System.err.println("Total error diagnostics : " + totalErrorDiagnostics);
        System.err.println("Distinct coalesced problems : " + sorted.size());
        System.err.println("Report written to : " + reportPath);
        System.err.println();
        int shown = Math.min(sorted.size(), 60);
        for (int i = 0; i < shown; i++) {
            Problem p = sorted.get(i);
            System.err.println((i + 1) + ". x" + p.count + " [" + p.code + "] "
                    + firstLine(p.representativeMessage));
        }

        System.exit(0);
    }

    private static void dumpAllThreads() {
        System.err.println("==== THREAD DUMP (on timeout) ====");
        Map<Thread, StackTraceElement[]> all = Thread.getAllStackTraces();
        for (Map.Entry<Thread, StackTraceElement[]> e : all.entrySet()) {
            Thread t = e.getKey();
            System.err.println("\"" + t.getName() + "\" state=" + t.getState());
            for (StackTraceElement f : e.getValue()) {
                System.err.println("    at " + f);
            }
            System.err.println();
        }
        System.err.println("==== END THREAD DUMP ====");
    }

    private static void safeClose(LspDiagnosticsHarness harness, Path file) {
        try {
            harness.closeDocument(file);
        } catch (RuntimeException e) {
            System.err.println("  (close failed for " + file + ": " + e + ")");
        }
    }

    /**
     * Directory names that hold build output or tooling caches rather than
     * real workspace sources. {@code .metals/out} in particular contains
     * JDK/library sources that metals extracted for navigation; compiling them
     * re-declares types that the index already provides (e.g. a second
     * {@code java.lang.Object}), which is a self-shadowing artifact unrelated
     * to workspace diagnostics — so we never want them in the measurement.
     */
    private static final java.util.Set<String> EXCLUDED_DIRS =
            java.util.Set.of(".metals", ".bloop", "target", "build", "out", "bin",
                    "node_modules", ".git", ".gradle", ".idea");

    private static List<Path> collectJavaFiles(Path workspaceRoot) throws IOException {
        try (Stream<Path> stream = Files.walk(workspaceRoot)) {
            return stream
                    .filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".java"))
                    .filter(p -> !isUnderExcludedDir(workspaceRoot, p))
                    .sorted()
                    .toList();
        }
    }

    private static boolean isUnderExcludedDir(Path workspaceRoot, Path file) {
        Path relative = workspaceRoot.relativize(file);
        for (Path segment : relative) {
            if (EXCLUDED_DIRS.contains(segment.toString())) {
                return true;
            }
        }
        return false;
    }

    private static void recordProblemFull(Map<String, Problem> problems, String code, String message,
                                          String file, int line, int col) {
        String normalized = normalizeMessage(message);
        String key = code + "\u0001" + normalized;
        Problem p = problems.computeIfAbsent(key, k -> new Problem(code, normalized, message));
        p.count++;
        p.occurrences.add(new Occurrence(file, line, col));
    }

    private static void recordProblem(Map<String, Problem> problems, String code, String message,
                                      String file, int line, int col) {
        recordProblemFull(problems, code, message, file, line, col);
    }

    /**
     * Collapse a javac message into a stable grouping key: drop the
     * per-usage {@code location:} line (which names the enclosing class and
     * therefore differs across otherwise-identical errors) and collapse
     * whitespace.
     */
    private static String normalizeMessage(String message) {
        if (message == null) return "";
        StringBuilder sb = new StringBuilder();
        for (String rawLine : message.split("\\R")) {
            String line = rawLine.trim();
            if (line.startsWith("location:")) {
                continue;
            }
            line = line.replaceAll("\\s+", " ");
            if (!line.isEmpty()) {
                if (sb.length() > 0) sb.append(" | ");
                sb.append(line);
            }
        }
        return sb.toString();
    }

    private static String codeOf(Diagnostic d) {
        if (d.getCode() == null) return "";
        if (d.getCode().isLeft()) return d.getCode().getLeft();
        return String.valueOf(d.getCode().getRight());
    }

    private static String relativize(Path root, Path file) {
        try {
            return root.relativize(file).toString().replace('\\', '/');
        } catch (IllegalArgumentException e) {
            return file.toString().replace('\\', '/');
        }
    }

    private static String firstLine(String message) {
        if (message == null) return "";
        int nl = message.indexOf('\n');
        String line = nl >= 0 ? message.substring(0, nl) : message;
        return line.replaceAll("\\s+", " ").trim();
    }

    private static void writeReport(Path reportPath, Path workspaceRoot, int totalFiles,
                                    int filesProcessed, int filesFailed, int filesWithErrors,
                                    int totalErrorDiagnostics, List<Problem> sorted) throws IOException {
        if (reportPath.getParent() != null) {
            Files.createDirectories(reportPath.getParent());
        }
        try (PrintWriter w = new PrintWriter(Files.newBufferedWriter(reportPath, StandardCharsets.UTF_8))) {
            w.println("{");
            w.println("  \"workspaceRoot\": " + jsonString(workspaceRoot.toString()) + ",");
            w.println("  \"totalJavaFiles\": " + totalFiles + ",");
            w.println("  \"filesProcessed\": " + filesProcessed + ",");
            w.println("  \"filesFailed\": " + filesFailed + ",");
            w.println("  \"filesWithErrors\": " + filesWithErrors + ",");
            w.println("  \"totalErrorDiagnostics\": " + totalErrorDiagnostics + ",");
            w.println("  \"distinctProblems\": " + sorted.size() + ",");
            w.println("  \"problems\": [");
            for (int i = 0; i < sorted.size(); i++) {
                Problem p = sorted.get(i);
                w.println("    {");
                w.println("      \"rank\": " + (i + 1) + ",");
                w.println("      \"count\": " + p.count + ",");
                w.println("      \"code\": " + jsonString(p.code) + ",");
                w.println("      \"message\": " + jsonString(p.representativeMessage) + ",");
                w.println("      \"normalizedMessage\": " + jsonString(p.normalizedMessage) + ",");
                long distinctFiles = p.occurrences.stream().map(Occurrence::file).distinct().count();
                w.println("      \"distinctFiles\": " + distinctFiles + ",");
                w.println("      \"occurrences\": [");
                int cap = Math.min(p.occurrences.size(), 25);
                for (int j = 0; j < cap; j++) {
                    Occurrence o = p.occurrences.get(j);
                    String sep = j < cap - 1 ? "," : "";
                    w.println("        {\"file\": " + jsonString(o.file()) + ", \"line\": " + o.line()
                            + ", \"character\": " + o.character() + "}" + sep);
                }
                w.println("      ]" + (p.occurrences.size() > cap ? "" : ""));
                w.println("    }" + (i < sorted.size() - 1 ? "," : ""));
            }
            w.println("  ]");
            w.println("}");
        }
    }

    private static String jsonString(String s) {
        if (s == null) return "null";
        StringBuilder sb = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append('"');
        return sb.toString();
    }
}
