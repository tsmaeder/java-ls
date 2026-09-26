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

import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.eclipse.lsp4j.ProgressParams;
import org.eclipse.lsp4j.WorkDoneProgressBegin;
import org.eclipse.lsp4j.WorkDoneProgressCreateParams;
import org.eclipse.lsp4j.WorkDoneProgressEnd;
import org.eclipse.lsp4j.WorkDoneProgressNotification;
import org.eclipse.lsp4j.WorkDoneProgressReport;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.eclipse.lsp4j.services.LanguageClient;

/**
 * Reports non-cancellable LSP {@code $/progress} workDoneProgress for the
 * initial workspace index scan. Creates a server-owned token via
 * {@code window/workDoneProgress/create} when the client supports it.
 * No-ops otherwise.
 */
final class IndexingProgress {

    static final int PROGRESS_EVERY = 100;
    private static final long CREATE_TIMEOUT_SECONDS = 5;

    private final LanguageClient client;
    private final Either<String, Integer> token;
    private final AtomicBoolean ended = new AtomicBoolean();

    /** Package-visible for unit tests that supply a client token directly. */
    IndexingProgress(LanguageClient client, Either<String, Integer> token) {
        this.client = client;
        this.token = token;
    }

    static IndexingProgress open(JavaLanguageServer server) {
        LanguageClient client = server == null ? null : server.getClient();
        Either<String, Integer> token = null;

        if (server != null && server.supportsWorkDoneProgress() && client != null) {
            Either<String, Integer> created = Either.forLeft(UUID.randomUUID().toString());
            try {
                client.createProgress(new WorkDoneProgressCreateParams(created))
                        .get(CREATE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                token = created;
            } catch (Exception ignored) {
                token = null;
            }
        }

        return new IndexingProgress(client, token);
    }

    boolean active() {
        return token != null && client != null;
    }

    void begin() {
        if (token == null || client == null) {
            return;
        }
        WorkDoneProgressBegin begin = new WorkDoneProgressBegin();
        begin.setTitle("Indexing");
        begin.setCancellable(false);
        begin.setPercentage(0);
        notify(Either.forLeft(begin));
    }

    /**
     * Report every {@link #PROGRESS_EVERY} files within a source.
     * Message format: {@code <sourceUri> (<n> of <m>)<filesInSource>}.
     */
    void fileIndexed(String sourceUri, int sourceIndex, int sourceCount, int filesInSource) {
        if (token == null || client == null) {
            return;
        }
        if (filesInSource % PROGRESS_EVERY != 0) {
            return;
        }
        WorkDoneProgressReport report = new WorkDoneProgressReport();
        report.setCancellable(false);
        report.setMessage(message(sourceUri, sourceIndex, sourceCount, filesInSource));
        if (sourceCount > 0) {
            report.setPercentage(Math.min(100, (sourceIndex * 100) / sourceCount));
        }
        notify(Either.forLeft(report));
    }

    void end(String message) {
        if (token == null || client == null || !ended.compareAndSet(false, true)) {
            return;
        }
        WorkDoneProgressEnd end = new WorkDoneProgressEnd();
        if (message != null && !message.isBlank()) {
            end.setMessage(message);
        }
        notify(Either.forLeft(end));
    }

    static String message(String sourceUri, int sourceIndex, int sourceCount, int filesInSource) {
        String uri = sourceUri == null ? "" : sourceUri;
        return uri + " (" + sourceIndex + " of " + sourceCount + ")" + filesInSource;
    }

    private void notify(Either<WorkDoneProgressNotification, Object> value) {
        synchronized (this) {
            client.notifyProgress(new ProgressParams(token, value));
        }
    }
}
