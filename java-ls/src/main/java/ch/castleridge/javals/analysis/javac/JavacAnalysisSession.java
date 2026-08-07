package ch.castleridge.javals.analysis.javac;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import javax.lang.model.element.Element;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;
import javax.tools.Diagnostic;
import javax.tools.JavaFileObject;

import org.eclipse.lsp4j.CompletionItem;
import org.eclipse.lsp4j.DiagnosticSeverity;
import org.eclipse.lsp4j.Location;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;

import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.LineMap;
import com.sun.source.util.TreePath;
import com.sun.source.util.Trees;

import ch.castleridge.javals.analysis.AnalysisSession;
import ch.castleridge.javals.analysis.PublishedDiagnostic;
import ch.castleridge.javals.analysis.ResolvedSymbol;
import ch.castleridge.javals.analysis.SymbolIdentity;
import ch.castleridge.javals.classpath.ClasspathOrder;
import ch.castleridge.javals.indexing.index.Index;

/**
 * javac-backed {@link AnalysisSession} wrapping a {@link JavacWorkspaceCompiler.Result}.
 */
public final class JavacAnalysisSession implements AnalysisSession {

    private final JavacWorkspaceCompiler.Result result;
    private final String docUri;
    private final SymbolLocator symbolLocator;
    private final Map<String, String> sourceJarByBinaryJar;

    public JavacAnalysisSession(JavacWorkspaceCompiler.Result result,
                                String docUri,
                                SymbolLocator symbolLocator,
                                Map<String, String> sourceJarByBinaryJar) {
        this.result = result;
        this.docUri = docUri;
        this.symbolLocator = symbolLocator;
        this.sourceJarByBinaryJar = sourceJarByBinaryJar;
    }

    JavacWorkspaceCompiler.Result result() {
        return result;
    }

    @Override
    public boolean isUsable() {
        return result != null && result.cu() != null && result.trees() != null && result.task() != null;
    }

    @Override
    public List<PublishedDiagnostic> diagnostics() {
        if (result == null || result.diagnostics() == null) {
            return List.of();
        }
        LineMap lineMap = result.cu() != null ? result.cu().getLineMap() : null;
        JavaFileObject compiledSource = result.source();
        List<PublishedDiagnostic> out = new ArrayList<>();
        for (Diagnostic<? extends JavaFileObject> d : result.diagnostics()) {
            if (compiledSource != null && d.getSource() != null && d.getSource() != compiledSource) {
                continue;
            }
            Range range = rangeOf(lineMap, d);
            String code = d.getCode();
            out.add(new PublishedDiagnostic(
                    range,
                    d.getMessage(Locale.ROOT),
                    severityOf(d.getKind()),
                    "javac",
                    code == null || code.isEmpty() ? null : code));
        }
        return out;
    }

    @Override
    public Optional<ResolvedSymbol> resolveAt(Position position) {
        if (!isUsable()) return Optional.empty();
        long offset = LspPositions.offsetAt(result.cu().getLineMap(), position);
        if (offset < 0) return Optional.empty();

        Trees trees = result.trees();
        TreePath path = TreePathLocator.findAt(trees, result.cu(), offset);
        if (path == null) return Optional.empty();

        Element element = DefinitionElementResolver.resolve(trees, path);
        if (element == null) return Optional.empty();

        Elements elements = result.task().getElements();
        Types types = result.task().getTypes();
        Optional<SymbolKey> keyOpt = SymbolKey.of(element, elements, types, trees);
        if (keyOpt.isEmpty()) return Optional.empty();

        SymbolIdentity identity = keyOpt.get().toIdentity();
        Optional<Location> definition = symbolLocator.locate(
                element, trees, result.cu(), docUri, sourceJarByBinaryJar);
        return Optional.of(new JavacResolvedSymbol(identity, definition, element));
    }

    @Override
    public List<CompletionItem> complete(CharSequence source, Position position, Index index, ClasspathOrder classpath) {
        if (!isUsable()) return List.of();
        long offset = LspPositions.offsetAt(result.cu().getLineMap(), position);
        if (offset < 0) return List.of();
        return CompletionProposer.propose(result, source == null ? "" : source.toString(), offset, index, classpath);
    }

    @Override
    public List<Location> referencesInUnit(ResolvedSymbol symbol) {
        if (!isUsable() || !(symbol instanceof JavacResolvedSymbol jrs)) {
            return List.of();
        }
        return findWithKey(jrs.identity(), jrs.element());
    }

    @Override
    public List<Location> findReferencesTo(SymbolIdentity identity) {
        if (!isUsable() || identity == null || identity.fileLocal()) {
            return List.of();
        }
        return findWithKey(identity, null);
    }

    @Override
    public Optional<Location> definitionOf(ResolvedSymbol symbol) {
        if (symbol == null) return Optional.empty();
        if (symbol.definition().isPresent()) return symbol.definition();
        if (!(symbol instanceof JavacResolvedSymbol jrs) || !isUsable()) {
            return Optional.empty();
        }
        return symbolLocator.locate(
                jrs.element(), result.trees(), result.cu(), docUri, sourceJarByBinaryJar);
    }

    private List<Location> findWithKey(SymbolIdentity identity, Element targetElement) {
        SymbolKey key = SymbolKey.fromIdentity(identity);
        Set<Location> found = ReferenceFinder.findReferences(
                result.cu(),
                result.trees(),
                result.task().getElements(),
                result.task().getTypes(),
                docUri,
                key,
                targetElement);
        return new ArrayList<>(found);
    }

    private static Range rangeOf(LineMap lineMap, Diagnostic<? extends JavaFileObject> d) {
        if (lineMap == null || d.getPosition() == Diagnostic.NOPOS) {
            Position p = new Position(0, 0);
            return new Range(p, p);
        }
        long start = d.getStartPosition();
        long end = d.getEndPosition();
        if (start == Diagnostic.NOPOS) start = d.getPosition();
        if (end == Diagnostic.NOPOS || end < start) end = start + 1;
        return new Range(LspPositions.positionAt(lineMap, start), LspPositions.positionAt(lineMap, end));
    }

    private static DiagnosticSeverity severityOf(Diagnostic.Kind kind) {
        return switch (kind) {
            case ERROR -> DiagnosticSeverity.Error;
            case WARNING, MANDATORY_WARNING -> DiagnosticSeverity.Warning;
            case NOTE -> DiagnosticSeverity.Information;
            default -> DiagnosticSeverity.Hint;
        };
    }

    /** javac-specific resolved symbol carrying the underlying {@link Element}. */
    public record JavacResolvedSymbol(
            SymbolIdentity identity,
            Optional<Location> definition,
            Element element) implements ResolvedSymbol {
    }
}
