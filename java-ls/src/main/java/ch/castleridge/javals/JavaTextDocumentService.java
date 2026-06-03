package ch.castleridge.javals;

import org.eclipse.lsp4j.*;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.eclipse.lsp4j.jsonrpc.messages.Either3;
import org.eclipse.lsp4j.services.LanguageClient;
import org.eclipse.lsp4j.services.TextDocumentService;

import java.net.URI;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

import javax.lang.model.element.Element;
import javax.tools.JavaFileObject;

import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.LineMap;
import com.sun.source.util.TreePath;
import com.sun.source.util.Trees;

import ch.castleridge.javals.indexing.index.Index;
import ch.castleridge.javals.indexing.index.UriCoding;
import ch.castleridge.javals.javac.ClasspathOrder;
import ch.castleridge.javals.javac.SourceCache;
import ch.castleridge.javals.javac.SymbolLocator;
import ch.castleridge.javals.javac.TreePathLocator;
import ch.castleridge.javals.javac.WorkspaceCompiler;

/**
 * Text Document Service implementation handling document operations
 * Copyright Anysphere Inc.
 */
public class JavaTextDocumentService implements TextDocumentService {

    private final JavaLanguageServer server;
    private final Map<String, TextDocumentItem> documents = new ConcurrentHashMap<>();
    private final Map<String, CachedCompile> compileCache = new ConcurrentHashMap<>();
    private final SourceCache sourceCache = new SourceCache();
    private final SymbolLocator symbolLocator = new SymbolLocator(sourceCache);

    public JavaTextDocumentService(JavaLanguageServer server) {
        this.server = server;
    }

    private record CachedCompile(int version, WorkspaceCompiler.Result result) {}

    @Override
    public void didOpen(DidOpenTextDocumentParams params) {
        TextDocumentItem doc = params.getTextDocument();
        String uri = UriCoding.decode(doc.getUri());
        documents.put(uri, doc);
        server.logMessage(MessageType.Info, "Document opened: " + uri);
        refreshCompile(uri);
    }

    @Override
    public void didChange(DidChangeTextDocumentParams params) {
        String uri = UriCoding.decode(params.getTextDocument().getUri());
        List<TextDocumentContentChangeEvent> changes = params.getContentChanges();

        if (!changes.isEmpty()) {
            // For full sync, just take the last change which contains the full text
            TextDocumentContentChangeEvent change = changes.get(changes.size() - 1);
            TextDocumentItem doc = documents.get(uri);
            if (doc != null) {
                Integer paramVersion = params.getTextDocument().getVersion();
                int newVersion = paramVersion != null ? paramVersion : doc.getVersion() + 1;
                documents.put(uri, new TextDocumentItem(uri, doc.getLanguageId(),
                    newVersion, change.getText()));
            }
        }
        server.logMessage(MessageType.Log, "Document changed: " + uri);
        refreshCompile(uri);
    }

    @Override
    public void didClose(DidCloseTextDocumentParams params) {
        String uri = UriCoding.decode(params.getTextDocument().getUri());
        documents.remove(uri);
        compileCache.remove(uri);
        publishEmptyDiagnostics(uri);
        server.logMessage(MessageType.Info, "Document closed: " + uri);
    }

    /**
     * Recompile {@code uri} in the background and refresh
     * {@link #compileCache} + publish diagnostics for it. Guarded against
     * stale writes: if the document has been edited again while we were
     * compiling, drop the result on the floor - a fresher refresh is
     * already (or will be) in flight.
     */
    private void refreshCompile(String uri) {
        TextDocumentItem doc = documents.get(uri);
        if (doc == null) return;
        int versionAtStart = doc.getVersion();
        String text = doc.getText();

        CompletableFuture.runAsync(() -> {
            IndexService indexService = server.getIndexService();
            Index index = indexService.index().orElse(null);
            ClasspathOrder classpath = indexService.classPathFor(uri);

            URI docUri;
            try {
                docUri = URI.create(uri);
            } catch (IllegalArgumentException e) {
                return;
            }

            WorkspaceCompiler.Result result;
                long t0 = System.currentTimeMillis();
                try {
                result = WorkspaceCompiler.compile(docUri, text, index, classpath);
                    long t1 = System.currentTimeMillis();
                    server.logMessage(MessageType.Log,
                            "Refresh compile took " + (t1 - t0) + "ms for " + uri);
                } catch (RuntimeException e) {
                    server.logException(e);
                    return;
                }

            TextDocumentItem latest = documents.get(uri);
            if (latest == null || latest.getVersion() != versionAtStart) {
                // Superseded by a newer edit (or document closed) - drop.
                return;
            }
            compileCache.put(uri, new CachedCompile(versionAtStart, result));
            publishDiagnostics(uri, result.cu(), result.source(), result.diagnostics());
        });
    }

