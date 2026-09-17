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

/**
 * Parameters for the {@code java/serverStatus} notification.
 *
 * <p>Sent by the server to inform the client of lifecycle state
 * transitions. Clients (including MCP adapters) can use this to
 * know when features like {@code textDocument/references} will
 * return complete results.
 *
 * @see ServerStatus
 */
public class ServerStatusParams {

    private String status;

    public ServerStatusParams() {
    }

    public ServerStatusParams(ServerStatus status) {
        this.status = status.name();
    }

    /**
     * Returns the current server status as a string
     * ({@code "starting"}, {@code "indexing"}, or {@code "ready"}).
     *
     * @return the status name
     */
    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }
}
