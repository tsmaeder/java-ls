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
import org.eclipse.lsp4j.WorkDoneProgressKind;
import org.eclipse.lsp4j.WorkDoneProgressNotification;
import org.eclipse.lsp4j.WorkDoneProgressReport;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.eclipse.lsp4j.services.LanguageClient;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IndexingProgressTest {

    @Test
    void reportsEveryHundredFilesWithSourceUriFormat() {
        RecordingClient client = new RecordingClient();
        Either<String, Integer> token = Either.forLeft("index-1");
        IndexingProgress progress = new IndexingProgress(client, token);
        progress.begin();

        String uri = "file:///lib/foo.jar";
        for (int i = 1; i <= 250; i++) {
            progress.fileIndexed(uri, 3, 10, i);
        }
        progress.end(null);

        List<WorkDoneProgressKind> kinds = client.kinds();
        assertEquals(WorkDoneProgressKind.begin, kinds.get(0));
        assertEquals(WorkDoneProgressKind.end, kinds.get(kinds.size() - 1));

        WorkDoneProgressBegin begin = assertInstanceOf(WorkDoneProgressBegin.class,
                client.params.get(0).getValue().getLeft());
        assertEquals("Indexing", begin.getTitle());
        assertFalse(Boolean.TRUE.equals(begin.getCancellable()));

        List<WorkDoneProgressReport> reports = client.reports();
        assertEquals(2, reports.size(), () -> "expected reports at 100 and 200, got: " + reports);
        assertEquals(IndexingProgress.message(uri, 3, 10, 100), reports.get(0).getMessage());
        assertEquals(IndexingProgress.message(uri, 3, 10, 200), reports.get(1).getMessage());
        assertEquals("file:///lib/foo.jar (3 of 10)100", reports.get(0).getMessage());
        assertFalse(Boolean.TRUE.equals(reports.get(0).getCancellable()));
    }

    @Test
    void noopsWithoutToken() {
        RecordingClient client = new RecordingClient();
        IndexingProgress progress = new IndexingProgress(client, null);
        progress.begin();
        progress.fileIndexed("file:///x", 1, 1, 100);
        progress.end(null);
        assertTrue(client.params.isEmpty());
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
