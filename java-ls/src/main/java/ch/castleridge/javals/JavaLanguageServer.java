package ch.castleridge.javals;

import org.eclipse.lsp4j.*;
import org.eclipse.lsp4j.services.*;
import java.util.concurrent.CompletableFuture;
import java.io.PrintWriter;
import java.io.StringWriter;
/**
 * Main Language Server implementation for Java LSP
 * Copyright Anysphere Inc.
 */
public class JavaLanguageServer implements LanguageServer, LanguageClientAware {
    
    private final TextDocumentService textDocumentService;
    private final WorkspaceService workspaceService;
    private final IndexService indexService;
    private LanguageClient client;
    private int errorCode = 1;

    public JavaLanguageServer() {
        this.indexService = new IndexService(this);
        this.textDocumentService = new JavaTextDocumentService(this, indexService);
        this.workspaceService = new JavaWorkspaceService(this);
    }

    public IndexService getIndexService() {
        return indexService;
    }

    @Override
    public CompletableFuture<InitializeResult> initialize(InitializeParams params) {
        indexService.initialize(params);
        InitializationOptions.referencesCandidateCap(params)
                .ifPresent(((JavaTextDocumentService) textDocumentService)::setReferencesCandidateCap);

        // Set up server capabilities
        ServerCapabilities capabilities = new ServerCapabilities();
        
        // Text document sync
        capabilities.setTextDocumentSync(TextDocumentSyncKind.Full);
        
        // Completion support
        CompletionOptions completionOptions = new CompletionOptions();
        completionOptions.setResolveProvider(true);
        completionOptions.setTriggerCharacters(java.util.Arrays.asList(".", "@"));
        capabilities.setCompletionProvider(completionOptions);
        
        // Hover support
        capabilities.setHoverProvider(true);
        
        // Definition support
        capabilities.setDefinitionProvider(true);
        
        // References support
        capabilities.setReferencesProvider(true);
        
        // Document symbol support
        capabilities.setDocumentSymbolProvider(true);
        
        // Workspace symbol support
        capabilities.setWorkspaceSymbolProvider(true);
        
        // Code action support
        capabilities.setCodeActionProvider(true);
        
        // Document formatting
        capabilities.setDocumentFormattingProvider(true);
        capabilities.setDocumentRangeFormattingProvider(true);
        
        // Rename support
        RenameOptions renameOptions = new RenameOptions();
        renameOptions.setPrepareProvider(true);
        capabilities.setRenameProvider(renameOptions);
        
        // Signature help
        SignatureHelpOptions signatureHelpOptions = new SignatureHelpOptions();
        signatureHelpOptions.setTriggerCharacters(java.util.Arrays.asList("(", ","));
        capabilities.setSignatureHelpProvider(signatureHelpOptions);

        InitializeResult result = new InitializeResult(capabilities);
        return CompletableFuture.completedFuture(result);
    }

    @Override
    public CompletableFuture<Object> shutdown() {
        errorCode = 0;
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public void exit() {
        System.exit(errorCode);
    }

    @Override
    public TextDocumentService getTextDocumentService() {
        return textDocumentService;
    }

    @Override
    public WorkspaceService getWorkspaceService() {
        return workspaceService;
    }

    @Override
    public void connect(LanguageClient client) {
        this.client = client;
    }

    public LanguageClient getClient() {
        return client;
    }

    public void logMessage(MessageType type, String message) {
        if (client != null) {
            MessageParams params = new MessageParams(type, message);
            client.logMessage(params);
        }
    }

    public void logException(Exception e) {
        if (client != null) {
            StringWriter stringWriter = new StringWriter();
            PrintWriter printWriter = new PrintWriter(stringWriter);
            e.printStackTrace(printWriter);
            printWriter.close();
            String stackTrace = stringWriter.toString();
            MessageParams params = new MessageParams(MessageType.Error, e.getMessage() + "\n" + stackTrace);
            client.logMessage(params);
        }
    }
}
