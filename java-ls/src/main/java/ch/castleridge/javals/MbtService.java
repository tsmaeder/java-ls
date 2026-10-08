/**
 * Copyright 2026 by Castle Ridge Software GmbH
 *
 * Author: Thomas Mäder, Castle Ridge Software
 *
 */
package ch.castleridge.javals;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.lsp4j.MessageType;

import ch.castleridge.javals.classpath.ClasspathEntry;
import ch.castleridge.javals.classpath.ClasspathOrder;
import ch.castleridge.javals.classpath.UriClasspathEntry;
import ch.castleridge.javals.indexing.mbt.MbtDependencyModuleInfo;
import ch.castleridge.javals.indexing.mbt.MbtInfo;
import ch.castleridge.javals.indexing.mbt.MbtJson;
import ch.castleridge.javals.indexing.mbt.MbtTargetInfo;
import ch.castleridge.javals.indexing.model.ResourceUris;
import ch.castleridge.javals.indexing.scan.DirInput;
import ch.castleridge.javals.indexing.scan.InputSource;
import ch.castleridge.javals.indexing.scan.JarInput;
import ch.castleridge.javals.indexing.scan.JrtInput;
import ch.castleridge.javals.indexing.scan.ScanCollector;

/**
 * Owns {@code mbt.json} loading: target listing, per-target classpaths,
 * scan inputs, source roots, and binary→sources jar mapping.
 *
 * <p>Classpath composition per target matches the prior IndexService
 * behaviour: own sources → {@code dependsOn} sources → dependency jars →
 * JRT. {@link #targetFor(String)} / {@link #classPathFor(String)} resolve a
 * URI to the first target (by namespace id) whose own source roots claim it,
 * or — for attached sources — the first target that depends on the binary
 * jar mapped to that sources archive.
 */
public final class MbtService {

    private final JavaLanguageServer server;
    private final AtomicReference<State> state = new AtomicReference<>(State.empty());

    public MbtService(JavaLanguageServer server) {
        this.server = server;
    }

    /**
     * Load {@code mbt.json} and build targets, classpaths, and scan inputs.
     * Does not index; the caller should pass {@link #inputSources()} and
     * {@link #sourceRoots()} to {@link IndexService#index}.
     */
    public void loadFrom(Path mbt, Path workspacePath) {
        try {
            MbtInfo info = MbtJson.read(mbt);
            Map<String, String> sourceJarByBinaryJar = new HashMap<>();
            Map<String, InputSource> sources = new LinkedHashMap<>();
            ScanCollector collector = new ScanCollector();
            Map<String, Target> targets = new TreeMap<>();

            extractInfo(info, workspacePath, sourceJarByBinaryJar, sources, collector, targets);

            if (sources.isEmpty()) {
                log(MessageType.Warning, "mbt.json contained no input sources: " + mbt);
                return;
            }
            List<IndexService.SourceRoot> sourceRoots = collectSourceRoots(sources);
            Map<String, String> binaryJarBySourceJar = invertSourceJarMap(sourceJarByBinaryJar);
            state.set(new State(
                    Collections.unmodifiableNavigableMap(new TreeMap<>(targets)),
                    Map.copyOf(sourceJarByBinaryJar),
                    Map.copyOf(binaryJarBySourceJar),
                    List.copyOf(sources.values()),
                    sourceRoots,
                    collector));
        } catch (IOException e) {
            log(MessageType.Error, "Failed to load mbt.json " + mbt + ": " + e.getMessage());
        } catch (RuntimeException e) {
            StringWriter writer = new StringWriter();
            e.printStackTrace(new PrintWriter(writer));
            log(MessageType.Error, "Failed to load mbt.json " + mbt + ": " + writer);
        }
    }

    /** Targets keyed by namespace id (sorted). Empty before/without a successful load. */
    public Map<String, Target> targets() {
        return state.get().targets;
    }

    /**
     * First target (stable namespace-id order) that owns {@code uri}: either
     * via own source roots, or as a dependency of the binary jar whose
     * attached sources archive contains the URI. Empty when none match.
     */
    public Optional<Target> targetFor(String uri) {
        if (uri == null) {
            return Optional.empty();
        }
        State current = state.get();
        for (Target target : current.targets.values()) {
            for (String rootUri : target.ownSourceRootUris()) {
                if (uri.startsWith(rootUri)) {
                    return Optional.of(target);
                }
            }
        }
        String sourcesArchive = ResourceUris.classpathContainer(uri);
        String binaryJarUri = binaryJarForSourcesArchive(current.binaryJarBySourceJar, sourcesArchive);
        if (binaryJarUri == null) {
            return Optional.empty();
        }
        for (Target target : current.targets.values()) {
            if (target.classpath().contains(binaryJarUri)) {
                return Optional.of(target);
            }
        }
        return Optional.empty();
    }

