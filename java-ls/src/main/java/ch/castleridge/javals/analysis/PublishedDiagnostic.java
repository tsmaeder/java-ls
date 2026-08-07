package ch.castleridge.javals.analysis;

import org.eclipse.lsp4j.DiagnosticSeverity;
import org.eclipse.lsp4j.Range;

/**
 * Compiler-neutral diagnostic for publishing to the LSP client.
 */
public record PublishedDiagnostic(
        Range range,
        String message,
        DiagnosticSeverity severity,
        String source,
        String code) {
}
