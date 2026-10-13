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

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

import ch.castleridge.javals.indexing.cli.HeapSizeEstimator;
import ch.castleridge.javals.indexing.scan.DirInput;
import ch.castleridge.javals.indexing.scan.InputSource;
import ch.castleridge.javals.indexing.scan.JarInput;
import ch.castleridge.javals.indexing.scan.JrtInput;
import ch.castleridge.javals.indexing.scan.ScanCollector;
import ch.castleridge.javals.indexing.scan.ScanResult;
import ch.castleridge.javals.indexing.scan.ScanStats;
import ch.castleridge.javals.indexing.scan.Scanner;
import org.eclipse.lsp4j.FileChangeType;
import org.eclipse.lsp4j.FileEvent;
import org.eclipse.lsp4j.MessageType;

import ch.castleridge.javals.indexing.index.Index;
import ch.castleridge.javals.indexing.index.InMemoryIndex;
import ch.castleridge.javals.indexing.index.UriCoding;

/**
 * Manages the workspace {@link Index}: runs the {@link Scanner} over
 * provided {@link InputSource}s and applies watched-file updates.
 *
 * <p>MBT loading and classpath ownership live in {@link MbtService}.
 * While the scan is running {@link #index()} returns the live index,
 * which grows incrementally as each {@link InputSource} is merged.
 */
public final class IndexService {

