package ch.castleridge.javals;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.lsp4j.InitializeParams;
import org.eclipse.lsp4j.MessageType;
import org.eclipse.lsp4j.WorkspaceFolder;

import ch.castleridge.javals.indexing.index.Index;
import ch.castleridge.javals.indexing.mbt.MbtInfo;
import ch.castleridge.javals.indexing.mbt.MbtJson;
import ch.castleridge.javals.indexing.scan.InputSource;
import ch.castleridge.javals.indexing.scan.Scanner;
import ch.castleridge.javals.javac.ClasspathOrder;

/**
 * Bootstraps the workspace {@link Index} by locating an {@code mbt.json}
 * in one of the workspace folders and asynchronously running the
 * {@link Scanner} over the {@link InputSource}s it describes.
 *
 * <p>While the scan is running both {@link #index()} and
 * {@link #classpath()} return {@link Optional#empty()}; callers that need
 * the index should treat that as "not yet ready" and skip cross-file
 * resolution. Once the future completes the references are published
 * atomically.
 */
public final class IndexService {

    private final JavaLanguageServer server;
    private final AtomicReference<State> state = new AtomicReference<>(State.empty());

    public IndexService(JavaLanguageServer server) {
        this.server = server;
    }

    public Optional<Index> index() {
        Index i = state.get().index;
        return Optional.ofNullable(i);
    }

    public Optional<ClasspathOrder> classpath() {
        ClasspathOrder cp = state.get().classpath;
        return Optional.ofNullable(cp);
    }

    /**
     * Look for an {@code mbt.json} under any of the workspace folders
     * declared in {@code params} (falling back to the deprecated
     * {@code rootUri}). If found, kick off a background scan and publish
     * the resulting index/classpath atomically. Returns the future for
     * tests; production callers can ignore it.
     */
    public CompletableFuture<Void> initialize(InitializeParams params) {
        List<Path> roots = workspaceRoots(params);
        Path mbt = findMbtJson(roots);
        if (mbt == null) {
            log(MessageType.Info, "No mbt.json found in workspace roots; index disabled");
            return CompletableFuture.completedFuture(null);
        }
        log(MessageType.Info, "Loading mbt.json: " + mbt);
        return CompletableFuture.runAsync(() -> loadFrom(mbt));
    }

    private void loadFrom(Path mbt) {
        try {
            MbtInfo info = MbtJson.read(mbt);
            List<InputSource> sources = MbtJson.toInputSources(info);
            if (sources.isEmpty()) {
                log(MessageType.Warning, "mbt.json contained no input sources: " + mbt);
                return;
            }
            Index index = new Index();
            Scanner scanner = new Scanner();
            long t0 = System.nanoTime();
            List<Throwable> failures = scanner.scanAll(sources, index);
            long elapsedMs = (System.nanoTime() - t0) / 1_000_000L;
            ClasspathOrder cp = ClasspathOrder.ofSources(sources);
            state.set(new State(index, cp));
            log(MessageType.Info, "Indexed " + index.size() + " types ("
                    + index.entryCount() + " entries) from " + sources.size()
                    + " sources in " + elapsedMs + " ms"
                    + (failures.isEmpty() ? "" : "; " + failures.size() + " failures"));
        } catch (IOException e) {
            log(MessageType.Error, "Failed to load mbt.json " + mbt + ": " + e.getMessage());
        } catch (RuntimeException e) {
            log(MessageType.Error, "Indexing failed for " + mbt + ": " + e.getMessage());
        }
    }

    private static List<Path> workspaceRoots(InitializeParams params) {
        List<Path> roots = new ArrayList<>();
        if (params == null) return roots;
        List<WorkspaceFolder> folders = params.getWorkspaceFolders();
        if (folders != null) {
            for (WorkspaceFolder f : folders) {
                Path p = uriToPath(f.getUri());
                if (p != null) roots.add(p);
            }
        }
        if (roots.isEmpty()) {
            @SuppressWarnings("deprecation")
            String rootUri = params.getRootUri();
            Path p = uriToPath(rootUri);
            if (p != null) roots.add(p);
            if (p == null) {
                @SuppressWarnings("deprecation")
                String rootPath = params.getRootPath();
                if (rootPath != null && !rootPath.isBlank()) {
                    roots.add(Paths.get(rootPath));
                }
            }
        }
        return roots;
    }

    private static Path uriToPath(String uri) {
        if (uri == null || uri.isBlank()) return null;
        try {
            return Paths.get(URI.create(uri));
        } catch (IllegalArgumentException | java.nio.file.FileSystemNotFoundException e) {
            return null;
        }
    }

    private static Path findMbtJson(List<Path> roots) {
        for (Path root : roots) {
            Path candidate = root.resolve("mbt.json");
            if (Files.isRegularFile(candidate)) return candidate;
        }
        return null;
    }

    private void log(MessageType type, String message) {
        if (server != null) {
            server.logMessage(type, message);
        }
    }

    private record State(Index index, ClasspathOrder classpath) {
        static State empty() {
            return new State(null, null);
        }
    }
}
