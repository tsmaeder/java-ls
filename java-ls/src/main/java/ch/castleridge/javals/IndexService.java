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
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

import ch.castleridge.javals.indexing.cli.HeapSizeEstimator;
import ch.castleridge.javals.indexing.mbt.*;
import ch.castleridge.javals.indexing.scan.*;
import ch.castleridge.javals.indexing.scan.Scanner;
import ch.castleridge.javals.classpath.ClasspathEntry;
import ch.castleridge.javals.classpath.ClasspathOrder;
import ch.castleridge.javals.classpath.UriClasspathEntry;
import org.eclipse.lsp4j.FileChangeType;
import org.eclipse.lsp4j.FileEvent;
import org.eclipse.lsp4j.InitializeParams;
import org.eclipse.lsp4j.MessageType;
import org.eclipse.lsp4j.WorkspaceFolder;

import ch.castleridge.javals.indexing.index.Index;
import ch.castleridge.javals.indexing.index.InMemoryIndex;
import ch.castleridge.javals.indexing.index.UriCoding;

/**
 * Bootstraps the workspace {@link Index} by locating an {@code mbt.json}
 * in one of the workspace folders and asynchronously running the
 * {@link Scanner} over the {@link InputSource}s it describes.
 *
 * <p>While the scan is running {@link #index()} returns the live index,
 * which grows incrementally as each {@link InputSource} is merged. Callers
 * that need the index can use it immediately; change listeners are
 * notified as entries arrive.
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

    public IndexService(JavaLanguageServer server) {
        this.server = server;
    }

    public void setSourceIndexer(ch.castleridge.javals.indexing.source.SourceIndexer sourceIndexer) {
        this.sourceIndexer = sourceIndexer == null
                ? ch.castleridge.javals.indexing.source.SourceIndexer.javac()
                : sourceIndexer;
    }

    public void addIndexChangedListener(Runnable listener) {
        indexChangedListeners.add(listener);
    }

    public Optional<Index> index() {
        Index i = state.get().index;
        return Optional.ofNullable(i);
    }

    public Map<String, String> sourceJarByBinaryJar() {
        return state.get().sourceJarByBinaryJar;
    }

    /** Directory source-root URIs discovered from {@code mbt.json} (empty before/without index). */
    public List<String> sourceRootUris() {
        return state.get().sourceRoots.stream().map(SourceRoot::sourceUri).toList();
    }

    /**
     * Apply LSP {@code workspace/didChangeWatchedFiles} events: reindex or
     * drop {@code .java} files that fall under a known mbt source root.
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
        Path file = pathFromClientString(UriCoding.decode(event.getUri()));
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
        Path workspacePath = resolveWorkspacePath(params, roots, mbt);
        log(MessageType.Info, "Loading mbt.json: " + mbt);
        return CompletableFuture.runAsync(() -> loadFrom(mbt, workspacePath));
    }
   
    /**
     * Classpath to compile {@code uri} against: the one of the namespace that
     * <em>owns</em> the file, i.e. lists it under its own source roots.
     *
     * <p>A file is claimed by every namespace that can see it, so a module's
     * sources are also on the classpath of each of its dependents. Picking any
     * claimant would compile the file against a dependent's classpath, which
     * lacks the module's own dependencies (namespace dependencies are not
     * transitive here) and makes perfectly good imports unresolvable. The
     * owning namespace lists the file's source root before the roots it
     * inherits from {@code dependsOn}, so the lowest {@link
     * ClasspathOrder#rank(String) rank} identifies it. Namespace id breaks
     * ties so the choice does not depend on map iteration order.
     */
    public ClasspathOrder classPathFor(String uri) {
        ClasspathOrder best = null;
        int bestRank = Integer.MAX_VALUE;
        String bestNamespace = null;
        for (Map.Entry<String, ClasspathOrder> entry : state.get().classpathsByNamespace.entrySet()) {
            int rank = entry.getValue().rank(uri);
            if (rank < 0) continue;
            if (rank < bestRank || (rank == bestRank && entry.getKey().compareTo(bestNamespace) < 0)) {
                bestRank = rank;
                best = entry.getValue();
                bestNamespace = entry.getKey();
            }
        }
        return best == null ? ClasspathOrder.UNRESTRICTED : best;
    }


    private void loadFrom(Path mbt, Path workspacePath) {
        try {
            MbtInfo info = MbtJson.read(mbt);
            Map<String, String> sourceJarByBinaryJar = new HashMap<>();
            Map<String, ClasspathOrder> classpathsByNamespace = new HashMap<>();
            Map<String, InputSource> sources = new HashMap<>();
            ScanCollector collector = new ScanCollector();

            extractInfo(info, workspacePath, sourceJarByBinaryJar, classpathsByNamespace, sources, collector);

            if (sources.isEmpty()) {
                log(MessageType.Warning, "mbt.json contained no input sources: " + mbt);
                return;
            }
            Index index = new InMemoryIndex();
            index.addChangedListener(this::notifyIndexChanged);
            List<SourceRoot> sourceRoots = collectSourceRoots(sources);
            state.set(new State(index, classpathsByNamespace, sourceJarByBinaryJar, sourceRoots));
            notifyIndexChanged();
            Scanner scanner = new Scanner(sourceIndexer);
            long t0 = System.nanoTime();
            List<Throwable> failures = scanner.scanAll(sources.values(), index);
            long elapsedMs = (System.nanoTime() - t0) / 1_000_000L;
            ScanStats stats = collector.snapshot();

            int jarCount = 0;
            long jarBytes = 0L;
            for (InputSource src : sources.values()) {
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
                    + " sources in " + elapsedMs + " ms"
                    + (failures.isEmpty() ? "" : "; " + failures.size() + " failures"));
            log(MessageType.Info, "Index stats: " + stats.sourceFileCount() + " source files, "
                    + jarCount + " jars (" + HeapSizeEstimator.formatBytes(jarBytes) + "); class files "
                    + HeapSizeEstimator.formatBytes(stats.classFileBytes()));
            HeapSizeEstimator est = new HeapSizeEstimator();
            long estimated = est.estimate(index);
            log(MessageType.Info, "Index memory estimate: " + HeapSizeEstimator.formatBytes(estimated));
            for (String row : est.topByBytes(15)) {
                log(MessageType.Info, "  " + row);
            }
            failures.forEach(f -> {
                StringWriter writer = new StringWriter();
                f.printStackTrace(new PrintWriter(writer));
                log(MessageType.Error, "Indexing failure: " + writer.toString());
            });
            if (server != null && !sourceRoots.isEmpty()) {
                server.registerSourceFileWatchers(sourceRootUris());
            }
        } catch (IOException e) {
            log(MessageType.Error, "Failed to load mbt.json " + mbt + ": " + e.getMessage());
        } catch (RuntimeException e) {
            StringWriter writer = new StringWriter();
            e.printStackTrace(new PrintWriter(writer));
            log(MessageType.Error, "Indexing failed for " + mbt + ": " + writer);
        }
    }

    private static List<SourceRoot> collectSourceRoots(Map<String, InputSource> sources) {
        List<SourceRoot> roots = new ArrayList<>();
        for (InputSource src : sources.values()) {
            if (src instanceof DirInput dir) {
                roots.add(new SourceRoot(dir.root().toAbsolutePath().normalize(), dir.sourceUri()));
            }
        }
        return List.copyOf(roots);
    }

    private void extractInfo(MbtInfo info, Path workspacePath, Map<String, String> sourceJarByBinaryJar,
                             Map<String, ClasspathOrder> classpathsByNamespace, Map<String, InputSource> sources,
                             ScanCollector collector) {
        Map<String, MbtDependencyModuleInfo> dependencyModuleInfos = new HashMap<>();
       
        for (MbtDependencyModuleInfo dependencyModuleInfo : info.dependencyModules) {
            dependencyModuleInfos.put(dependencyModuleInfo.id, dependencyModuleInfo);
        }

        for (MbtDependencyModuleInfo dependencyModuleInfo : info.dependencyModules) {
            String binaryJar = dependencyModuleInfo.jar;
            String sourceJar = dependencyModuleInfo.sources;
            if (binaryJar == null) {
                continue;
            }
            Path binaryJarPath = pathFromUri(binaryJar);
            if (binaryJarPath == null) {
                continue;
            }
            if (!sources.containsKey(dependencyModuleInfo.id)) {
                sources.put(dependencyModuleInfo.id, new JarInput(binaryJarPath, collector));
                if (sourceJar != null) {
                    // Key by the normalized file URI that the scanner stamps on
                    // every indexed entry (JarInput.sourceUri() ==
                    // binaryJarPath.toUri()), not the raw mbt.json `jar` string.
                    // The two can differ - e.g. File.toURI() emits `file:/x`
                    // while Path.toUri() emits `file:///x` - and SymbolLocator
                    // looks the sources jar up by the entry's stamped sourceUri.
                    // A mismatch silently disables go-to-definition into a
                    // dependency's sources jar even though the type resolves.
                    sourceJarByBinaryJar.put(binaryJarPath.toUri().toString(), sourceJar);
                }
            }
        }

        for (String namespaceId : info.namespaces.keySet()) {
            MbtTargetInfo targetInfo = info.namespaces.get(namespaceId);
            classpathOrder(namespaceId, targetInfo, workspacePath, dependencyModuleInfos,
                    info.namespaces, classpathsByNamespace, sources, sourceJarByBinaryJar, collector);
        }
    }

    private static void classpathOrder(String namespaceId,
                                       MbtTargetInfo targetInfo,
                                       Path workspacePath,
                                       Map<String, MbtDependencyModuleInfo> dependencyModules,
                                       Map<String, MbtTargetInfo> namespaces,
                                       Map<String, ClasspathOrder> classpathsByNamespace,
                                       Map<String, InputSource> sources,
                                       Map<String, String> sourceJarByBinaryJar,
                                       ScanCollector collector) {
        List<ClasspathEntry> classpathEntries = new ArrayList<>();
        addSourceRoots(targetInfo.sources, workspacePath, classpathEntries, sources, collector);

        if (targetInfo.dependsOn != null && !targetInfo.dependsOn.isEmpty()) {
            Set<String> visited = new HashSet<>();
            visited.add(namespaceId);
            for (String depId : targetInfo.dependsOn) {
                addDependsOnEntries(depId, workspacePath, dependencyModules, namespaces,
                        classpathEntries, sources, visited, collector);
            }
        }

        Path jdk = Path.of(System.getProperty("java.home"));
        if (targetInfo.javaHome != null && !targetInfo.javaHome.isBlank()) {
            jdk = pathFromUri(targetInfo.javaHome);
        }
        JrtInput jrtInput = new JrtInput(jdk, collector);

        if (!sources.containsKey(jrtInput.sourceUri().toString())) {
            sources.put(jrtInput.sourceUri().toString(), jrtInput);
            Path sourcePath = jdk.resolve("lib/src.zip");
            if (Files.isRegularFile(sourcePath)) {
                sourceJarByBinaryJar.put(jrtInput.sourceUri().toString(), sourcePath.toUri().toString());
            }
        }

        addDependencyJars(targetInfo.dependencyModules, dependencyModules, classpathEntries);
        classpathEntries.add(UriClasspathEntry.of(jrtInput.sourceUri()));
        classpathsByNamespace.put(namespaceId, new ClasspathOrder(classpathEntries, false));
    }

    private static void addDependsOnEntries(String depNamespaceId,
                                            Path workspacePath,
                                            Map<String, MbtDependencyModuleInfo> dependencyModules,
                                            Map<String, MbtTargetInfo> namespaces,
                                            List<ClasspathEntry> classpathEntries,
                                            Map<String, InputSource> sources,
                                            Set<String> visited,
                                            ScanCollector collector) {
        if (depNamespaceId == null || depNamespaceId.isBlank() || !visited.add(depNamespaceId)) {
            return;
        }
        MbtTargetInfo dep = namespaces.get(depNamespaceId);
        if (dep == null) {
            return;
        }
        addSourceRoots(dep.sources, workspacePath, classpathEntries, sources, collector);
        /* if (dep.dependsOn != null) {
            for (String transitive : dep.dependsOn) {
                addDependsOnEntries(transitive, workspacePath, dependencyModules, namespaces,
                        classpathEntries, sources, visited);
            }
        }
        addDependencyJars(dep.dependencyModules, dependencyModules, classpathEntries);*/
    }

    private static void addSourceRoots(List<String> roots,
                                       Path workspacePath,
                                       List<ClasspathEntry> classpathEntries,
                                       Map<String, InputSource> sources,
                                       ScanCollector collector) {
        if (roots == null) {
            return;
        }
        for (String source : roots) {
            Path sourcePath = workspacePath.resolve(source);
            if (Files.isDirectory(sourcePath)) {
                String sourceUri = sourcePath.toUri().toString();
                classpathEntries.add(UriClasspathEntry.of(sourceUri));
                sources.putIfAbsent(sourceUri, new DirInput(sourcePath, collector));
            }
        }
    }

    private static void addDependencyJars(List<String> dependencyModuleIds,
                                          Map<String, MbtDependencyModuleInfo> dependencyModules,
                                          List<ClasspathEntry> classpathEntries) {
        if (dependencyModuleIds == null) {
            return;
        }
        for (String dependencyModuleId : dependencyModuleIds) {
            MbtDependencyModuleInfo dependencyModuleInfo = dependencyModules.get(dependencyModuleId);
            if (dependencyModuleInfo != null && dependencyModuleInfo.jar != null) {
                Path jarPath = pathFromUri(dependencyModuleInfo.jar);
                if (jarPath == null) {
                    continue;
                }
                // Match the normalized URI the scanner stamps on indexed
                // entries (see extractInfo) so classpath shadowing/visibility
                // recognises this jar's types regardless of how the raw
                // mbt.json URI happens to be spelled.
                classpathEntries.add(UriClasspathEntry.of(jarPath.toUri().toString()));
            }
        }
    }

    private static Path resolveWorkspacePath(InitializeParams params, List<Path> roots, Path mbt) {
        Path fromOptions = workspacePathFromInitializationOptions(params);
        if (fromOptions != null) {
            return fromOptions.toAbsolutePath().normalize();
        }
        if (!roots.isEmpty()) {
            return roots.get(0).toAbsolutePath().normalize();
        }
        return mbt.toAbsolutePath().normalize().getParent();
    }

    private static Path workspacePathFromInitializationOptions(InitializeParams params) {
        return InitializationOptions.workspacePath(params)
                .map(IndexService::pathFromClientString)
                .orElse(null);
    }

    private static List<Path> workspaceRoots(InitializeParams params) {
        List<Path> roots = new ArrayList<>();
        if (params == null) return roots;
        List<WorkspaceFolder> folders = params.getWorkspaceFolders();
        if (folders != null) {
            for (WorkspaceFolder f : folders) {
                Path p = pathFromClientString(f.getUri());
                if (p != null) roots.add(p);
            }
        }
        if (roots.isEmpty()) {
            @SuppressWarnings("deprecation")
            String rootUri = params.getRootUri();
            Path p = pathFromClientString(rootUri);
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

    private static Path pathFromClientString(String s) {
        if (s == null || s.isBlank()) return null;
        if (s.startsWith("file:") || s.contains("://")) {
            try {
                return Paths.get(URI.create(s));
            } catch (IllegalArgumentException | java.nio.file.FileSystemNotFoundException e) {
                return null;
            }
        }
        return Paths.get(s);
    }

    private static Path findMbtJson(List<Path> roots) {
        for (Path root : roots) {
            Path candidate = root.resolve("mbt.json");
            if (Files.isRegularFile(candidate)) return candidate;
            candidate = root.resolve(".metals", "mbt.json");
            if (Files.isRegularFile(candidate)) return candidate;
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

    static Map<String, String> sourceJarLookup(MbtInfo info) {
        if (info == null || info.dependencyModules == null || info.dependencyModules.isEmpty()) {
            return Map.of();
        }
        Map<String, String> out = new LinkedHashMap<>();
        for (MbtDependencyModuleInfo dm : info.dependencyModules) {
            Path binaryJar = pathFromUri(dm == null ? null : dm.jar);
            Path sourceJar = pathFromUri(dm == null ? null : dm.sources);
            if (binaryJar == null || sourceJar == null) continue;
            if (!Files.isRegularFile(binaryJar) || !Files.isRegularFile(sourceJar)) continue;
            out.putIfAbsent(binaryJar.toUri().toString(), sourceJar.toUri().toString());
        }
        return out.isEmpty() ? Map.of() : Map.copyOf(out);
    }

    private static Path pathFromUri(String s) {
        if (s == null || s.isBlank()) return null;
        try {
            return Path.of(URI.create(s)).toAbsolutePath().normalize();
        } catch (IllegalArgumentException | java.nio.file.FileSystemNotFoundException e) {
            // Not a parseable file URI (e.g. a raw OS path like
            // "C:\Program Files\...\jdk"); fall back to treating it as a path.
            try {
                return Path.of(s).toAbsolutePath().normalize();
            } catch (java.nio.file.InvalidPathException ex) {
                return null;
            }
        }
    }
    private record State(Index index, Map<String, ClasspathOrder> classpathsByNamespace,
                         Map<String, String> sourceJarByBinaryJar,
                         List<SourceRoot> sourceRoots) {
        static State empty() {
            return new State(null, Map.of(), Map.of(), List.of());
        }
    }

    record SourceRoot(Path path, String sourceUri) {}

    record ResolvedResource(String sourceUri, String relativePath) {}
}
