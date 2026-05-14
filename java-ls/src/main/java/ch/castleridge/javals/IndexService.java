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
import java.util.concurrent.atomic.AtomicReference;

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
 * <p>While the scan is running both {@link #index()}  returns {@link Optional#empty()}; callers that need
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
        return CompletableFuture.runAsync(() -> loadFrom(mbt, workspacePath));
    }
   
   public ClasspathOrder classPathFor(String uri) {
      for(ClasspathOrder classpath : state.get().classpathsByNamespace.values()) {
         if (classpath.contains(uri)) {
            return classpath;
         }
      }
      return ClasspathOrder.UNRESTRICTED;

   } 
 
    private void loadFrom(Path mbt, Path workspacePath) {
        try {
            MbtInfo info = MbtJson.read(mbt);
            Map<String, String> sourceJarByBinaryJar = new HashMap<>();
            Map<String, ClasspathOrder> classpathsByNamespace = new HashMap<>();
            Map<String, InputSource> sources = new HashMap<>(); 

            extractInfo(info, workspacePath, sourceJarByBinaryJar, classpathsByNamespace, sources);

            if (sources.isEmpty()) {
                log(MessageType.Warning, "mbt.json contained no input sources: " + mbt);
                return;
            }
            Index index = new Index();
            Scanner scanner = new Scanner();
            long t0 = System.nanoTime();
            List<Throwable> failures = scanner.scanAll(sources.values(), index);
            long elapsedMs = (System.nanoTime() - t0) / 1_000_000L;

            state.set(new State(index, classpathsByNamespace, sourceJarByBinaryJar));
            log(MessageType.Info, "Indexed " + index.size() + " types ("
                    + index.entryCount() + " entries) from " + sources.size()
                    + " sources in " + elapsedMs + " ms"
                    + (failures.isEmpty() ? "" : "; " + failures.size() + " failures"));
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

    private void extractInfo(MbtInfo info, Path workspacePath, Map<String, String> sourceJarByBinaryJar, Map<String, ClasspathOrder> classpathsByNamespace, Map<String, InputSource> sources) {
        Map<String, MbtDependencyModuleInfo> dependencyModuleInfos = new HashMap<>();
       
        for (MbtDependencyModuleInfo dependencyModuleInfo : info.dependencyModules) {
            dependencyModuleInfos.put(dependencyModuleInfo.id, dependencyModuleInfo);
        }

        for (MbtDependencyModuleInfo dependencyModuleInfo : info.dependencyModules) {
            String binaryJar = dependencyModuleInfo.jar;
            String sourceJar = dependencyModuleInfo.sources;
            if (binaryJar != null && sourceJar != null) {
                sourceJarByBinaryJar.put(binaryJar, sourceJar);
                if (!sources.containsKey(binaryJar)) {
                    sources.put(dependencyModuleInfo.id, new JarInput(pathFromUri(binaryJar)));
                }
            }
        }

        for (String namespaceId : info.namespaces.keySet()) {
            MbtTargetInfo targetInfo = info.namespaces.get(namespaceId);
           classpathOrder(namespaceId, targetInfo, workspacePath, dependencyModuleInfos, classpathsByNamespace, sources, sourceJarByBinaryJar); 
        }
    }

    private static void classpathOrder(String namespaceId, MbtTargetInfo targetInfo, Path workspacePath, Map<String, MbtDependencyModuleInfo> dependencyModules, Map<String, ClasspathOrder> classpathsByNamespace, Map<String, InputSource> sources, Map<String, String> sourceJarByBinaryJar) {
        List<ClasspathEntry> classpathEntries = new ArrayList<>();
        for (String source : targetInfo.sources) {
            Path sourcePath = workspacePath.resolve(source);
            if (Files.isDirectory(sourcePath)) {
               String sourceUri = sourcePath.toUri().toString(); 
                classpathEntries.add(UriClasspathEntry.of(sourceUri));
                if (!sources.containsKey(sourceUri)) {
                    sources.put(sourceUri, new DirInput(sourcePath));
                }
            }
        }
       
       Path jdk = Path.of(System.getProperty("java.home")); 

        if (targetInfo.javaHome != null && !targetInfo.javaHome.isBlank()) {
            jdk = Path.of(targetInfo.javaHome).toAbsolutePath().normalize();
        } 
        JrtInput jrtInput = new JrtInput(jdk) ; 
        
        if (!sources.containsKey(jrtInput.sourceUri().toString())) {
            sources.put(jrtInput.sourceUri().toString(), jrtInput);
            Path sourcePath = jdk.resolve("lib/src.zip");
            if (Files.isRegularFile(sourcePath)) {
                sourceJarByBinaryJar.put(jrtInput.sourceUri().toString(), sourcePath.toUri().toString());
            }
        }
        
        for (String dependencyModuleId : targetInfo.dependencyModules) {
            MbtDependencyModuleInfo dependencyModuleInfo = dependencyModules.get(dependencyModuleId);
            if (dependencyModuleInfo != null) {
                classpathEntries.add(UriClasspathEntry.of(dependencyModuleInfo.jar));
            }
        }
        classpathEntries.add(UriClasspathEntry.of(jrtInput.sourceUri()));
        classpathsByNamespace.put(namespaceId,   new ClasspathOrder(classpathEntries, false));
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
        }
        return null;
    }

    private void log(MessageType type, String message) {
        if (server != null) {
            server.logMessage(type, message);
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
