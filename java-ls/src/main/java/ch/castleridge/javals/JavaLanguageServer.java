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

import org.eclipse.lsp4j.*;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.eclipse.lsp4j.services.*;

import ch.castleridge.javals.indexing.index.UriCoding;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Path;
/**
 * Main Language Server implementation for Java LSP
 */
public class JavaLanguageServer implements LanguageServer, LanguageClientAware {
    
    private static final long MBT_RELOAD_DEBOUNCE_MS = 400L;

    private final TextDocumentService textDocumentService;
    private final WorkspaceService workspaceService;
    private final IndexService indexService;
    private final MbtService mbtService;
    private final WorkspaceBootstrap workspaceBootstrap;
    private LanguageClient client;
    private int errorCode = 1;
    private volatile String compilerBackend = "javac";
    private volatile boolean watchedFilesDynamicRegistration;
    private volatile boolean workDoneProgressSupported;
    private final Map<Object, AtomicBoolean> progressCancels = new ConcurrentHashMap<>();

    private volatile List<Path> workspaceRoots = List.of();
    private volatile Path workspacePath;
    private volatile String sourceWatcherRegistrationId;
    private volatile String mbtWatcherRegistrationId;
    private final AtomicLong mbtReloadGeneration = new AtomicLong();
    private final ScheduledExecutorService mbtReloadScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "java-ls-mbt-reload");
        t.setDaemon(true);
        return t;
    });

    public JavaLanguageServer() {
        this.indexService = new IndexService(this);
        this.mbtService = new MbtService(this);
        this.workspaceBootstrap = new WorkspaceBootstrap();
        this.textDocumentService = new JavaTextDocumentService(this, indexService, mbtService);
        this.workspaceService = new JavaWorkspaceService(this);
        indexService.addIndexChangedListener(this::rebindWorkspaceCompiler);
    }

    private void rebindWorkspaceCompiler() {
        JavaTextDocumentService tds = (JavaTextDocumentService) textDocumentService;
        tds.setWorkspaceCompiler(ch.castleridge.javals.analysis.BackendFactory.rebind(
                tds.workspaceCompiler(),
                compilerBackend,
                tds.javacLocator(),
                tds.ecjLocator(),
                mbtService.sourceJarByBinaryJar(),
                mbtService));
    }

    public IndexService getIndexService() {
        return indexService;
    }

    public MbtService getMbtService() {
        return mbtService;
    }

    /**
     * Applies reference-search settings from an options root (initialize options or
     * unwrapped {@code didChangeConfiguration} settings).
     *
     * @param updateOnly when {@code true}, only keys present in {@code optionsRoot} are
     *        applied (partial config updates); when {@code false}, absent
     *        {@code references} applies {@link InitializationOptions.References#DEFAULT}
     */
    void applyReferencesFromOptions(Object optionsRoot, boolean updateOnly) {
        JavaTextDocumentService documents = (JavaTextDocumentService) textDocumentService;
        var references = InitializationOptions.referencesFromOptions(optionsRoot);
        if (references.isPresent()) {
            InitializationOptions.References refs = references.get();
            documents.setReferenceSearchScope(refs.scope());
            logMessage(MessageType.Info,
                    "References: inJars=" + refs.inJars() + ", inJdk=" + refs.inJdk());
        } else if (!updateOnly) {
            documents.setReferenceSearchScope(InitializationOptions.References.DEFAULT.scope());
            logMessage(MessageType.Info,
                    "References: inJars=" + InitializationOptions.References.DEFAULT.inJars()
                            + ", inJdk=" + InitializationOptions.References.DEFAULT.inJdk());
        }
        InitializationOptions.referencesCandidateCap(optionsRoot)
                .ifPresent(documents::setReferencesCandidateCap);
    }

    /**
     * Hot-reloads reference-search settings from {@code workspace/didChangeConfiguration}.
     */
    void applyConfiguration(Object settings) {
        applyReferencesFromOptions(
                InitializationOptions.optionsRootFromDidChangeSettings(settings), true);
    }

    @Override
    public CompletableFuture<InitializeResult> initialize(InitializeParams params) {
        InitializationOptions.Backend backend = InitializationOptions.backend(params);
        this.compilerBackend = backend.compiler();
        this.watchedFilesDynamicRegistration = supportsWatchedFilesDynamicRegistration(params);
        this.workDoneProgressSupported = supportsWorkDoneProgress(params);
        indexService.setSourceIndexer(
                ch.castleridge.javals.indexing.source.SourceIndexer.of(backend.sourceIndexer()));
        indexService.setBytecodeIndexer(
                ch.castleridge.javals.indexing.bytecode.BytecodeIndexer.of(backend.classIndexer()));
        rebindWorkspaceCompiler();
        logMessage(MessageType.Info,
                "Backend: sourceIndexer=" + backend.sourceIndexer()
                        + ", classIndexer=" + backend.classIndexer()
                        + ", compiler=" + backend.compiler());

        List<Path> roots = WorkspaceBootstrap.workspaceRoots(params);
        this.workspaceRoots = List.copyOf(roots);

        CompletableFuture.runAsync(() -> {
            workspaceBootstrap.prepare(params, this::logMessage).ifPresent(p -> {
                this.workspacePath = p.workspace();
                applyPreparedWorkspace(p, true);
            });
        });
        applyReferencesFromOptions(params.getInitializationOptions(), false);

        // Set up server capabilities
        ServerCapabilities capabilities = new ServerCapabilities();
        
        // Text document sync
        capabilities.setTextDocumentSync(TextDocumentSyncKind.Full);
        
        // Completion support
        CompletionOptions completionOptions = new CompletionOptions();
        completionOptions.setResolveProvider(true);
        completionOptions.setTriggerCharacters(java.util.Arrays.asList(".", "@"));
        capabilities.setCompletionProvider(completionOptions);
        
        // Hover support
        capabilities.setHoverProvider(true);
        
        // Definition support
        capabilities.setDefinitionProvider(true);
        
        // References support (cancellable workDoneProgress)
        ReferenceOptions referenceOptions = new ReferenceOptions();
        referenceOptions.setWorkDoneProgress(true);
        capabilities.setReferencesProvider(referenceOptions);

        // Type hierarchy (prepare + subtypes + supertypes)
        capabilities.setTypeHierarchyProvider(true);

        // Call hierarchy (prepare + incoming + outgoing)
        capabilities.setCallHierarchyProvider(true);
        
        // Document symbol support
        capabilities.setDocumentSymbolProvider(true);
        
        // Workspace symbol support
        capabilities.setWorkspaceSymbolProvider(true);
        
        // Code action support
        capabilities.setCodeActionProvider(true);
        
        // Document formatting
        capabilities.setDocumentFormattingProvider(true);
        capabilities.setDocumentRangeFormattingProvider(true);
        
        // Rename support
        RenameOptions renameOptions = new RenameOptions();
        renameOptions.setPrepareProvider(true);
        capabilities.setRenameProvider(renameOptions);
        
        // Signature help
        SignatureHelpOptions signatureHelpOptions = new SignatureHelpOptions();
        signatureHelpOptions.setTriggerCharacters(java.util.Arrays.asList("(", ","));
        capabilities.setSignatureHelpProvider(signatureHelpOptions);

        InitializeResult result = new InitializeResult(capabilities);
        return CompletableFuture.completedFuture(result);
    }

    /**
     * Load mbt state, refresh ECJ, index (cold) or apply input delta (reload),
     * then (re)register file watchers.
     */
    void applyPreparedWorkspace(WorkspaceBootstrap.PreparedWorkspace prepared, boolean coldStart) {
        if (prepared == null) return;
        mbtService.loadFrom(prepared.mbtJson(), prepared.workspace());
        refreshCompilerAfterMbtLoad();
        if (!mbtService.inputSources().isEmpty()) {
            if (coldStart) {
                indexService.index(mbtService.inputSources(), mbtService.sourceRoots());
            } else {
                indexService.applyInputDelta(mbtService.inputSources(), mbtService.sourceRoots());
            }
        }
        registerWatchers();
    }

    private void refreshCompilerAfterMbtLoad() {
        ch.castleridge.javals.analysis.WorkspaceCompiler current =
                ((JavaTextDocumentService) textDocumentService).workspaceCompiler();
        if (current instanceof ch.castleridge.javals.analysis.ecj.EcjWorkspaceCompiler ecj) {
            ecj.clearEnvironments();
            ecj.setMbtService(mbtService);
            ecj.setSourceJarByBinaryJar(mbtService.sourceJarByBinaryJar());
        } else {
            rebindWorkspaceCompiler();
        }
    }

    /**
     * Debounced mbt reload triggered by watched-file events for mbt candidates.
     */
    void scheduleMbtReload() {
        long gen = mbtReloadGeneration.incrementAndGet();
        mbtReloadScheduler.schedule(() -> {
            if (gen != mbtReloadGeneration.get()) {
                return;
            }
            reloadMbt();
        }, MBT_RELOAD_DEBOUNCE_MS, TimeUnit.MILLISECONDS);
    }

    /**
     * Relocate mbt without re-importing, then apply an incremental index update.
     * Package-visible for tests.
     */
    void reloadMbt() {
        List<Path> roots = workspaceRoots;
        if (roots == null || roots.isEmpty()) {
            logMessage(MessageType.Warning, "Cannot reload mbt.json: no workspace roots");
            return;
        }
        var relocated = workspaceBootstrap.relocate(roots, workspacePath);
        if (relocated.isEmpty()) {
            logMessage(MessageType.Info, "No mbt.json found after change; keeping prior index");
            return;
        }
        WorkspaceBootstrap.PreparedWorkspace prepared = relocated.get();
        this.workspacePath = prepared.workspace();
        logMessage(MessageType.Info, "Reloading mbt.json: " + prepared.mbtJson());
        applyPreparedWorkspace(prepared, false);
    }

    /** True when {@code uri} is a watched mbt consumer path under known roots. */
    boolean isMbtCandidateUri(String uri) {
        if (uri == null || uri.isBlank()) return false;
        Path file = ClientPaths.fromClientString(UriCoding.decode(uri));
        return WorkspaceBootstrap.isMbtCandidate(file, workspaceRoots);
    }

    private void registerWatchers() {
        if (!watchedFilesDynamicRegistration || client == null) {
            return;
        }
        unregisterWatchers();
        registerMbtFileWatchers();
        registerSourceFileWatchers(mbtService.sourceRootUris());
    }

    private void unregisterWatchers() {
        if (client == null) return;
        List<Unregistration> unregs = new ArrayList<>(2);
        if (sourceWatcherRegistrationId != null) {
            unregs.add(new Unregistration(sourceWatcherRegistrationId, "workspace/didChangeWatchedFiles"));
            sourceWatcherRegistrationId = null;
        }
        if (mbtWatcherRegistrationId != null) {
            unregs.add(new Unregistration(mbtWatcherRegistrationId, "workspace/didChangeWatchedFiles"));
            mbtWatcherRegistrationId = null;
        }
        if (unregs.isEmpty()) return;
        try {
            client.unregisterCapability(new UnregistrationParams(unregs));
        } catch (RuntimeException e) {
            logMessage(MessageType.Warning,
                    "Could not unregister file watchers: " + e.getMessage());
        }
    }

    /**
     * Dynamically register {@code workspace/didChangeWatchedFiles} watchers for
     * mbt consumer files under each workspace root.
     */
    void registerMbtFileWatchers() {
        if (!watchedFilesDynamicRegistration || client == null
                || workspaceRoots == null || workspaceRoots.isEmpty()) {
            return;
        }
        List<FileSystemWatcher> watchers = new ArrayList<>();
        int kind = WatchKind.Create + WatchKind.Change + WatchKind.Delete;
        for (Path root : workspaceRoots) {
            if (root == null) continue;
            String rootUri = root.toUri().toString();
            watchers.add(new FileSystemWatcher(
                    Either.forRight(new RelativePattern(Either.forRight(rootUri), "mbt.json")), kind));
            watchers.add(new FileSystemWatcher(
                    Either.forRight(new RelativePattern(Either.forRight(rootUri), ".javals/mbt.json")), kind));
            watchers.add(new FileSystemWatcher(
                    Either.forRight(new RelativePattern(Either.forRight(rootUri), ".metals/mbt.json")), kind));
        }
        if (watchers.isEmpty()) return;
        String id = UUID.randomUUID().toString();
        Registration registration = new Registration(
                id,
                "workspace/didChangeWatchedFiles",
                new DidChangeWatchedFilesRegistrationOptions(watchers));
        try {
            client.registerCapability(new RegistrationParams(List.of(registration)));
            mbtWatcherRegistrationId = id;
            logMessage(MessageType.Info,
                    "Registered mbt.json watchers for " + workspaceRoots.size() + " workspace root(s)");
        } catch (RuntimeException e) {
            logMessage(MessageType.Warning,
                    "Could not register mbt.json watchers: " + e.getMessage());
        }
    }

    /**
     * Dynamically register {@code workspace/didChangeWatchedFiles} watchers for
     * {@code **}/{@code *.java} under each mbt source root, when the client supports it.
     */
    public void registerSourceFileWatchers(List<String> sourceRootUris) {
        if (!watchedFilesDynamicRegistration || client == null
                || sourceRootUris == null || sourceRootUris.isEmpty()) {
            return;
        }
        List<FileSystemWatcher> watchers = new ArrayList<>(sourceRootUris.size());
        int kind = WatchKind.Create + WatchKind.Change + WatchKind.Delete;
        for (String sourceUri : sourceRootUris) {
            if (sourceUri == null || sourceUri.isBlank()) continue;
            RelativePattern pattern = new RelativePattern(Either.forRight(sourceUri), "**/*.java");
            watchers.add(new FileSystemWatcher(Either.forRight(pattern), kind));
        }
        if (watchers.isEmpty()) return;
        String id = UUID.randomUUID().toString();
        DidChangeWatchedFilesRegistrationOptions options =
                new DidChangeWatchedFilesRegistrationOptions(watchers);
        Registration registration = new Registration(
                id,
                "workspace/didChangeWatchedFiles",
                options);
        try {
            client.registerCapability(new RegistrationParams(List.of(registration)));
            sourceWatcherRegistrationId = id;
            logMessage(MessageType.Info,
                    "Registered file watchers for " + watchers.size() + " source root(s)");
        } catch (RuntimeException e) {
            logMessage(MessageType.Warning,
                    "Could not register source file watchers: " + e.getMessage());
        }
    }

    private static boolean supportsWatchedFilesDynamicRegistration(InitializeParams params) {
        if (params == null || params.getCapabilities() == null) return false;
        WorkspaceClientCapabilities workspace = params.getCapabilities().getWorkspace();
        if (workspace == null || workspace.getDidChangeWatchedFiles() == null) return false;
        return Boolean.TRUE.equals(workspace.getDidChangeWatchedFiles().getDynamicRegistration());
    }

    private static boolean supportsWorkDoneProgress(InitializeParams params) {
        if (params == null || params.getCapabilities() == null) return false;
        WindowClientCapabilities window = params.getCapabilities().getWindow();
        if (window == null) return false;
        return Boolean.TRUE.equals(window.getWorkDoneProgress());
    }

    /** Whether the client advertised {@code window.workDoneProgress} at initialize. */
    boolean supportsWorkDoneProgress() {
        return workDoneProgressSupported;
    }

    void registerProgressCancel(Either<String, Integer> token, AtomicBoolean cancel) {
        Object key = progressCancelKey(token);
        if (key != null && cancel != null) {
            progressCancels.put(key, cancel);
        }
    }

    void unregisterProgressCancel(Either<String, Integer> token) {
        Object key = progressCancelKey(token);
        if (key != null) {
            progressCancels.remove(key);
        }
    }

    @Override
    public void cancelProgress(WorkDoneProgressCancelParams params) {
        if (params == null) return;
        Object key = progressCancelKey(params.getToken());
        if (key == null) return;
        AtomicBoolean cancel = progressCancels.get(key);
        if (cancel != null) {
            cancel.set(true);
        }
    }

    private static Object progressCancelKey(Either<String, Integer> token) {
        if (token == null) return null;
        if (token.isLeft()) return token.getLeft();
        if (token.isRight()) return token.getRight();
        return null;
    }

    @Override
    public CompletableFuture<Object> shutdown() {
        errorCode = 0;
        mbtReloadScheduler.shutdownNow();
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public void exit() {
        System.exit(errorCode);
    }

    @Override
    public TextDocumentService getTextDocumentService() {
        return textDocumentService;
    }

    @Override
    public WorkspaceService getWorkspaceService() {
        return workspaceService;
    }

    @Override
    public void connect(LanguageClient client) {
        this.client = client;
    }

    public LanguageClient getClient() {
        return client;
    }

    public void logMessage(MessageType type, String message) {
        if (client != null) {
            MessageParams params = new MessageParams(type, message);
            client.logMessage(params);
        }
    }

    public void logException(Throwable e) {
        if (client != null) {
            StringWriter stringWriter = new StringWriter();
            PrintWriter printWriter = new PrintWriter(stringWriter);
            e.printStackTrace(printWriter);
            printWriter.close();
            String stackTrace = stringWriter.toString();
            MessageParams params = new MessageParams(MessageType.Error, e.getMessage() + "\n" + stackTrace);
            client.logMessage(params);
        }
    }

    /** Package-visible for tests that drive reload without LSP initialize. */
    void setWorkspaceRootsForTest(List<Path> roots, Path workspace) {
        this.workspaceRoots = roots == null ? List.of() : List.copyOf(roots);
        this.workspacePath = workspace;
    }
}
