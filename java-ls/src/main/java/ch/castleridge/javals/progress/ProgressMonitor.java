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

import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;

import org.eclipse.lsp4j.WorkDoneProgressBegin;
import org.eclipse.lsp4j.WorkDoneProgressCreateParams;
import org.eclipse.lsp4j.WorkDoneProgressEnd;
import org.eclipse.lsp4j.WorkDoneProgressReport;
import org.eclipse.lsp4j.jsonrpc.CancelChecker;
import org.eclipse.lsp4j.jsonrpc.messages.Either;

/**
 * Progress monitor which wraps the LSP progress API. Tracks a cumulative
 * percentage so callers can report incremental progress.
 */
public class ProgressMonitor implements CancelChecker {

    private final String progressId;
    private final ProgressSupport progressSupport;
    private CompletableFuture<Void> future;
    private int currentPercentage;

    public ProgressMonitor(ProgressSupport progressSupport) {
        this(UUID.randomUUID().toString(), progressSupport);
    }

    public ProgressMonitor(String progressId, ProgressSupport progressSupport) {
        this.progressId = progressId;
        this.progressSupport = progressSupport;
        this.currentPercentage = 0;
        WorkDoneProgressCreateParams create = new WorkDoneProgressCreateParams(Either.forLeft(progressId));
        future = progressSupport.createProgress(create);
    }

    /**
     * Sends the {@code WorkDoneProgressBegin} notification.
     *
     * @param title       the progress title shown in the UI
     * @param message     optional detail message
     * @param percentage  initial percentage (0-100), or {@code null}
     * @param cancellable whether the operation can be cancelled
     */
    public void begin(String title, String message, Integer percentage, Boolean cancellable) {
        WorkDoneProgressBegin begin = new WorkDoneProgressBegin();
        begin.setTitle(title);
        begin.setMessage(message);
        begin.setPercentage(percentage);
        begin.setCancellable(cancellable);
        progressSupport.notifyProgress(progressId, begin);
    }

    /**
     * Reports progress with an incremental percentage.
     *
     * @param message   progress message to display
     * @param increment percentage points to add to the current percentage
     */
    public void report(String message, int increment) {
        currentPercentage += increment;
        WorkDoneProgressReport report = new WorkDoneProgressReport();
        report.setMessage(message);
        report.setPercentage(currentPercentage);
        progressSupport.notifyProgress(progressId, report);
    }

    /**
     * Reports progress with an absolute percentage.
     *
     * @param message    progress message to display
     * @param percentage absolute percentage (0-100)
     */
    public void reportAbsolute(String message, int percentage) {
        currentPercentage = percentage;
        WorkDoneProgressReport report = new WorkDoneProgressReport();
        report.setMessage(message);
        report.setPercentage(percentage);
        progressSupport.notifyProgress(progressId, report);
    }

    /**
     * Sends the {@code WorkDoneProgressEnd} notification.
     *
     * @param message final message summarizing the result
     */
    public void end(String message) {
        WorkDoneProgressEnd end = new WorkDoneProgressEnd();
        end.setMessage(message);
        progressSupport.notifyProgress(progressId, end);
    }

    @Override
    public void checkCanceled() {
        if (future != null && future.isCancelled()) {
            throw new CancellationException();
        }
    }

    @Override
    public boolean isCanceled() {
        return future != null ? future.isCancelled() : true;
    }
}
