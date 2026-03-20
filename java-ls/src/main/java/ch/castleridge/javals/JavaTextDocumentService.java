package ch.castleridge.javals;

import org.eclipse.lsp4j.*;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.eclipse.lsp4j.jsonrpc.messages.Either3;
import org.eclipse.lsp4j.services.TextDocumentService;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Text Document Service implementation handling document operations
 * Copyright Anysphere Inc.
 */
public class JavaTextDocumentService implements TextDocumentService {

    private final JavaLanguageServer server;
    private final Map<String, TextDocumentItem> documents = new ConcurrentHashMap<>();

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

    @Override
    public CompletableFuture<Either<List<? extends Location>, List<? extends LocationLink>>> definition(DefinitionParams params) {
        // Return empty list for now - would need actual Java parsing
        return CompletableFuture.completedFuture(Either.forLeft(new ArrayList<>()));
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
