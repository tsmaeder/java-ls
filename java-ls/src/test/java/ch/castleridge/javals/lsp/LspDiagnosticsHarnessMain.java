package ch.castleridge.javals.lsp;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import org.eclipse.lsp4j.Diagnostic;
import org.eclipse.lsp4j.DiagnosticSeverity;
import org.eclipse.lsp4j.Range;

/**
 * Thin CLI wrapper around {@link LspDiagnosticsHarness}.
 *
 * <p>Usage: {@code LspDiagnosticsHarnessMain <workspace-root> <file> [<file> ...]}
 */
public final class LspDiagnosticsHarnessMain {

    private static final Duration TIMEOUT = Duration.ofSeconds(120);

    private LspDiagnosticsHarnessMain() {}

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("Usage: LspDiagnosticsHarnessMain <workspace-root> <file> [<file> ...]");
            System.exit(2);
        }

        Path workspaceRoot = Path.of(args[0]).toAbsolutePath().normalize();
        boolean hasErrors = false;

        try (LspDiagnosticsHarness harness = LspDiagnosticsHarness.start(workspaceRoot)) {
            harness.awaitIndexReady(TIMEOUT);

            for (int i = 1; i < args.length; i++) {
                Path file = Path.of(args[i]).toAbsolutePath().normalize();
                List<Diagnostic> diagnostics = harness.openAndAwaitDiagnostics(file, TIMEOUT);
                printDiagnostics(file, diagnostics);
                if (diagnostics.stream().anyMatch(d -> d.getSeverity() == DiagnosticSeverity.Error)) {
                    hasErrors = true;
                }
            }
        }

        System.exit(hasErrors ? 1 : 0);
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
