package ch.castleridge.javals;

import org.eclipse.lsp4j.*;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.eclipse.lsp4j.jsonrpc.messages.Either3;
import org.eclipse.lsp4j.services.TextDocumentService;

import java.net.URI;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

import javax.lang.model.element.Element;

import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.LineMap;
import com.sun.source.util.TreePath;
import com.sun.source.util.Trees;

import ch.castleridge.javals.indexing.index.Index;
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
    private final SourceCache sourceCache = new SourceCache();
    private final SymbolLocator symbolLocator = new SymbolLocator(sourceCache);

    public JavaTextDocumentService(JavaLanguageServer server) {
        this.server = server;
    }

    @Override
    public void didOpen(DidOpenTextDocumentParams params) {
        TextDocumentItem doc = params.getTextDocument();
        documents.put(doc.getUri(), doc);
        server.logMessage(MessageType.Info, "Document opened: " + doc.getUri());
    }

    @Override
    public void didChange(DidChangeTextDocumentParams params) {
        String uri = params.getTextDocument().getUri();
        List<TextDocumentContentChangeEvent> changes = params.getContentChanges();
        
        if (!changes.isEmpty()) {
            // For full sync, just take the last change which contains the full text
            TextDocumentContentChangeEvent change = changes.get(changes.size() - 1);
            TextDocumentItem doc = documents.get(uri);
            if (doc != null) {
                
                // complete bullshit
                documents.put(uri, new TextDocumentItem(uri, doc.getLanguageId(), 
                    doc.getVersion() + 1, change.getText()));
            }
        }
        server.logMessage(MessageType.Log, "Document changed: " + uri);
    }

    @Override
    public void didClose(DidCloseTextDocumentParams params) {
        String uri = params.getTextDocument().getUri();
        documents.remove(uri);
        server.logMessage(MessageType.Info, "Document closed: " + uri);
    }

    @Override
    public void didSave(DidSaveTextDocumentParams params) {
        server.logMessage(MessageType.Info, "Document saved: " + params.getTextDocument().getUri());
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
        String uri = params.getTextDocument().getUri();
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
        String uri = params.getTextDocument().getUri();
        Position position = params.getPosition();
        return CompletableFuture.supplyAsync(() -> Either.forLeft(computeDefinition(uri, position)));
    }

    private List<Location> computeDefinition(String uri, Position position) {
        TextDocumentItem doc = documents.get(uri);
        if (doc == null) return List.of();

        IndexService indexService = server.getIndexService();
        Index index = indexService.index().orElse(null);
        ClasspathOrder classpath = indexService.classpath().orElse(null);
        if (index == null || classpath == null) {
            // Index not ready yet: fall back to an empty in-memory index so that
            // same-file lookups still work.
            index = new Index();
            classpath = ClasspathOrder.UNRESTRICTED;
        }

        URI docUri;
        try {
            docUri = URI.create(uri);
        } catch (IllegalArgumentException e) {
            return List.of();
        }

        WorkspaceCompiler.Result compiled;
        try {
            compiled = WorkspaceCompiler.compile(docUri, doc.getText(), index, classpath);
        } catch (RuntimeException e) {
            server.logMessage(MessageType.Warning, "Definition: compile failed for " + uri + ": " + e.getMessage());
            return List.of();
        }

        CompilationUnitTree cu = compiled.cu();
        if (cu == null) return List.of();

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

    public TextDocumentItem getDocument(String uri) {
        return documents.get(uri);
    }
}