    @Override
    public void didSave(DidSaveTextDocumentParams params) {
        server.logMessage(MessageType.Info, "Document saved: " + UriCoding.decode(params.getTextDocument().getUri()));
    }

    @Override
    public CompletableFuture<Either<List<CompletionItem>, CompletionList>> completion(CompletionParams params) {
        // Basic completion example
        List<CompletionItem> completions = new ArrayList<>();
        
        CompletionItem item1 = new CompletionItem("public");
        item1.setKind(CompletionItemKind.Keyword);
        item1.setDetail("Java keyword");
        completions.add(item1);
        
        CompletionItem item2 = new CompletionItem("private");
        item2.setKind(CompletionItemKind.Keyword);
        item2.setDetail("Java keyword");
        completions.add(item2);
        
        CompletionItem item3 = new CompletionItem("class");
        item3.setKind(CompletionItemKind.Keyword);
        item3.setDetail("Java keyword");
        completions.add(item3);

        return CompletableFuture.completedFuture(Either.forLeft(completions));
    }

    @Override
    public CompletableFuture<CompletionItem> resolveCompletionItem(CompletionItem item) {
        // Add additional information to completion item if needed
        return CompletableFuture.completedFuture(item);
    }

    @Override
    public CompletableFuture<Hover> hover(HoverParams params) {
        String uri = UriCoding.decode(params.getTextDocument().getUri());
        TextDocumentItem doc = documents.get(uri);
        
        if (doc != null) {
            MarkupContent content = new MarkupContent();
            content.setKind(MarkupKind.MARKDOWN);
            content.setValue("**Java Language Server**\n\nHover information at position: " + 
                params.getPosition().getLine() + ":" + params.getPosition().getCharacter());
            
            Hover hover = new Hover(content);
            return CompletableFuture.completedFuture(hover);
        }
        
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletableFuture<SignatureHelp> signatureHelp(SignatureHelpParams params) {
        SignatureHelp help = new SignatureHelp();
        help.setSignatures(new ArrayList<>());
        help.setActiveSignature(0);
        help.setActiveParameter(0);
        return CompletableFuture.completedFuture(help);
    }

    /**
     * Resolve "go to definition" by recompiling the open document under
     * the workspace's {@link Index} and {@link ClasspathOrder}, then
     * mapping the resolved javac {@link Element} back to a source range
     * via {@link SymbolLocator}.
     *
     * <p>If the workspace index has not finished loading yet, the call
     * still succeeds for symbols whose declaration lives in the open
     * document but cannot resolve cross-file references.
     *
     * <p>See {@link SymbolLocator} for the resolution algorithm and its
     * caveats around overload disambiguation, bytecode-only dependencies
     * and {@code jar:} / {@code jrt:} URIs in the returned
     * {@link Location}.
     */
    @Override
    public CompletableFuture<Either<List<? extends Location>, List<? extends LocationLink>>> definition(DefinitionParams params) {
        String uri = UriCoding.decode(params.getTextDocument().getUri());
        Position position = params.getPosition();
        return CompletableFuture.supplyAsync(() -> Either.forLeft(computeDefinition(uri, position)));
    }

    private List<Location> computeDefinition(String uri, Position position) {
        TextDocumentItem doc = documents.get(uri);
        if (doc == null) return List.of();

        IndexService indexService = server.getIndexService();

        WorkspaceCompiler.Result compiled = compileCache.get(uri).result();

        if (compiled == null) {
            return List.of();
        }

        CompilationUnitTree cu = compiled.cu();
        if (cu == null) {
            refreshCompile(uri); // might not be ready yet
            return List.of();
        }

        long offset = positionToOffset(cu.getLineMap(), position);
        if (offset < 0) return List.of();

        Trees trees = compiled.trees();
        TreePath path = TreePathLocator.findAt(trees, cu, offset);
        if (path == null) return List.of();

        Element element = elementForPath(trees, path);
        if (element == null) return List.of();

        return symbolLocator.locate(element, trees, cu, uri, indexService.sourceJarByBinaryJar())
                .map(List::of)
                .orElse(List.of());
    }

    private static long positionToOffset(LineMap lineMap, Position position) {
        if (lineMap == null) return -1;
        try {
            return lineMap.getPosition(position.getLine() + 1, position.getCharacter() + 1);
        } catch (IndexOutOfBoundsException | IllegalArgumentException e) {
            return -1;
        }
    }

    /**
     * Resolve {@code path}'s leaf to an {@link Element}, walking up the
     * path if the leaf itself isn't bound (e.g. punctuation tokens that
     * sit inside a parent {@code MemberSelectTree}).
     */
    private static Element elementForPath(Trees trees, TreePath path) {
        TreePath cur = path;
        while (cur != null) {
            Element e = trees.getElement(cur);
            if (e != null) return e;
            cur = cur.getParentPath();
        }
        return null;
    }

    @Override
    public CompletableFuture<List<? extends Location>> references(ReferenceParams params) {
        return CompletableFuture.completedFuture(new ArrayList<>());
    }

    @Override
    public CompletableFuture<List<Either<SymbolInformation, DocumentSymbol>>> documentSymbol(DocumentSymbolParams params) {
        return CompletableFuture.completedFuture(new ArrayList<>());
    }

    @Override
    public CompletableFuture<List<Either<Command, CodeAction>>> codeAction(CodeActionParams params) {
        return CompletableFuture.completedFuture(new ArrayList<>());
    }

    @Override
    public CompletableFuture<List<? extends TextEdit>> formatting(DocumentFormattingParams params) {
        return CompletableFuture.completedFuture(new ArrayList<>());
    }

    @Override
    public CompletableFuture<List<? extends TextEdit>> rangeFormatting(DocumentRangeFormattingParams params) {
        return CompletableFuture.completedFuture(new ArrayList<>());
    }

    @Override
    public CompletableFuture<Either3<Range, PrepareRenameResult, PrepareRenameDefaultBehavior>> prepareRename(PrepareRenameParams params) {
        Range range = new Range(params.getPosition(), params.getPosition());
        PrepareRenameResult result = new PrepareRenameResult(range, "placeholder");
        return CompletableFuture.completedFuture(Either3.forSecond(result));
    }

    @Override
    public CompletableFuture<WorkspaceEdit> rename(RenameParams params) {
        WorkspaceEdit edit = new WorkspaceEdit();
        edit.setChanges(new HashMap<>());
        return CompletableFuture.completedFuture(edit);
    }

    /**
     * Translate javac diagnostics into LSP diagnostics and push them to
     * the client. Diagnostics whose source isn't {@code compiledSource}
     * are dropped - they belong to indexed classpath files that the
     * client has no buffer for.
     */
    private void publishDiagnostics(String uri,
                                    CompilationUnitTree cu,
                                    JavaFileObject compiledSource,
                                    List<javax.tools.Diagnostic<? extends JavaFileObject>> diags) {
        LanguageClient client = server.getClient();
        if (client == null) return;

        LineMap lineMap = cu != null ? cu.getLineMap() : null;
        List<Diagnostic> out = new ArrayList<>();
        for (javax.tools.Diagnostic<? extends JavaFileObject> d : diags) {
            if (compiledSource != null && d.getSource() != null && d.getSource() != compiledSource) {
                continue;
            }
            Range range = rangeOf(lineMap, d);
            Diagnostic lsp = new Diagnostic(range, d.getMessage(Locale.ROOT));
            lsp.setSeverity(severityOf(d.getKind()));
            lsp.setSource("javac");
            String code = d.getCode();
            if (code != null && !code.isEmpty()) {
                lsp.setCode(code);
            }
            out.add(lsp);
        }

        PublishDiagnosticsParams params = new PublishDiagnosticsParams(uri, out);
        client.publishDiagnostics(params);
    }

    private void publishEmptyDiagnostics(String uri) {
        LanguageClient client = server.getClient();
        if (client == null) return;
        client.publishDiagnostics(new PublishDiagnosticsParams(uri, new ArrayList<>()));
    }

    private static Range rangeOf(LineMap lineMap, javax.tools.Diagnostic<?> d) {
        long start = clampPos(d.getStartPosition());
        long end = clampPos(d.getEndPosition());
        if (end < start) end = start;

        if (lineMap != null) {
            try {
                Position s = positionAt(lineMap, start);
                Position e = positionAt(lineMap, end);
                return new Range(s, e);
            } catch (IndexOutOfBoundsException | IllegalArgumentException ignored) {
                // fall through to line/column fallback below
            }
        }

        // Fallback: javac reports 1-based line/column; LSP wants 0-based.
        long line = d.getLineNumber();
        long col = d.getColumnNumber();
        int lspLine = line > 0 ? (int) (line - 1) : 0;
        int lspCol = col > 0 ? (int) (col - 1) : 0;
        Position p = new Position(lspLine, lspCol);
        return new Range(p, p);
    }

    private static long clampPos(long pos) {
        return pos < 0 ? 0 : pos;
    }

    private static Position positionAt(LineMap lineMap, long offset) {
        long line = lineMap.getLineNumber(offset);
        long col = lineMap.getColumnNumber(offset);
        int lspLine = line > 0 ? (int) (line - 1) : 0;
        int lspCol = col > 0 ? (int) (col - 1) : 0;
        return new Position(lspLine, lspCol);
    }

    private static DiagnosticSeverity severityOf(javax.tools.Diagnostic.Kind kind) {
        if (kind == null) return DiagnosticSeverity.Hint;
        switch (kind) {
            case ERROR: return DiagnosticSeverity.Error;
            case WARNING:
            case MANDATORY_WARNING: return DiagnosticSeverity.Warning;
            case NOTE: return DiagnosticSeverity.Information;
            case OTHER:
            default: return DiagnosticSeverity.Hint;
        }
    }
}