    private final JavaLanguageServer server;
    private final AtomicReference<State> state = new AtomicReference<>(State.empty());
    private final List<Runnable> indexChangedListeners = new CopyOnWriteArrayList<>();
    private final ExecutorService watchExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "java-ls-index-watch");
        t.setDaemon(true);
        return t;
    });
    private volatile ch.castleridge.javals.indexing.source.SourceIndexer sourceIndexer =
            ch.castleridge.javals.indexing.source.SourceIndexer.javac();
    private volatile ch.castleridge.javals.indexing.bytecode.BytecodeIndexer bytecodeIndexer =
            ch.castleridge.javals.indexing.bytecode.BytecodeIndexer.asm();

    public IndexService(JavaLanguageServer server) {
        this.server = server;
    }

    public void setSourceIndexer(ch.castleridge.javals.indexing.source.SourceIndexer sourceIndexer) {
        this.sourceIndexer = sourceIndexer == null
                ? ch.castleridge.javals.indexing.source.SourceIndexer.javac()
                : sourceIndexer;
    }

    public void setBytecodeIndexer(ch.castleridge.javals.indexing.bytecode.BytecodeIndexer bytecodeIndexer) {
        this.bytecodeIndexer = bytecodeIndexer == null
                ? ch.castleridge.javals.indexing.bytecode.BytecodeIndexer.asm()
                : bytecodeIndexer;
    }

    public void addIndexChangedListener(Runnable listener) {
        indexChangedListeners.add(listener);
    }

    public Optional<Index> index() {
        Index i = state.get().index;
        return Optional.ofNullable(i);
    }

    /**
     * Apply LSP {@code workspace/didChangeWatchedFiles} events: reindex or
     * drop {@code .java} files that fall under a known source root.
     * Returns a future that completes when the batch has been applied.
     */
    public CompletableFuture<Void> onWatchedFilesChanged(List<FileEvent> changes) {
        if (changes == null || changes.isEmpty()) {
            return CompletableFuture.completedFuture(null);
        }
        if (state.get().index == null || state.get().sourceRoots.isEmpty()) {
            return CompletableFuture.completedFuture(null);
        }
        List<FileEvent> snapshot = List.copyOf(changes);
        return CompletableFuture.runAsync(() -> applyWatchedFiles(snapshot), watchExecutor);
    }

    private void applyWatchedFiles(List<FileEvent> changes) {
        State current = state.get();
        Index index = current.index;
        if (index == null || current.sourceRoots.isEmpty()) return;

        for (FileEvent event : changes) {
            if (event == null || event.getUri() == null) continue;
            try {
                applyOneWatchedFile(index, current.sourceRoots, event);
            } catch (RuntimeException e) {
                log(MessageType.Error, "Failed to update index for " + event.getUri() + ": " + e.getMessage());
            }
        }
    }

    private void applyOneWatchedFile(Index index, List<SourceRoot> sourceRoots, FileEvent event) {
        Path file = ClientPaths.fromClientString(UriCoding.decode(event.getUri()));
        if (file == null) return;
        file = file.toAbsolutePath().normalize();
        String fileName = file.getFileName() == null ? "" : file.getFileName().toString();
        if (!fileName.endsWith(".java") || Index.isSkippedFileName(fileName)) return;

        ResolvedResource resolved = resolveUnderSourceRoots(file, sourceRoots);
        if (resolved == null) return;

        boolean delete = event.getType() == FileChangeType.Deleted || !Files.isRegularFile(file);
        if (delete) {
            index.putResource(resolved.sourceUri(), resolved.relativePath(), new InMemoryIndex());
            log(MessageType.Log, "Index removed " + resolved.relativePath());
            return;
        }

        String content;
        try {
            content = Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            log(MessageType.Warning, "Could not read " + file + " for reindex: " + e.getMessage());
            return;
        }
        InMemoryIndex replacement = new InMemoryIndex();
        sourceIndexer.index(resolved.relativePath(), resolved.sourceUri(), content, replacement);
        index.putResource(resolved.sourceUri(), resolved.relativePath(), replacement);
        log(MessageType.Log, "Index updated " + resolved.relativePath()
                + " (" + replacement.entryCount() + " types)");
    }

    static ResolvedResource resolveUnderSourceRoots(Path file, List<SourceRoot> sourceRoots) {
        if (file == null || sourceRoots == null || sourceRoots.isEmpty()) return null;
        Path abs = file.toAbsolutePath().normalize();
        SourceRoot best = null;
        for (SourceRoot root : sourceRoots) {
            if (abs.startsWith(root.path())
                    && (best == null || root.path().getNameCount() > best.path().getNameCount())) {
                best = root;
            }
        }
        if (best == null) return null;
        String relative = best.path().relativize(abs).toString().replace('\\', '/');
        if (relative.isEmpty() || relative.startsWith("..")) return null;
        return new ResolvedResource(best.sourceUri(), relative);
    }

    /**
     * Scan {@code sources} into a fresh workspace index and retain
     * {@code sourceRoots} for watched-file updates.
     */
    public void index(Collection<InputSource> sources, List<SourceRoot> sourceRoots) {
        if (sources == null || sources.isEmpty()) {
            log(MessageType.Warning, "No input sources to index");
            return;
        }
        try {
            Index index = new InMemoryIndex();
            index.addChangedListener(this::notifyIndexChanged);
            List<SourceRoot> roots = sourceRoots == null ? List.of() : List.copyOf(sourceRoots);
            Map<String, InputSource> byUri = inputsByUri(sources);
            Map<String, SourceFingerprint> fingerprints = fingerprintsOf(byUri.values());
            state.set(new State(index, roots, Map.copyOf(byUri), Map.copyOf(fingerprints)));
            notifyIndexChanged();
            IndexingProgress progress = IndexingProgress.open(server);
            progress.begin();
            ScanResult scan;
            ScanCollector collector = findScanCollector(sources);
            try {
                Scanner scanner = new Scanner(sourceIndexer, bytecodeIndexer);
                scan = scanner.scan(sources, index, progress::fileIndexed);
            } finally {
                // End before the "Indexed" ready log so awaitIndexReady cannot
                // return while a late progress end is still in flight.
                progress.end(null);
            }
            logScanResult(index, sources, scan, collector);
        } catch (RuntimeException e) {
            StringWriter writer = new StringWriter();
            e.printStackTrace(new PrintWriter(writer));
            log(MessageType.Error, "Indexing failed: " + writer);
        }
    }

    /**
     * Incrementally update the live index to match {@code newSources}:
     * drop removed inputs, scan added ones, rescan jars/JDK whose
     * fingerprint changed. Serialized with watched-file updates.
     * Falls back to {@link #index} when no index exists yet.
     */
    public void applyInputDelta(Collection<InputSource> newSources, List<SourceRoot> newRoots) {
        if (newSources == null || newSources.isEmpty()) {
            log(MessageType.Warning, "No input sources to index");
            return;
        }
        if (state.get().index == null) {
            index(newSources, newRoots);
            return;
        }
        try {
            CompletableFuture.runAsync(
                    () -> applyInputDeltaLocked(newSources, newRoots),
                    watchExecutor).join();
        } catch (RuntimeException e) {
            StringWriter writer = new StringWriter();
            e.printStackTrace(new PrintWriter(writer));
            log(MessageType.Error, "Incremental indexing failed: " + writer);
        }
    }

    private void applyInputDeltaLocked(Collection<InputSource> newSources, List<SourceRoot> newRoots) {
        State current = state.get();
        Index index = current.index;
        if (index == null) {
            index(newSources, newRoots);
            return;
        }

        Map<String, InputSource> oldByUri = current.inputsByUri;
        Map<String, InputSource> newByUri = inputsByUri(newSources);
        Map<String, SourceFingerprint> newFingerprints = fingerprintsOf(newByUri.values());

        List<String> dropped = new ArrayList<>();
        for (String uri : oldByUri.keySet()) {
            if (!newByUri.containsKey(uri)) {
                dropped.add(uri);
            }
        }
        List<InputSource> added = new ArrayList<>();
        List<InputSource> replaced = new ArrayList<>();
        for (Map.Entry<String, InputSource> e : newByUri.entrySet()) {
            String uri = e.getKey();
            InputSource src = e.getValue();
            if (!oldByUri.containsKey(uri)) {
                added.add(src);
                continue;
            }
            if (src instanceof DirInput) {
                continue;
            }
            SourceFingerprint prev = current.fingerprints.get(uri);
            SourceFingerprint next = newFingerprints.get(uri);
            if (prev == null || !prev.equals(next)) {
                replaced.add(src);
            }
        }

        for (String uri : dropped) {
            index.putSource(uri, new InMemoryIndex());
            log(MessageType.Log, "Index removed source " + uri);
        }
        for (InputSource src : replaced) {
            index.putSource(src.sourceUri(), new InMemoryIndex());
            log(MessageType.Log, "Index clearing changed source " + src.sourceUri());
        }

        List<InputSource> toScan = new ArrayList<>(added.size() + replaced.size());
        toScan.addAll(added);
        toScan.addAll(replaced);

        List<SourceRoot> roots = newRoots == null ? List.of() : List.copyOf(newRoots);
        state.set(new State(index, roots, Map.copyOf(newByUri), Map.copyOf(newFingerprints)));

        if (toScan.isEmpty()) {
            log(MessageType.Info, "Index inputs unchanged (" + newByUri.size() + " sources)");
            return;
        }

        IndexingProgress progress = IndexingProgress.open(server);
        progress.begin();
        ScanResult scan;
        ScanCollector collector = findScanCollector(toScan);
        try {
            Scanner scanner = new Scanner(sourceIndexer, bytecodeIndexer);
            scan = scanner.scan(toScan, index, progress::fileIndexed);
        } finally {
            progress.end(null);
        }
        log(MessageType.Info, "Index delta: dropped=" + dropped.size()
                + ", added=" + added.size()
                + ", replaced=" + replaced.size()
                + " in " + scan.elapsedMs() + " ms");
        logScanResult(index, toScan, scan, collector);
    }

    private void logScanResult(Index index, Collection<InputSource> sources, ScanResult scan,
                               ScanCollector collector) {
        List<Throwable> failures = scan.failures();
        ScanStats stats = collector == null ? new ScanStats(0, 0L) : collector.snapshot();

        int jarCount = 0;
        long jarBytes = 0L;
        for (InputSource src : sources) {
            if (src instanceof JarInput jarInput) {
                jarCount++;
                try {
                    jarBytes += Files.size(jarInput.jar());
                } catch (IOException ignored) {
                    // leave jarBytes unchanged for unreadable jars
                }
            }
        }

        log(MessageType.Info, "Indexed " + index.size() + " types ("
                + index.entryCount() + " entries) from " + sources.size()
                + " sources in " + scan.elapsedMs() + " ms"
                + " (class files: " + scan.classFilesMs() + " ms, source files: "
                + scan.sourceFilesMs() + " ms)"
                + (failures.isEmpty() ? "" : "; " + failures.size() + " failures"));
        log(MessageType.Info, "Index stats: " + stats.sourceFileCount() + " source files, "
                + jarCount + " jars (" + HeapSizeEstimator.formatBytes(jarBytes) + "); class files "
                + HeapSizeEstimator.formatBytes(stats.classFileBytes()));
        failures.forEach(f -> {
            StringWriter writer = new StringWriter();
            f.printStackTrace(new PrintWriter(writer));
            log(MessageType.Error, "Indexing failure: " + writer.toString());
        });
    }

    private static Map<String, InputSource> inputsByUri(Collection<InputSource> sources) {
        Map<String, InputSource> out = new LinkedHashMap<>();
        for (InputSource src : sources) {
            if (src == null || src.sourceUri() == null) continue;
            out.putIfAbsent(src.sourceUri(), src);
        }
        return out;
    }

    private static Map<String, SourceFingerprint> fingerprintsOf(Collection<InputSource> sources) {
        Map<String, SourceFingerprint> out = new HashMap<>();
        for (InputSource src : sources) {
            if (src == null || src.sourceUri() == null) continue;
            SourceFingerprint fp = fingerprint(src);
            if (fp != null) {
                out.put(src.sourceUri(), fp);
            }
        }
        return out;
    }

    static SourceFingerprint fingerprint(InputSource src) {
        if (src instanceof JarInput jar) {
            return fingerprintPath(jar.jar());
        }
        if (src instanceof JrtInput jrt) {
            Path release = jrt.javaHome.resolve("release");
            if (Files.isRegularFile(release)) {
                return fingerprintPath(release);
            }
            return fingerprintPath(jrt.javaHome);
        }
        return null;
    }

    private static SourceFingerprint fingerprintPath(Path path) {
        if (path == null) return null;
        try {
            BasicFileAttributes attrs = Files.readAttributes(path, BasicFileAttributes.class);
            long size = attrs.isDirectory() ? 0L : attrs.size();
            long mtime = attrs.lastModifiedTime().toMillis();
            return new SourceFingerprint(size, mtime);
        } catch (IOException e) {
            return null;
        }
    }

    private static ScanCollector findScanCollector(Collection<InputSource> sources) {
        for (InputSource src : sources) {
            if (src instanceof DirInput dir && dir.collector() != null) {
                return dir.collector();
            }
            if (src instanceof JarInput jar && jar.collector() != null) {
                return jar.collector();
            }
        }
        return null;
    }

    private void log(MessageType type, String message) {
        if (server != null) {
            server.logMessage(type, message);
        }
    }

    private void notifyIndexChanged() {
        for (Runnable listener : indexChangedListeners) {
            try {
                listener.run();
            } catch (RuntimeException e) {
                log(MessageType.Error, "Index-changed listener failed: " + e.getMessage());
            }
        }
    }

    private record State(Index index,
                         List<SourceRoot> sourceRoots,
                         Map<String, InputSource> inputsByUri,
                         Map<String, SourceFingerprint> fingerprints) {
        static State empty() {
            return new State(null, List.of(), Map.of(), Map.of());
        }
    }

    record SourceFingerprint(long size, long mtimeMillis) {}

    public record SourceRoot(Path path, String sourceUri) {}

    record ResolvedResource(String sourceUri, String relativePath) {}
}
