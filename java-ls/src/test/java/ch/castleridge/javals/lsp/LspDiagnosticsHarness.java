package ch.castleridge.javals.lsp;

import java.io.IOException;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.eclipse.lsp4j.Diagnostic;
import org.eclipse.lsp4j.DidOpenTextDocumentParams;
import org.eclipse.lsp4j.InitializeParams;
import org.eclipse.lsp4j.InitializedParams;
import org.eclipse.lsp4j.MessageActionItem;
import org.eclipse.lsp4j.MessageParams;
import org.eclipse.lsp4j.PublishDiagnosticsParams;
import org.eclipse.lsp4j.ShowMessageRequestParams;
import org.eclipse.lsp4j.TextDocumentItem;
import org.eclipse.lsp4j.WorkspaceFolder;
import org.eclipse.lsp4j.jsonrpc.Launcher;
import org.eclipse.lsp4j.launch.LSPLauncher;
import org.eclipse.lsp4j.services.LanguageClient;
import org.eclipse.lsp4j.services.LanguageServer;

import ch.castleridge.javals.App;
import ch.castleridge.javals.indexing.index.UriCoding;

/**
 * In-process LSP harness: boots {@link App#startServer} over piped streams,
 * drives initialize / didOpen, and blocks until the server publishes diagnostics.
 */
public final class LspDiagnosticsHarness implements AutoCloseable {

    private static final Duration DEFAULT_INIT_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration DEFAULT_DIAGNOSTICS_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration DEFAULT_INDEX_TIMEOUT = Duration.ofSeconds(120);

    private final LanguageServer server;
    private final CapturingClient capturingClient;
    private final Future<?> clientListening;
    private final Thread serverThread;
    private final PipedOutputStream clientToServer;
    private final PipedOutputStream serverToClient;
    private boolean closed;

    private LspDiagnosticsHarness(LanguageServer server,
                                  CapturingClient capturingClient,
                                  Future<?> clientListening,
                                  Thread serverThread,
                                  PipedOutputStream clientToServer,
                                  PipedOutputStream serverToClient) {
        this.server = server;
        this.capturingClient = capturingClient;
        this.clientListening = clientListening;
        this.serverThread = serverThread;
        this.clientToServer = clientToServer;
        this.serverToClient = serverToClient;
    }

    public static LspDiagnosticsHarness start(Path workspaceRoot) throws Exception {
        return start(workspaceRoot, DEFAULT_INIT_TIMEOUT);
    }

