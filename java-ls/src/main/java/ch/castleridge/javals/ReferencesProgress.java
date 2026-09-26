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
import java.util.concurrent.atomic.AtomicInteger;

import org.eclipse.lsp4j.ProgressParams;
import org.eclipse.lsp4j.WorkDoneProgressBegin;
import org.eclipse.lsp4j.WorkDoneProgressCreateParams;
import org.eclipse.lsp4j.WorkDoneProgressEnd;
import org.eclipse.lsp4j.WorkDoneProgressNotification;
import org.eclipse.lsp4j.WorkDoneProgressReport;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.eclipse.lsp4j.services.LanguageClient;

/**
 * Reports LSP {@code $/progress} workDoneProgress for a references scan.
 * Uses a client-supplied workDoneToken when present; otherwise creates a
 * server-owned token via {@code window/workDoneProgress/create} when the
 * client supports it. No-ops when neither is available.
 */
final class ReferencesProgress {

    static final int PROGRESS_EVERY = 50;
    private static final long CREATE_TIMEOUT_SECONDS = 5;

    private final LanguageClient client;
    private final Either<String, Integer> token;
    private final int total;
    private final AtomicInteger done = new AtomicInteger();
    private final AtomicBoolean ended = new AtomicBoolean();
    private final JavaLanguageServer server;
    private final Either<String, Integer> registeredCancelToken;

    private ReferencesProgress(LanguageClient client,
            Either<String, Integer> token,
            int total,
            JavaLanguageServer server,
            Either<String, Integer> registeredCancelToken) {
        this.client = client;
        this.token = token;
        this.total = Math.max(total, 0);
        this.server = server;
        this.registeredCancelToken = registeredCancelToken;
    }

    /** Package-visible for unit tests that supply a client token directly. */
    ReferencesProgress(LanguageClient client, Either<String, Integer> token, int total) {
        this(client, token, total, null, null);
    }

    /**
     * Resolve a progress token and optionally register UI-cancel handling.
     *
     * @param progressCancel set by {@link JavaLanguageServer#cancelProgress} for server-owned tokens
     */
    static ReferencesProgress open(JavaLanguageServer server,
            Either<String, Integer> clientToken,
            int total,
            AtomicBoolean progressCancel) {
        LanguageClient client = server == null ? null : server.getClient();
        Either<String, Integer> token = clientToken;
        Either<String, Integer> registered = null;

        if (token == null && server != null && server.supportsWorkDoneProgress() && client != null) {
            Either<String, Integer> created = Either.forLeft(UUID.randomUUID().toString());
            try {
                client.createProgress(new WorkDoneProgressCreateParams(created))
                        .get(CREATE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                token = created;
                server.registerProgressCancel(created, progressCancel);
                registered = created;
            } catch (Exception ignored) {
                token = null;
            }
        }

        return new ReferencesProgress(client, token, total, server, registered);
    }

    /** True when reporting against an active token. */
    boolean active() {
        return token != null && client != null;
    }

    Either<String, Integer> token() {
        return token;
    }

    void begin() {
        if (token == null || client == null) {
            return;
        }
        WorkDoneProgressBegin begin = new WorkDoneProgressBegin();
        begin.setTitle("Finding references");
        begin.setCancellable(true);
        begin.setPercentage(0);
        begin.setMessage(message(0));
        notify(Either.forLeft(begin));
    }

    /**
     * Increment the analyzed-file counter and report every {@link #PROGRESS_EVERY} files.
     */
    void fileDone() {
        int n = done.incrementAndGet();
        if (token == null || client == null) {
            return;
        }
        if (n % PROGRESS_EVERY == 0 || (total > 0 && n >= total)) {
            report(n);
        }
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

    /** Drop UI-cancel registration for a server-owned token. */
    void unregister() {
        if (server != null && registeredCancelToken != null) {
            server.unregisterProgressCancel(registeredCancelToken);
        }
    }

    private void report(int n) {
        WorkDoneProgressReport report = new WorkDoneProgressReport();
        report.setCancellable(true);
        report.setMessage(message(n));
        if (total > 0) {
            report.setPercentage(Math.min(100, (n * 100) / total));
        }
        notify(Either.forLeft(report));
    }

    private String message(int n) {
        if (total > 0) {
            return "Analyzed " + n + " of " + total + " files";
        }
        return "Analyzed " + n + " files";
    }

    private void notify(Either<WorkDoneProgressNotification, Object> value) {
        synchronized (this) {
            client.notifyProgress(new ProgressParams(token, value));
        }
    }
}
