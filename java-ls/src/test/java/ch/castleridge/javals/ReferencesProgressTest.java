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

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import org.eclipse.lsp4j.MessageActionItem;
import org.eclipse.lsp4j.MessageParams;
import org.eclipse.lsp4j.ProgressParams;
import org.eclipse.lsp4j.PublishDiagnosticsParams;
import org.eclipse.lsp4j.ShowMessageRequestParams;
import org.eclipse.lsp4j.WorkDoneProgressBegin;
import org.eclipse.lsp4j.WorkDoneProgressEnd;
import org.eclipse.lsp4j.WorkDoneProgressKind;
import org.eclipse.lsp4j.WorkDoneProgressNotification;
import org.eclipse.lsp4j.WorkDoneProgressReport;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.eclipse.lsp4j.services.LanguageClient;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReferencesProgressTest {

    @Test
    void reportsEveryFiftyFilesThenEnd() {
        RecordingClient client = new RecordingClient();
        Either<String, Integer> token = Either.forLeft("refs-1");
        ReferencesProgress progress = new ReferencesProgress(client, token, 120);
        progress.begin();
        for (int i = 0; i < 120; i++) {
            progress.fileDone();
        }
        progress.end(null);

        List<WorkDoneProgressKind> kinds = client.kinds();
        assertEquals(WorkDoneProgressKind.begin, kinds.get(0));
        assertTrue(kinds.contains(WorkDoneProgressKind.report),
                () -> "expected report notifications, got: " + kinds);
        assertEquals(WorkDoneProgressKind.end, kinds.get(kinds.size() - 1));

        List<WorkDoneProgressReport> reports = client.reports();
        assertTrue(reports.stream().anyMatch(r -> r.getMessage() != null
                        && r.getMessage().contains("50 of 120")),
                () -> "expected a report around 50 files, got: " + reports);
        assertTrue(reports.stream().anyMatch(r -> r.getMessage() != null
                        && r.getMessage().contains("120 of 120")),
                () -> "expected final 120/120 report, got: " + reports);
    }

    @Test
    void noopsWithoutToken() {
        RecordingClient client = new RecordingClient();
        ReferencesProgress progress = new ReferencesProgress(client, null, 100);
        progress.begin();
        for (int i = 0; i < 100; i++) {
            progress.fileDone();
        }
        progress.end("Cancelled");
        assertTrue(client.params.isEmpty());
    }

    @Test
    void endWithCancelledMessage() {
        RecordingClient client = new RecordingClient();
        ReferencesProgress progress = new ReferencesProgress(client, Either.forLeft("t"), 10);
        progress.begin();
        progress.end("Cancelled");
        WorkDoneProgressEnd end = assertInstanceOf(WorkDoneProgressEnd.class,
                client.params.get(client.params.size() - 1).getValue().getLeft());
        assertEquals("Cancelled", end.getMessage());
    }

    private static final class RecordingClient implements LanguageClient {
        final List<ProgressParams> params = new ArrayList<>();

        List<WorkDoneProgressKind> kinds() {
            List<WorkDoneProgressKind> kinds = new ArrayList<>();
            for (ProgressParams p : params) {
                WorkDoneProgressNotification n = p.getValue().getLeft();
                kinds.add(n.getKind());
            }
            return kinds;
        }

        List<WorkDoneProgressReport> reports() {
            List<WorkDoneProgressReport> reports = new ArrayList<>();
            for (ProgressParams p : params) {
                WorkDoneProgressNotification n = p.getValue().getLeft();
                if (n instanceof WorkDoneProgressReport report) {
                    reports.add(report);
                }
            }
            return reports;
        }

        @Override
        public void notifyProgress(ProgressParams parameter) {
            params.add(parameter);
        }

        @Override
        public void telemetryEvent(Object object) {}

        @Override
        public void publishDiagnostics(PublishDiagnosticsParams diagnostics) {}

        @Override
        public void showMessage(MessageParams messageParams) {}

        @Override
        public CompletableFuture<MessageActionItem> showMessageRequest(ShowMessageRequestParams requestParams) {
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public void logMessage(MessageParams message) {}
    }
}
