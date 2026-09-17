/**
 * Copyright (c) 2026 IBM Corporation and others.
 *
 * Licensed under the MIT License.
 *
 * SPDX-License-Identifier: MIT
 *
 * Author: Angelo Zerr
 *
 */
package ch.castleridge.javals;

import org.eclipse.lsp4j.jsonrpc.services.JsonNotification;
import org.eclipse.lsp4j.services.LanguageClient;

/**
 * Extended {@link LanguageClient} with custom notifications
 * specific to the Java Language Server.
 *
 * <p>Used as the remote interface in the {@code Launcher.Builder}
 * so the JSON-RPC proxy implements these custom methods.
 */
public interface JavaLanguageClient extends LanguageClient {

    /**
     * Notification sent when the server transitions between lifecycle
     * states ({@code starting} → {@code indexing} → {@code ready}).
     *
     * <p>Clients can listen for the {@code ready} status to know when
     * LSP features like {@code textDocument/references} will return
     * complete results.
     *
     * @param params the server status parameters
     */
    @JsonNotification("java/serverStatus")
    void serverStatus(ServerStatusParams params);
}
