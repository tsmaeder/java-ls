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
import org.eclipse.lsp4j.services.*;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.io.PrintWriter;
import java.io.StringWriter;
/**
 * Main Language Server implementation for Java LSP
 */
public class JavaLanguageServer implements LanguageServer, LanguageClientAware {
    
    private final TextDocumentService textDocumentService;
    private final WorkspaceService workspaceService;
    private final IndexService indexService;
    private LanguageClient client;
    private int errorCode = 1;
    private volatile String compilerBackend = "javac";
    private volatile boolean watchedFilesDynamicRegistration;

    public JavaLanguageServer() {
        this.indexService = new IndexService(this);
        this.textDocumentService = new JavaTextDocumentService(this, indexService);
        this.workspaceService = new JavaWorkspaceService(this);
        indexService.addIndexChangedListener(this::rebindWorkspaceCompiler);
    }

    private void rebindWorkspaceCompiler() {
        JavaTextDocumentService tds = (JavaTextDocumentService) textDocumentService;
        tds.setWorkspaceCompiler(ch.castleridge.javals.analysis.BackendFactory.workspaceCompiler(
                compilerBackend,
                tds.symbolLocator(),
                tds.declarationLocator(),
                indexService.sourceJarByBinaryJar()));
    }

    public IndexService getIndexService() {
        return indexService;
    }

    @Override
    public CompletableFuture<InitializeResult> initialize(InitializeParams params) {
        InitializationOptions.Backend backend = InitializationOptions.backend(params);
        this.compilerBackend = backend.compiler();
        this.watchedFilesDynamicRegistration = supportsWatchedFilesDynamicRegistration(params);
        indexService.setSourceIndexer(
                ch.castleridge.javals.indexing.source.SourceIndexer.of(backend.sourceIndexer()));
        indexService.setBytecodeIndexer(
                ch.castleridge.javals.indexing.bytecode.BytecodeIndexer.of(backend.classIndexer()));
        rebindWorkspaceCompiler();
        logMessage(MessageType.Info,
                "Backend: sourceIndexer=" + backend.sourceIndexer()
                        + ", classIndexer=" + backend.classIndexer()
                        + ", compiler=" + backend.compiler());

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

        // Type hierarchy (prepare + subtypes + supertypes)
        capabilities.setTypeHierarchyProvider(true);
        
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

    /**
     * Dynamically register {@code workspace/didChangeWatchedFiles} watchers for
     * {@code **}/{@code *.java} under each mbt source root, when the client supports it.
     */
    public void registerSourceFileWatchers(List<String> sourceRootUris) {
        if (!watchedFilesDynamicRegistration || client == null
                || sourceRootUris == null || sourceRootUris.isEmpty()) {
            return;
        }
        List<FileSystemWatcher> watchers = new ArrayList<>(sourceRootUris.size());
        int kind = WatchKind.Create + WatchKind.Change + WatchKind.Delete;
        for (String sourceUri : sourceRootUris) {
            if (sourceUri == null || sourceUri.isBlank()) continue;
            RelativePattern pattern = new RelativePattern(Either.forRight(sourceUri), "**/*.java");
            watchers.add(new FileSystemWatcher(Either.forRight(pattern), kind));
        }
        if (watchers.isEmpty()) return;
        DidChangeWatchedFilesRegistrationOptions options =
                new DidChangeWatchedFilesRegistrationOptions(watchers);
        Registration registration = new Registration(
                UUID.randomUUID().toString(),
                "workspace/didChangeWatchedFiles",
                options);
        try {
            client.registerCapability(new RegistrationParams(List.of(registration)));
            logMessage(MessageType.Info,
                    "Registered file watchers for " + watchers.size() + " source root(s)");
        } catch (RuntimeException e) {
            logMessage(MessageType.Warning,
                    "Could not register source file watchers: " + e.getMessage());
        }
    }

    private static boolean supportsWatchedFilesDynamicRegistration(InitializeParams params) {
        if (params == null || params.getCapabilities() == null) return false;
        WorkspaceClientCapabilities workspace = params.getCapabilities().getWorkspace();
        if (workspace == null || workspace.getDidChangeWatchedFiles() == null) return false;
        return Boolean.TRUE.equals(workspace.getDidChangeWatchedFiles().getDynamicRegistration());
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

    public void logException(Throwable e) {
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
