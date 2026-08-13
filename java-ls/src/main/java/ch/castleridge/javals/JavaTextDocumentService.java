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

import ch.castleridge.javals.analysis.AnalysisSession;
import ch.castleridge.javals.analysis.BackendFactory;
import ch.castleridge.javals.analysis.PublishedDiagnostic;
import ch.castleridge.javals.analysis.ResolvedSymbol;
import ch.castleridge.javals.analysis.SymbolIdentity;
import ch.castleridge.javals.analysis.SourceText;
import ch.castleridge.javals.analysis.WorkspaceCompiler;
import ch.castleridge.javals.analysis.ecj.EcjDeclarationLocator;
import ch.castleridge.javals.analysis.javac.SourceCache;
import ch.castleridge.javals.analysis.javac.SymbolLocator;
import ch.castleridge.javals.classpath.ClasspathOrder;
import ch.castleridge.javals.indexing.bloom.IdentifierBloomFilter;
import ch.castleridge.javals.indexing.index.Index;
import ch.castleridge.javals.indexing.index.UriCoding;

/**
 * Text Document Service implementation handling document operations.
 * Compiler-backend agnostic: all analysis goes through {@link WorkspaceCompiler}.
 */
public class JavaTextDocumentService implements TextDocumentService {

    private final JavaLanguageServer server;
    private final IndexService indexService;
    private final Map<String, TextDocumentItem> documents = new ConcurrentHashMap<>();
    private final Map<String, CachedCompile> compileCache = new ConcurrentHashMap<>();
    private final SourceCache sourceCache = new SourceCache();
    private final SymbolLocator symbolLocator = new SymbolLocator(sourceCache);
    private final EcjDeclarationLocator declarationLocator = new EcjDeclarationLocator();
    private volatile WorkspaceCompiler workspaceCompiler = BackendFactory.workspaceCompiler("javac");
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

    public void setWorkspaceCompiler(WorkspaceCompiler workspaceCompiler) {
        this.workspaceCompiler = workspaceCompiler == null
                ? BackendFactory.workspaceCompiler("javac")
                : workspaceCompiler;
    }

    public void setReferencesCandidateCap(int referencesCandidateCap) {
        this.referencesCandidateCap = referencesCandidateCap;
    }

    int referencesCandidateCap() {
        return referencesCandidateCap;
    }

    SymbolLocator symbolLocator() {
        return symbolLocator;
    }

    EcjDeclarationLocator declarationLocator() {
        return declarationLocator;
    }

    private record CachedCompile(int version, AnalysisSession session) {
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

    /**
     * Declarations in {@code uri} are located in a parse of the file as the
     * index saw it, so an edit invalidates those positions.
     */
    private void forgetParsedSource(String uri) {
        sourceCache.invalidate(uri);
        declarationLocator.invalidate(uri);
    }

