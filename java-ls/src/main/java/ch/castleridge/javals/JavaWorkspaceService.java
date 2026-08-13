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
import org.eclipse.lsp4j.services.WorkspaceService;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Workspace Service implementation handling workspace-wide operations
 */
public class JavaWorkspaceService implements WorkspaceService {

    private final JavaLanguageServer server;

    public JavaWorkspaceService(JavaLanguageServer server) {
        this.server = server;
    }

    @Override
    public void didChangeConfiguration(DidChangeConfigurationParams params) {
        server.logMessage(MessageType.Info, "Configuration changed");
    }

    @Override
    public void didChangeWatchedFiles(DidChangeWatchedFilesParams params) {
        for (FileEvent event : params.getChanges()) {
            server.logMessage(MessageType.Log, "File changed: " + event.getUri() + 
                " (type: " + event.getType() + ")");
        }
    }

    @Override
    public CompletableFuture<Either<List<? extends SymbolInformation>, List<? extends WorkspaceSymbol>>> symbol(WorkspaceSymbolParams params) {
        // Return empty list for now - would need workspace-wide indexing
        List<SymbolInformation> symbols = new ArrayList<>();
        return CompletableFuture.completedFuture(Either.forLeft(symbols));
    }

    @Override
    public CompletableFuture<Object> executeCommand(ExecuteCommandParams params) {
        server.logMessage(MessageType.Info, "Execute command: " + params.getCommand());
        return CompletableFuture.completedFuture(null);
    }
}