    public static LspDiagnosticsHarness start(Path workspaceRoot, Duration initTimeout) throws Exception {
        Path absolute = workspaceRoot.toAbsolutePath().normalize();

        PipedInputStream serverIn = new PipedInputStream();
        PipedOutputStream clientToServer = new PipedOutputStream(serverIn);

        PipedInputStream clientFromServer = new PipedInputStream();
        PipedOutputStream serverToClient = new PipedOutputStream(clientFromServer);

        CapturingClient capturingClient = new CapturingClient();

        Launcher<LanguageServer> clientLauncher = LSPLauncher.createClientLauncher(
                capturingClient,
                clientFromServer,
                clientToServer);
        LanguageServer remoteServer = clientLauncher.getRemoteProxy();
        Future<?> clientListening = clientLauncher.startListening();

        Thread serverThread = new Thread(() -> {
            try {
                App.startServer(serverIn, serverToClient);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (ExecutionException e) {
                throw new RuntimeException("Language server failed", e.getCause());
            }
        }, "lsp-server");
        serverThread.setDaemon(true);
        serverThread.start();

        InitializeParams init = new InitializeParams();
        WorkspaceFolder folder = new WorkspaceFolder();
        folder.setUri(absolute.toUri().toString());
        folder.setName(absolute.getFileName() != null ? absolute.getFileName().toString() : "workspace");
        init.setWorkspaceFolders(List.of(folder));

        Map<String, Object> initOptions = new HashMap<>();
        initOptions.put("workspacePath", absolute.toString());
        init.setInitializationOptions(initOptions);

        remoteServer.initialize(init).get(initTimeout.toMillis(), TimeUnit.MILLISECONDS);
        remoteServer.initialized(new InitializedParams());

        return new LspDiagnosticsHarness(
                remoteServer,
                capturingClient,
                clientListening,
                serverThread,
                clientToServer,
                serverToClient);
    }

    public void awaitIndexReady() throws Exception {
        awaitIndexReady(DEFAULT_INDEX_TIMEOUT);
    }

    public void awaitIndexReady(Duration timeout) throws Exception {
        capturingClient.awaitIndexReady(timeout);
    }

    public List<Diagnostic> openAndAwaitDiagnostics(Path file) throws Exception {
        return openAndAwaitDiagnostics(file, DEFAULT_DIAGNOSTICS_TIMEOUT);
    }

    public List<Diagnostic> openAndAwaitDiagnostics(Path file, Duration timeout) throws Exception {
        Path absolute = file.toAbsolutePath().normalize();
        String text = Files.readString(absolute);
        return openAndAwaitDiagnostics(absolute.toUri(), text, timeout);
    }

    public List<Diagnostic> openAndAwaitDiagnostics(URI uri, String text) throws Exception {
        return openAndAwaitDiagnostics(uri, text, DEFAULT_DIAGNOSTICS_TIMEOUT);
    }

    public List<Diagnostic> openAndAwaitDiagnostics(URI uri, String text, Duration timeout) throws Exception {
        String uriString = uri.toString();
        String decodedUri = UriCoding.decode(uriString);

        CompletableFuture<PublishDiagnosticsParams> future = capturingClient.prepareDiagnosticsFuture(decodedUri);

        TextDocumentItem item = new TextDocumentItem(uriString, "java", 1, text);
        server.getTextDocumentService().didOpen(new DidOpenTextDocumentParams(item));

        PublishDiagnosticsParams params = future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        List<Diagnostic> diagnostics = params.getDiagnostics();
        return diagnostics == null ? List.of() : List.copyOf(diagnostics);
    }

    public List<String> logMessages() {
        return capturingClient.logMessages();
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        try {
            server.shutdown().get(5, TimeUnit.SECONDS);
        } catch (Exception ignored) {
            // Best effort; stream close below ends the JSON-RPC session.
        }
        try {
            clientToServer.close();
        } catch (IOException ignored) {
        }
        try {
            serverToClient.close();
        } catch (IOException ignored) {
        }
        try {
            clientListening.get(5, TimeUnit.SECONDS);
        } catch (Exception ignored) {
        }
        try {
            serverThread.join(5_000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        // Do not call server.exit() — JavaLanguageServer.exit() invokes System.exit().
    }

    static final class CapturingClient implements LanguageClient {

        private final ConcurrentHashMap<String, CompletableFuture<PublishDiagnosticsParams>> diagnosticsByUri =
                new ConcurrentHashMap<>();
        private final CompletableFuture<Void> indexReady = new CompletableFuture<>();
        private final List<String> logMessages = new ArrayList<>();

        CompletableFuture<PublishDiagnosticsParams> prepareDiagnosticsFuture(String decodedUri) {
            CompletableFuture<PublishDiagnosticsParams> future = new CompletableFuture<>();
            diagnosticsByUri.put(decodedUri, future);
            return future;
        }

        void awaitIndexReady(Duration timeout) throws TimeoutException, ExecutionException, InterruptedException {
            indexReady.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        }

        List<String> logMessages() {
            synchronized (logMessages) {
                return List.copyOf(logMessages);
            }
        }

        @Override
        public void publishDiagnostics(PublishDiagnosticsParams diagnostics) {
            String decoded = UriCoding.decode(diagnostics.getUri());
            CompletableFuture<PublishDiagnosticsParams> future = diagnosticsByUri.get(decoded);
            if (future != null) {
                future.complete(diagnostics);
            }
        }

        @Override
        public void logMessage(MessageParams message) {
            String text = message == null ? "" : message.getMessage();
            synchronized (logMessages) {
                logMessages.add(text);
            }
            if (isIndexReadyMessage(text)) {
                indexReady.complete(null);
            }
        }

        private static boolean isIndexReadyMessage(String message) {
            if (message == null || message.isBlank()) {
                return false;
            }
            return message.contains("index disabled")
                    || (message.contains("Indexed ") && message.contains(" types"))
                    || message.contains("mbt.json contained no input sources");
        }

        @Override
        public void telemetryEvent(Object object) {}

        @Override
        public void showMessage(MessageParams messageParams) {}

        @Override
        public CompletableFuture<MessageActionItem> showMessageRequest(ShowMessageRequestParams requestParams) {
            return CompletableFuture.completedFuture(null);
        }
    }
}
