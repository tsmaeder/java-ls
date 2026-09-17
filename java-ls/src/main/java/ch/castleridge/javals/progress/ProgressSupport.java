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
package ch.castleridge.javals.progress;

import java.util.concurrent.CompletableFuture;

import org.eclipse.lsp4j.WorkDoneProgressCreateParams;
import org.eclipse.lsp4j.WorkDoneProgressNotification;

/**
 * LSP Progress support API.
 *
 * <p>Provides an abstraction over the LSP {@code $/progress}
 * notification mechanism. Implementations check whether the client
 * advertised {@code window.workDoneProgress} capability and, when
 * supported, create and drive progress tokens via
 * {@link ProgressMonitor}.
 *
 * @see ProgressMonitor
 */
public interface ProgressSupport {

    /**
     * Creates a new {@link ProgressMonitor} if the client supports
     * work-done progress, or returns {@code null} otherwise.
     *
     * @return a new progress monitor, or {@code null}
     */
    default ProgressMonitor createProgressMonitor() {
        if (!isWorkDoneProgressSupported()) {
            return null;
        }
        return new ProgressMonitor(this);
    }

    /**
     * Returns {@code true} if the LSP client supports
     * {@code window/workDoneProgress}.
     *
     * @return whether work-done progress is supported
     */
    boolean isWorkDoneProgressSupported();

    /**
     * Asks the client to create a progress token.
     *
     * @param params the progress create parameters
     * @return a future that completes when the client acknowledges
     */
    CompletableFuture<Void> createProgress(WorkDoneProgressCreateParams params);

    /**
     * Sends a progress notification to the client.
     *
     * @param progressId   the progress token
     * @param notification the progress notification (begin, report, or end)
     */
    void notifyProgress(String progressId, WorkDoneProgressNotification notification);
}
