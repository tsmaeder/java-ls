package ch.castleridge.javals;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

import ch.castleridge.javals.indexing.cli.HeapSizeEstimator;
import ch.castleridge.javals.indexing.mbt.*;
import ch.castleridge.javals.indexing.scan.*;
import ch.castleridge.javals.indexing.scan.Scanner;
import ch.castleridge.javals.javac.ClasspathEntry;
import ch.castleridge.javals.javac.ClasspathOrder;
import ch.castleridge.javals.javac.UriClasspathEntry;
import org.eclipse.lsp4j.InitializeParams;
import org.eclipse.lsp4j.MessageType;
import org.eclipse.lsp4j.WorkspaceFolder;

import ch.castleridge.javals.indexing.index.Index;

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

    public IndexService(JavaLanguageServer server) {
        this.server = server;
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
        boolean indexClassFiles = InitializationOptions.indexClassFileContents(params);
        boolean prunedSource = InitializationOptions.prunedSourceIndexing(params);
        return CompletableFuture.runAsync(() -> loadFrom(mbt, workspacePath, indexClassFiles, prunedSource));
    }
   
   public ClasspathOrder classPathFor(String uri) {
      for(ClasspathOrder classpath : state.get().classpathsByNamespace.values()) {
         if (classpath.contains(uri)) {
            return classpath;
         }
      }
      return ClasspathOrder.UNRESTRICTED;

   } 
 
    private void loadFrom(Path mbt, Path workspacePath, boolean indexClassFiles, boolean prunedSource) {
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
            Index index = new Index();
            index.addChangedListener(this::notifyIndexChanged);
            state.set(new State(index, classpathsByNamespace, sourceJarByBinaryJar));
            notifyIndexChanged();
            Scanner scanner = new Scanner(indexClassFiles, prunedSource);
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
                    + index.entryCount() + " entries, "
                    + index.classFileSize() + " class files, "
                    + index.prunedSourceSize() + " pruned sources) from " + sources.size()
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
        } catch (IOException e) {
            log(MessageType.Error, "Failed to load mbt.json " + mbt + ": " + e.getMessage());
        } catch (RuntimeException e) {
            log(MessageType.Error, "Indexing failed for " + mbt + ": " + e.getMessage());
        }
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
        if (params == null) return null;
        Object options = params.getInitializationOptions();
        if (!(options instanceof Map<?, ?> map)) {
            return null;
        }
        Object workspacePath = map.get("workspacePath");
        if (workspacePath instanceof String s) {
            return pathFromClientString(s);
        }
        return null;
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
            return null;
        }
    }
    private record State(Index index, Map<String, ClasspathOrder> classpathsByNamespace,
                         Map<String, String> sourceJarByBinaryJar) {
        static State empty() {
            return new State(null, Map.of(), Map.of());
        }
    }
}