    /**
     * Classpath for {@code uri}: the maintained classpath of
     * {@link #targetFor(String)}, or {@link ClasspathOrder#UNRESTRICTED}.
     */
    public ClasspathOrder classPathFor(String uri) {
        return targetFor(uri).map(Target::classpath).orElse(ClasspathOrder.UNRESTRICTED);
    }

    public Map<String, String> sourceJarByBinaryJar() {
        return state.get().sourceJarByBinaryJar;
    }

    public List<String> sourceRootUris() {
        return state.get().sourceRoots.stream().map(IndexService.SourceRoot::sourceUri).toList();
    }

    public List<IndexService.SourceRoot> sourceRoots() {
        return state.get().sourceRoots;
    }

    public Collection<InputSource> inputSources() {
        return state.get().inputSources;
    }

    /** Shared scan collector attached to {@link #inputSources()}; may be null before load. */
    public ScanCollector scanCollector() {
        return state.get().scanCollector;
    }

    private void extractInfo(MbtInfo info, Path workspacePath, Map<String, String> sourceJarByBinaryJar,
                             Map<String, InputSource> sources, ScanCollector collector,
                             Map<String, Target> targets) {
        Map<String, MbtDependencyModuleInfo> dependencyModuleInfos = new HashMap<>();

        if (info.dependencyModules != null) {
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
                        sourceJarByBinaryJar.put(binaryJarPath.toUri().toString(), sourceJar);
                    }
                }
            }
        }

        if (info.namespaces == null) {
            return;
        }
        // TreeMap iteration later; build in id order for stable "first target".
        List<String> namespaceIds = new ArrayList<>(info.namespaces.keySet());
        namespaceIds.sort(String::compareTo);
        for (String namespaceId : namespaceIds) {
            MbtTargetInfo targetInfo = info.namespaces.get(namespaceId);
            Target target = buildTarget(namespaceId, targetInfo, workspacePath, dependencyModuleInfos,
                    info.namespaces, sources, sourceJarByBinaryJar, collector);
            targets.put(namespaceId, target);
        }
    }

    private static Target buildTarget(String namespaceId,
                                      MbtTargetInfo targetInfo,
                                      Path workspacePath,
                                      Map<String, MbtDependencyModuleInfo> dependencyModules,
                                      Map<String, MbtTargetInfo> namespaces,
                                      Map<String, InputSource> sources,
                                      Map<String, String> sourceJarByBinaryJar,
                                      ScanCollector collector) {
        List<ClasspathEntry> classpathEntries = new ArrayList<>();
        List<String> ownSourceRootUris = new ArrayList<>();
        addSourceRoots(targetInfo.sources, workspacePath, classpathEntries, sources, collector, ownSourceRootUris);

        if (targetInfo.dependsOn != null && !targetInfo.dependsOn.isEmpty()) {
            Set<String> visited = new HashSet<>();
            visited.add(namespaceId);
            for (String depId : targetInfo.dependsOn) {
                addDependsOnEntries(depId, workspacePath, namespaces,
                        classpathEntries, sources, visited, collector);
            }
        }

        Path jdk = Path.of(System.getProperty("java.home"));
        if (targetInfo.javaHome != null && !targetInfo.javaHome.isBlank()) {
            Path parsed = pathFromUri(targetInfo.javaHome);
            if (parsed != null) {
                jdk = parsed;
            }
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
        return new Target(namespaceId, targetInfo, new ClasspathOrder(classpathEntries, false),
                List.copyOf(ownSourceRootUris));
    }

    private static void addDependsOnEntries(String depNamespaceId,
                                            Path workspacePath,
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
        addSourceRoots(dep.sources, workspacePath, classpathEntries, sources, collector, null);
    }

    private static void addSourceRoots(List<String> roots,
                                       Path workspacePath,
                                       List<ClasspathEntry> classpathEntries,
                                       Map<String, InputSource> sources,
                                       ScanCollector collector,
                                       List<String> ownSourceRootUris) {
        if (roots == null) {
            return;
        }
        for (String source : roots) {
            if (source == null || source.isBlank()) {
                continue;
            }
            Path sourcePath = resolveWorkspacePath(workspacePath, source);
            if (Files.isDirectory(sourcePath)) {
                String sourceUri = sourcePath.toUri().toString();
                classpathEntries.add(UriClasspathEntry.of(sourceUri));
                sources.putIfAbsent(sourceUri, new DirInput(sourcePath, collector));
                if (ownSourceRootUris != null) {
                    ownSourceRootUris.add(sourceUri);
                }
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
                classpathEntries.add(UriClasspathEntry.of(jarPath.toUri().toString()));
            }
        }
    }

    private static List<IndexService.SourceRoot> collectSourceRoots(Map<String, InputSource> sources) {
        List<IndexService.SourceRoot> roots = new ArrayList<>();
        for (InputSource src : sources.values()) {
            if (src instanceof DirInput dir) {
                roots.add(new IndexService.SourceRoot(dir.root().toAbsolutePath().normalize(), dir.sourceUri()));
            }
        }
        return List.copyOf(roots);
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

    /** Invert binary→sources into normalized sources-archive URI → binary jar URI. */
    private static Map<String, String> invertSourceJarMap(Map<String, String> sourceJarByBinaryJar) {
        Map<String, String> out = new HashMap<>();
        for (Map.Entry<String, String> e : sourceJarByBinaryJar.entrySet()) {
            String sourcesKey = normalizeArchiveUri(e.getValue());
            if (sourcesKey != null) {
                out.putIfAbsent(sourcesKey, e.getKey());
            }
        }
        return out;
    }

    private static String binaryJarForSourcesArchive(Map<String, String> binaryJarBySourceJar,
                                                     String sourcesArchive) {
        if (sourcesArchive == null || sourcesArchive.isBlank() || binaryJarBySourceJar.isEmpty()) {
            return null;
        }
        String normalized = normalizeArchiveUri(sourcesArchive);
        if (normalized == null) {
            return null;
        }
        return binaryJarBySourceJar.get(normalized);
    }

    /** Normalize a jar/zip/file URI string to a canonical {@code file:} URI key. */
    private static String normalizeArchiveUri(String uri) {
        if (uri == null || uri.isBlank()) {
            return null;
        }
        Path path = pathFromUri(uri);
        if (path != null) {
            return path.toUri().toString();
        }
        return uri.trim();
    }

    /** Resolves a workspace-relative (forward-slash) or absolute source path. */
    private static Path resolveWorkspacePath(Path workspacePath, String source) {
        Path path = Path.of(source.replace('/', java.io.File.separatorChar));
        if (path.isAbsolute()) {
            return path.toAbsolutePath().normalize();
        }
        return workspacePath.resolve(path).toAbsolutePath().normalize();
    }

    private static Path pathFromUri(String s) {
        if (s == null || s.isBlank()) return null;
        String trimmed = s.trim();
        if (trimmed.contains("://") || trimmed.startsWith("file:")) {
            try {
                return Path.of(URI.create(trimmed)).toAbsolutePath().normalize();
            } catch (IllegalArgumentException | java.nio.file.FileSystemNotFoundException e) {
                return null;
            }
        }
        try {
            return Path.of(trimmed).toAbsolutePath().normalize();
        } catch (java.nio.file.InvalidPathException ex) {
            return null;
        }
    }

    private void log(MessageType type, String message) {
        if (server != null) {
            server.logMessage(type, message);
        }
    }

    /**
     * One mbt namespace: its DTO, maintained classpath, and own source-root
     * URIs used for on-the-fly ownership in {@link #targetFor(String)}.
     */
    public record Target(String id, MbtTargetInfo info, ClasspathOrder classpath,
                         List<String> ownSourceRootUris) {}

    private record State(Map<String, Target> targets,
                         Map<String, String> sourceJarByBinaryJar,
                         Map<String, String> binaryJarBySourceJar,
                         Collection<InputSource> inputSources,
                         List<IndexService.SourceRoot> sourceRoots,
                         ScanCollector scanCollector) {
        static State empty() {
            return new State(Map.of(), Map.of(), Map.of(), List.of(), List.of(), null);
        }
    }
}
