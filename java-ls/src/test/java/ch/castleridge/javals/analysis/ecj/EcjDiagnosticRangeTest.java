/**
 * Copyright 2026 by Castle Ridge Software GmbH
 *
 * Author: Thomas Mäder, Castle Ridge Software
 *
 */
package ch.castleridge.javals.analysis.ecj;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.eclipse.lsp4j.Range;
import org.junit.jupiter.api.Test;

import ch.castleridge.javals.analysis.AnalysisSession;
import ch.castleridge.javals.analysis.PublishedDiagnostic;
import ch.castleridge.javals.classpath.ClasspathOrder;
import ch.castleridge.javals.classpath.UriClasspathEntry;
import ch.castleridge.javals.indexing.index.InMemoryIndex;
import ch.castleridge.javals.indexing.source.ecj.EcjSourceIndexer;

/**
 * Diagnostic ranges come from {@code DefaultProblem}'s line and column, which
 * ECJ computes from its own line-separator table.
 */
class EcjDiagnosticRangeTest {
    private static final String CLASSPATH_URI = "index:///diag-range/";

    @Test
    void unresolvedTypeCoversTheName() {
        String source = "class Use {\n    NotAType field;\n}\n";
        assertEquals("NotAType", covered(source, unresolved(source)));
    }

    @Test
    void unresolvedTypeCoversTheNameWhenLinesEndWithCrLf() {
        String source = "class Use {\r\n    NotAType field;\r\n}\r\n";
        assertEquals("NotAType", covered(source, unresolved(source)));
    }

    @Test
    void unterminatedCommentCrossesTheLineEnding() {
        String source = "class Use {\n    /* open\n    still\n}\n";
        AnalysisSession session = analyze(source);
        PublishedDiagnostic diagnostic = session.diagnostics().stream()
                .filter(d -> d.message().toLowerCase().contains("comment"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no comment diagnostic in " + session.diagnostics()));
        String text = covered(source, diagnostic.range());
        assertTrue(text.contains("\n"), () -> "expected the range to cross a line ending, got [" + text + "] " + diagnostic);
    }

    private static Range unresolved(String source) {
        AnalysisSession session = analyze(source);
        return session.diagnostics().stream()
                .filter(d -> d.message().contains("NotAType"))
                .map(PublishedDiagnostic::range)
                .findFirst()
                .orElseThrow(() -> new AssertionError("no NotAType diagnostic in " + session.diagnostics()));
    }

    private static AnalysisSession analyze(String source) {
        InMemoryIndex index = new InMemoryIndex();
        EcjSourceIndexer.index("java/lang/Object.java", CLASSPATH_URI, """
                package java.lang;
                public class Object {
                    public Object() {}
                }
                """, index);
        EcjSourceIndexer.index("java/lang/String.java", CLASSPATH_URI, """
                package java.lang;
                public final class String {
                    public String() {}
                }
                """, index);
        ClasspathOrder classpath = new ClasspathOrder(List.of(UriClasspathEntry.of(CLASSPATH_URI)), false);
        return new EcjWorkspaceCompiler().analyze(
                "file:///workspace/Use.java", source, index, classpath);
    }

    private static String covered(String source, Range range) {
        int start = EcjAnalysisEngine.offsetAt(source, range.getStart());
        int end = EcjAnalysisEngine.offsetAt(source, range.getEnd());
        assertTrue(start >= 0 && end >= start, () -> "range " + range + " is outside the source");
        return source.substring(start, end);
    }
}
