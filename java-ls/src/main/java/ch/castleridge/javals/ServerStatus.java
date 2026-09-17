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
 * Lifecycle status of the Java Language Server.
 *
 * <p>The server transitions through these states during startup:
 * <ol>
 *   <li>{@link #starting} — server is initializing, not yet indexing</li>
 *   <li>{@link #indexing} — workspace files are being indexed</li>
 *   <li>{@link #ready} — indexing is complete, all LSP features are available</li>
 * </ol>
 *
 * <p>Clients can use this to determine when features like
 * {@code textDocument/references} will return complete results.
 */
public enum ServerStatus {
    /** Server is initializing, not yet indexing. */
    starting,
    /** Workspace files are being indexed. */
    indexing,
    /** Indexing is complete, all LSP features are available. */
    ready
}
