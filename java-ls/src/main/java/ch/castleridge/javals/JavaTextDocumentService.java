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
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import javax.lang.model.element.Element;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;
import javax.tools.JavaFileObject;

import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.LineMap;
import com.sun.source.util.TreePath;
import com.sun.source.util.Trees;

import ch.castleridge.javals.indexing.bloom.IdentifierBloomFilter;
import ch.castleridge.javals.indexing.index.Index;
import ch.castleridge.javals.indexing.index.UriCoding;
import ch.castleridge.javals.javac.ClasspathOrder;
import ch.castleridge.javals.javac.CompletionProposer;
import ch.castleridge.javals.javac.DefinitionElementResolver;
import ch.castleridge.javals.javac.ReferenceFinder;
import ch.castleridge.javals.javac.SourceCache;
import ch.castleridge.javals.javac.SymbolKey;
import ch.castleridge.javals.javac.SymbolLocator;
import ch.castleridge.javals.javac.TreePathLocator;
import ch.castleridge.javals.javac.WorkspaceCompiler;

/**
 * Text Document Service implementation handling document operations
 * Copyright Anysphere Inc.
 */
public class JavaTextDocumentService implements TextDocumentService {

    private final JavaLanguageServer server;
    private final IndexService indexService;
    private final Map<String, TextDocumentItem> documents = new ConcurrentHashMap<>();
    private final Map<String, CachedCompile> compileCache = new ConcurrentHashMap<>();
    private final SourceCache sourceCache = new SourceCache();
    private final SymbolLocator symbolLocator = new SymbolLocator(sourceCache);
    /** Max files to scan for cross-file references; {@code <= 0} means no cap. */
    private volatile int referencesCandidateCap;
    private final ScheduledExecutorService refreshScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "index-refresh-debounce");
        t.setDaemon(true);
        return t;
    });
    private final AtomicReference<ScheduledFuture<?>> pendingRefresh = new AtomicReference<>();

    public JavaTextDocumentService(JavaLanguageServer server, IndexService indexService) {
        this.server = server;
        this.indexService = indexService;
        indexService.addIndexChangedListener(this::scheduleRefreshOpenDocuments);
    }

    public void setReferencesCandidateCap(int referencesCandidateCap) {
        this.referencesCandidateCap = referencesCandidateCap;
    }

    int referencesCandidateCap() {
        return referencesCandidateCap;
    }

    private record CachedCompile(int version, WorkspaceCompiler.Result result) {
    }

    private record ResolvedElement(WorkspaceCompiler.Result compiled, Element element) {
    }

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
    /**
     * Recompile every open document. Called when the workspace index
     * changes so diagnostics reflect the latest classpath.
     */
    public void refreshOpenDocuments() {
        for (String uri : documents.keySet()) {
            refreshCompile(uri);
        }
    }

    private void scheduleRefreshOpenDocuments() {
        if (pendingRefresh.get() != null)
            return;
        ScheduledFuture<?> f = refreshScheduler.schedule(() -> {
            pendingRefresh.set(null);
            refreshOpenDocuments();
        }, 1, TimeUnit.SECONDS);
        if (!pendingRefresh.compareAndSet(null, f)) {
            f.cancel(false);
        }
    }

    private void refreshCompile(String uri) {
        TextDocumentItem doc = documents.get(uri);
        if (doc == null)
            return;
        int versionAtStart = doc.getVersion();
        String text = doc.getText();

        CompletableFuture.runAsync(() -> {
            Optional<Index> indexOpt = indexService.index();
            if (indexOpt.isEmpty()) {
                return;
            }
            Index index = indexOpt.get();
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
            } catch (RuntimeException | Error e) {
                // javac signals fatal internal failures with Error subtypes
                // (com.sun.tools.javac.util.Abort, AssertionError) and deep
                // resolution cycles surface as StackOverflowError - none of
                // which are RuntimeExceptions. Catching only RuntimeException
                // let those escape the async task, which then died silently:
                // no diagnostics were ever published and the failure was
                // invisible. Catch Throwable-but-rethrow-nothing so every
                // compile failure becomes a visible compiler-internal-error.
                server.logMessage(MessageType.Error,
                        "Compile failed for " + uri + ": " + describe(e));
                server.logException(e);
                TextDocumentItem latestOnError = documents.get(uri);
                if (latestOnError == null || latestOnError.getVersion() != versionAtStart) {
                    // Superseded by a newer edit (or document closed) - drop.
                    return;
                }
                publishCompilerErrorDiagnostic(uri, e);
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

    /**
     * Compute completion candidates for types, fields and methods at the
     * cursor. Reuses the cached compile from the last {@link #refreshCompile}
     * (same as {@link #resolveElementAt}, no version check) so completion
     * stays cheap; falls back to a synchronous compile of the current
     * buffer when nothing is cached yet (e.g. right after {@code didOpen}).
     */
    @Override
    public CompletableFuture<Either<List<CompletionItem>, CompletionList>> completion(CompletionParams params) {
        String uri = UriCoding.decode(params.getTextDocument().getUri());
        Position position = params.getPosition();
        return CompletableFuture.supplyAsync(() -> Either.forLeft(computeCompletion(uri, position)));
    }

    private List<CompletionItem> computeCompletion(String uri, Position position) {
        TextDocumentItem doc = documents.get(uri);
        if (doc == null)
            return List.of();

        WorkspaceCompiler.Result compiled = compiledForCompletion(uri, doc);
        if (compiled == null || compiled.cu() == null)
            return List.of();

        long offset = positionToOffset(compiled.cu().getLineMap(), position);
        if (offset < 0)
            return List.of();

        Index index = indexService.index().orElse(null);
        ClasspathOrder classpath = indexService.classPathFor(uri);
        return CompletionProposer.propose(compiled, doc.getText(), offset, index, classpath);
    }

    private WorkspaceCompiler.Result compiledForCompletion(String uri, TextDocumentItem doc) {
        CachedCompile cached = compileCache.get(uri);
        if (cached != null && cached.result() != null) {
            return cached.result();
        }

        Optional<Index> indexOpt = indexService.index();
        if (indexOpt.isEmpty())
            return null;

        URI docUri;
        try {
            docUri = URI.create(uri);
        } catch (IllegalArgumentException e) {
            return null;
        }

        ClasspathOrder classpath = indexService.classPathFor(uri);
        try {
            return WorkspaceCompiler.compile(docUri, doc.getText(), indexOpt.get(), classpath);
        } catch (RuntimeException | Error e) {
            server.logMessage(MessageType.Error, "Completion compile failed for " + uri + ": " + describe(e));
            server.logException(e);
            return null;
        }
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
     * <p>
     * If the workspace index has not finished loading yet, the call
     * still succeeds for symbols whose declaration lives in the open
     * document but cannot resolve cross-file references.
     *
     * <p>
     * See {@link SymbolLocator} for the resolution algorithm and its
     * caveats around overload disambiguation, bytecode-only dependencies
     * and {@code jar:} / {@code jrt:} URIs in the returned
     * {@link Location}.
     */
    @Override
    public CompletableFuture<Either<List<? extends Location>, List<? extends LocationLink>>> definition(
            DefinitionParams params) {
        String uri = UriCoding.decode(params.getTextDocument().getUri());
        Position position = params.getPosition();
        return CompletableFuture.supplyAsync(() -> Either.forLeft(computeDefinition(uri, position)));
    }

    private List<Location> computeDefinition(String uri, Position position) {
        Optional<ResolvedElement> resolved = resolveElementAt(uri, position);
        if (resolved.isEmpty())
            return List.of();

        WorkspaceCompiler.Result compiled = resolved.get().compiled();
        Element element = resolved.get().element();
        CompilationUnitTree cu = compiled.cu();
        Trees trees = compiled.trees();

        return symbolLocator.locate(element, trees, cu, uri, indexService.sourceJarByBinaryJar())
                .map(List::of)
                .orElse(List.of());
    }

    private Optional<ResolvedElement> resolveElementAt(String uri, Position position) {
        TextDocumentItem doc = documents.get(uri);
        if (doc == null)
            return Optional.empty();

        CachedCompile cached = compileCache.get(uri);
        if (cached == null)
            return Optional.empty();
        WorkspaceCompiler.Result compiled = cached.result();
        if (compiled == null)
            return Optional.empty();

        CompilationUnitTree cu = compiled.cu();
        if (cu == null) {
            refreshCompile(uri);
            return Optional.empty();
        }

        long offset = positionToOffset(cu.getLineMap(), position);
        if (offset < 0)
            return Optional.empty();

        Trees trees = compiled.trees();
        TreePath path = TreePathLocator.findAt(trees, cu, offset);
        if (path == null)
            return Optional.empty();

        Element element = DefinitionElementResolver.resolve(trees, path);
        if (element == null)
            return Optional.empty();

        return Optional.of(new ResolvedElement(compiled, element));
    }

    private static long positionToOffset(LineMap lineMap, Position position) {
        if (lineMap == null)
            return -1;
        try {
            return lineMap.getPosition(position.getLine() + 1, position.getCharacter() + 1);
        } catch (IndexOutOfBoundsException | IllegalArgumentException e) {
            return -1;
        }
    }

    @Override
    public CompletableFuture<List<? extends Location>> references(ReferenceParams params) {
        String uri = UriCoding.decode(params.getTextDocument().getUri());
        Position position = params.getPosition();
        boolean includeDeclaration = params.getContext() != null
                && Boolean.TRUE.equals(params.getContext().isIncludeDeclaration());
        return CompletableFuture.supplyAsync(() -> computeReferences(uri, position, includeDeclaration));
    }

    private List<Location> computeReferences(String uri, Position position, boolean includeDeclaration) {
        Optional<ResolvedElement> resolved = resolveElementAt(uri, position);
        if (resolved.isEmpty())
            return List.of();

        WorkspaceCompiler.Result compiled = resolved.get().compiled();
        Element element = resolved.get().element();
        CompilationUnitTree cu = compiled.cu();
        Trees trees = compiled.trees();

        Elements elements = compiled.task().getElements();
        Types types = compiled.task().getTypes();
        Optional<SymbolKey> targetKeyOpt = SymbolKey.of(element, elements, types, trees);
        if (targetKeyOpt.isEmpty())
            return List.of();
        SymbolKey targetKey = targetKeyOpt.get();

        Optional<String> originUri = SymbolKey.originResourceUri(element, trees);

        if (targetKey.fileLocal()) {
            Set<Location> locations = ReferenceFinder.findReferences(
                    cu, trees, elements, types, uri, targetKey, element);
            return finalizeReferences(locations, includeDeclaration, element, trees, cu, uri);
        }

        Set<String> bloomCandidates = new LinkedHashSet<>();
        Optional<Index> indexOpt = indexService.index();
        if (indexOpt.isPresent()) {
            String simpleName = targetKey.simpleName();
            for (Map.Entry<String, IdentifierBloomFilter> entry : indexOpt.get().bloomFilters().entrySet()) {
                if (entry.getValue().mightContain(simpleName)) {
                    bloomCandidates.add(entry.getKey());
                }
            }
        }
        int bloomHits = bloomCandidates.size();
        int openDocs = documents.size();

        Set<String> candidates = new LinkedHashSet<>(bloomCandidates);
        candidates.addAll(documents.keySet());
        originUri.filter(u -> u.endsWith(".java")).ifPresent(candidates::add);

        int totalBeforeCap = candidates.size();
        String capNote = "";
        if (referencesCandidateCap > 0 && candidates.size() > referencesCandidateCap) {
            Set<String> capped = new LinkedHashSet<>(documents.keySet());
            originUri.filter(u -> u.endsWith(".java")).ifPresent(capped::add);
            for (String candidateUri : bloomCandidates) {
                if (capped.size() >= referencesCandidateCap) {
                    break;
                }
                capped.add(candidateUri);
            }
            candidates = capped;
            capNote = ", capped " + candidates.size() + "/" + totalBeforeCap;
        }

        server.logMessage(MessageType.Log,
                "References: '" + targetKey.simpleName() + "' -> " + candidates.size()
                        + " candidates (" + bloomHits + " bloom hits, " + openDocs + " open docs"
                        + capNote
                        + originUri.map(u -> ", origin " + u).orElse("") + ")");

        long t0 = System.nanoTime();
        Set<Location> locations = Collections.synchronizedSet(new LinkedHashSet<>());
        candidates.parallelStream().forEach(candidateUri -> {
            String text = textForUri(candidateUri);
            if (text == null)
                return;

            URI docUri;
            try {
                docUri = URI.create(candidateUri);
            } catch (IllegalArgumentException e) {
                return;
            }

            Optional<Index> index = indexService.index();
            if (index.isEmpty())
                return;
            ClasspathOrder classpath = indexService.classPathFor(candidateUri);

            WorkspaceCompiler.Result result;
            try {
                result = WorkspaceCompiler.compile(docUri, text, index.get(), classpath);
            } catch (RuntimeException e) {
                server.logMessage(MessageType.Error,
                        "Error compiling candidate " + candidateUri + ": " + e.getMessage());
                server.logException(e);
                return;
            }

            CompilationUnitTree candidateCu = result.cu();
            if (candidateCu == null)
                return;

            Trees candidateTrees = result.trees();
            Elements candidateElements = result.task().getElements();
            Types candidateTypes = result.task().getTypes();
            locations.addAll(ReferenceFinder.findReferences(
                    candidateCu,
                    candidateTrees,
                    candidateElements,
                    candidateTypes,
                    candidateUri,
                    targetKey,
                    element));
        });

        long elapsedMs = (System.nanoTime() - t0) / 1_000_000L;
        server.logMessage(MessageType.Log,
                "References: resolved " + locations.size() + " references across "
                        + candidates.size() + " files in " + elapsedMs + " ms");

        return finalizeReferences(locations, includeDeclaration, element, trees, cu, uri);
    }

    private List<Location> finalizeReferences(Set<Location> locations,
            boolean includeDeclaration,
            Element element,
            Trees trees,
            CompilationUnitTree cu,
            String uri) {
        Optional<Location> declaration = symbolLocator.locate(
                element, trees, cu, uri, indexService.sourceJarByBinaryJar());
        if (includeDeclaration) {
            declaration.ifPresent(locations::add);
        } else {
            declaration.ifPresent(decl -> locations.removeIf(loc -> locationsEqual(loc, decl)));
        }
        return new ArrayList<>(locations);
    }

    private String textForUri(String uri) {
        TextDocumentItem doc = documents.get(uri);
        if (doc != null)
            return doc.getText();
        return SourceCache.readText(uri);
    }

    private static boolean locationsEqual(Location a, Location b) {
        if (a == null || b == null)
            return false;
        if (!Objects.equals(a.getUri(), b.getUri()))
            return false;
        Range ra = a.getRange();
        Range rb = b.getRange();
        if (ra == null || rb == null)
            return ra == rb;
        return Objects.equals(ra.getStart(), rb.getStart())
                && Objects.equals(ra.getEnd(), rb.getEnd());
    }

    @Override
    public CompletableFuture<List<Either<SymbolInformation, DocumentSymbol>>> documentSymbol(
            DocumentSymbolParams params) {
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
    public CompletableFuture<Either3<Range, PrepareRenameResult, PrepareRenameDefaultBehavior>> prepareRename(
            PrepareRenameParams params) {
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
        if (client == null)
            return;

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

    /**
     * Publish a single error diagnostic representing a crash in the
     * workspace compiler itself (as opposed to a javac diagnostic about the
     * user's source). Without this, a {@link RuntimeException} during
     * {@link WorkspaceCompiler#compile} would leave the file with no
     * diagnostics at all, making the failure invisible to the client.
     */
    private void publishCompilerErrorDiagnostic(String uri, Throwable error) {
        LanguageClient client = server.getClient();
        if (client == null)
            return;

        Position start = new Position(0, 0);
        Position end = new Position(0, 1);
        Diagnostic lsp = new Diagnostic(new Range(start, end),
                "Internal error while compiling this file: " + describe(error));
        lsp.setSeverity(DiagnosticSeverity.Error);
        lsp.setSource("javals");
        lsp.setCode("compiler-internal-error");

        client.publishDiagnostics(new PublishDiagnosticsParams(uri, List.of(lsp)));
    }

    private static String describe(Throwable error) {
        if (error == null)
            return "unknown error";
        String type = error.getClass().getSimpleName();
        String message = error.getMessage();
        return message == null || message.isBlank() ? type : type + ": " + message;
    }

    private void publishEmptyDiagnostics(String uri) {
        LanguageClient client = server.getClient();
        if (client == null)
            return;
        client.publishDiagnostics(new PublishDiagnosticsParams(uri, new ArrayList<>()));
    }

    private static Range rangeOf(LineMap lineMap, javax.tools.Diagnostic<?> d) {
        long start = clampPos(d.getStartPosition());
        long end = clampPos(d.getEndPosition());
        if (end < start)
            end = start;

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
        if (kind == null)
            return DiagnosticSeverity.Hint;
        switch (kind) {
            case ERROR:
                return DiagnosticSeverity.Error;
            case WARNING:
            case MANDATORY_WARNING:
                return DiagnosticSeverity.Warning;
            case NOTE:
                return DiagnosticSeverity.Information;
            case OTHER:
            default:
                return DiagnosticSeverity.Hint;
        }
    }
}