    private void refreshCompile(String uri) {
        TextDocumentItem doc = documents.get(uri);
        if (doc == null)
            return;
        forgetParsedSource(uri);
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

            AnalysisSession session;
            long t0 = System.currentTimeMillis();
            try {
                session = workspaceCompiler.analyze(docUri, text, index, classpath);
                long t1 = System.currentTimeMillis();
                server.logMessage(MessageType.Log,
                        "Refresh compile took " + (t1 - t0) + "ms for " + uri);
            } catch (RuntimeException | Error e) {
                server.logMessage(MessageType.Error,
                        "Compile failed for " + uri + ": " + describe(e));
                server.logException(e);
                TextDocumentItem latestOnError = documents.get(uri);
                if (latestOnError == null || latestOnError.getVersion() != versionAtStart) {
                    return;
                }
                publishCompilerErrorDiagnostic(uri, e);
                return;
            }

            TextDocumentItem latest = documents.get(uri);
            if (latest == null || latest.getVersion() != versionAtStart) {
                return;
            }
            compileCache.put(uri, new CachedCompile(versionAtStart, session));
            publishDiagnostics(uri, session.diagnostics());
        });
    }

    @Override
    public void didSave(DidSaveTextDocumentParams params) {
        String uri = UriCoding.decode(params.getTextDocument().getUri());
        forgetParsedSource(uri);
        server.logMessage(MessageType.Info, "Document saved: " + uri);
    }

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

        AnalysisSession session = sessionForCompletion(uri, doc);
        if (session == null || !session.isUsable())
            return List.of();

        Index index = indexService.index().orElse(null);
        ClasspathOrder classpath = indexService.classPathFor(uri);
        return session.complete(doc.getText(), position, index, classpath);
    }

    private AnalysisSession sessionForCompletion(String uri, TextDocumentItem doc) {
        CachedCompile cached = compileCache.get(uri);
        if (cached != null && cached.session() != null) {
            return cached.session();
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
            return workspaceCompiler.analyze(docUri, doc.getText(), indexOpt.get(), classpath);
        } catch (RuntimeException | Error e) {
            server.logMessage(MessageType.Error, "Completion compile failed for " + uri + ": " + describe(e));
            server.logException(e);
            return null;
        }
    }

    @Override
    public CompletableFuture<CompletionItem> resolveCompletionItem(CompletionItem item) {
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

    @Override
    public CompletableFuture<Either<List<? extends Location>, List<? extends LocationLink>>> definition(
            DefinitionParams params) {
        String uri = UriCoding.decode(params.getTextDocument().getUri());
        Position position = params.getPosition();
        return CompletableFuture.supplyAsync(() -> Either.forLeft(computeDefinition(uri, position)));
    }

    private List<Location> computeDefinition(String uri, Position position) {
        Optional<ResolvedSymbol> resolved = resolveSymbolAt(uri, position);
        if (resolved.isEmpty())
            return List.of();

        CachedCompile cached = compileCache.get(uri);
        if (cached == null || cached.session() == null)
            return List.of();

        return cached.session().definitionOf(resolved.get()).map(List::of).orElse(List.of());
    }

    private Optional<ResolvedSymbol> resolveSymbolAt(String uri, Position position) {
        CachedCompile cached = compileCache.get(uri);
        if (cached == null || cached.session() == null)
            return Optional.empty();
        AnalysisSession session = cached.session();
        if (!session.isUsable()) {
            refreshCompile(uri);
            return Optional.empty();
        }
        return session.resolveAt(position);
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
        Optional<ResolvedSymbol> resolvedOpt = resolveSymbolAt(uri, position);
        if (resolvedOpt.isEmpty())
            return List.of();

        CachedCompile cached = compileCache.get(uri);
        if (cached == null || cached.session() == null)
            return List.of();

        AnalysisSession session = cached.session();
        ResolvedSymbol resolved = resolvedOpt.get();
        SymbolIdentity identity = resolved.identity();

        if (resolved.fileLocal()) {
            Set<Location> locations = new LinkedHashSet<>(session.referencesInUnit(resolved));
            return finalizeReferences(locations, includeDeclaration, session, resolved);
        }

        Set<String> bloomCandidates = new LinkedHashSet<>();
        Optional<Index> indexOpt = indexService.index();
        if (indexOpt.isPresent()) {
            String simpleName = identity.simpleName();
            for (Map.Entry<String, IdentifierBloomFilter> entry : indexOpt.get().bloomFilters().entrySet()) {
                // Only source blooms can yield source reference locations.
                // Classfile-keyed blooms (jar/jrt *.class) would otherwise be
                // read as text and compiled as garbage, so skip them here.
                if (entry.getKey().endsWith(".java") && entry.getValue().mightContain(simpleName)) {
                    bloomCandidates.add(entry.getKey());
                }
            }
        }
        int bloomHits = bloomCandidates.size();
        int openDocs = documents.size();

        Set<String> candidates = new LinkedHashSet<>(bloomCandidates);
        candidates.addAll(documents.keySet());
        resolved.originResourceUri().filter(u -> u.endsWith(".java")).ifPresent(candidates::add);

        int totalBeforeCap = candidates.size();
        String capNote = "";
        if (referencesCandidateCap > 0 && candidates.size() > referencesCandidateCap) {
            Set<String> capped = new LinkedHashSet<>(documents.keySet());
            resolved.originResourceUri().filter(u -> u.endsWith(".java")).ifPresent(capped::add);
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
                "References: '" + identity.simpleName() + "' -> " + candidates.size()
                        + " candidates (" + bloomHits + " bloom hits, " + openDocs + " open docs"
                        + capNote
                        + resolved.originResourceUri().map(u -> ", origin " + u).orElse("") + ")");

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

            AnalysisSession candidateSession;
            try {
                candidateSession = workspaceCompiler.analyze(docUri, text, index.get(), classpath);
            } catch (RuntimeException e) {
                server.logMessage(MessageType.Error,
                        "Error compiling candidate " + candidateUri + ": " + e.getMessage());
                server.logException(e);
                return;
            }
            if (!candidateSession.isUsable())
                return;
            locations.addAll(candidateSession.findReferencesTo(identity));
        });

        long elapsedMs = (System.nanoTime() - t0) / 1_000_000L;
        server.logMessage(MessageType.Log,
                "References: resolved " + locations.size() + " references across "
                        + candidates.size() + " files in " + elapsedMs + " ms");

        return finalizeReferences(locations, includeDeclaration, session, resolved);
    }

    @Override
    public CompletableFuture<List<TypeHierarchyItem>> prepareTypeHierarchy(TypeHierarchyPrepareParams params) {
        String uri = UriCoding.decode(params.getTextDocument().getUri());
        Position position = params.getPosition();
        return CompletableFuture.supplyAsync(() -> computePrepareTypeHierarchy(uri, position));
    }

    @Override
    public CompletableFuture<List<TypeHierarchyItem>> typeHierarchySupertypes(TypeHierarchySupertypesParams params) {
        TypeHierarchyItem item = params.getItem();
        return CompletableFuture.supplyAsync(() -> computeTypeHierarchySupertypes(item));
    }

    @Override
    public CompletableFuture<List<TypeHierarchyItem>> typeHierarchySubtypes(TypeHierarchySubtypesParams params) {
        TypeHierarchyItem item = params.getItem();
        return CompletableFuture.supplyAsync(() -> computeTypeHierarchySubtypes(item));
    }

    private List<TypeHierarchyItem> computePrepareTypeHierarchy(String uri, Position position) {
        CachedCompile cached = compileCache.get(uri);
        if (cached == null || cached.session() == null || !cached.session().isUsable()) {
            return List.of();
        }
        return cached.session().prepareTypeHierarchy(position).map(List::of).orElse(List.of());
    }

    private List<TypeHierarchyItem> computeTypeHierarchySupertypes(TypeHierarchyItem item) {
        if (item == null) return List.of();
        AnalysisSession session = sessionForHierarchyItem(item);
        if (session == null) return List.of();
        return session.typeHierarchySupertypes(item);
    }

    private List<TypeHierarchyItem> computeTypeHierarchySubtypes(TypeHierarchyItem item) {
        if (item == null) return List.of();
        AnalysisSession session = sessionForHierarchyItem(item);
        if (session == null) return List.of();
        return session.typeHierarchySubtypes(item);
    }

    /**
     * Prefer an open document's session so locators share parse caches; otherwise
     * synthesize a session against the item's URI so index walks still work.
     */
    private AnalysisSession sessionForHierarchyItem(TypeHierarchyItem item) {
        String uri = item.getUri() == null ? null : UriCoding.decode(item.getUri());
        if (uri != null) {
            CachedCompile cached = compileCache.get(uri);
            if (cached != null && cached.session() != null && cached.session().isUsable()) {
                return cached.session();
            }
        }
        // Any usable open session carries the same index; fall back to the first.
        for (CachedCompile cached : compileCache.values()) {
            if (cached != null && cached.session() != null && cached.session().isUsable()) {
                return cached.session();
            }
        }
        Optional<Index> indexOpt = indexService.index();
        if (indexOpt.isEmpty() || uri == null) return null;
        String text = textForUri(uri);
        if (text == null) text = "";
        try {
            return workspaceCompiler.analyze(URI.create(uri), text, indexOpt.get(), indexService.classPathFor(uri));
        } catch (RuntimeException e) {
            server.logMessage(MessageType.Error, "Type hierarchy session failed for " + uri + ": " + describe(e));
            return null;
        }
    }

    private List<Location> finalizeReferences(Set<Location> locations,
            boolean includeDeclaration,
            AnalysisSession session,
            ResolvedSymbol resolved) {
        Optional<Location> declaration = session.definitionOf(resolved);
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
        return SourceText.read(uri);
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

    private void publishDiagnostics(String uri, List<PublishedDiagnostic> diags) {
        LanguageClient client = server.getClient();
        if (client == null)
            return;

        List<Diagnostic> out = new ArrayList<>();
        for (PublishedDiagnostic d : diags) {
            Diagnostic lsp = new Diagnostic(d.range(), d.message());
            lsp.setSeverity(d.severity());
            lsp.setSource(d.source());
            if (d.code() != null && !d.code().isEmpty()) {
                lsp.setCode(d.code());
            }
            out.add(lsp);
        }

        client.publishDiagnostics(new PublishDiagnosticsParams(uri, out));
    }

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
}
