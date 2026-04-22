package ch.castleridge.javals.indexing.mbt;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import com.google.gson.Gson;

import ch.castleridge.javals.indexing.scan.DirInput;
import ch.castleridge.javals.indexing.scan.InputSource;
import ch.castleridge.javals.indexing.scan.JarInput;
import ch.castleridge.javals.indexing.scan.JrtInput;

/**
 * Reads {@code mbt.json} files (as emitted by the classpath-extractor
 * project) and projects them into the {@link InputSource}s that the
 * scanner consumes.
 */
public final class MbtJson {

    private MbtJson() {}

    /** Parse an {@code mbt.json} file into its DTO shape. */
    public static MbtInfo read(Path file) throws IOException {
        try (Reader reader = Files.newBufferedReader(file)) {
            return new Gson().fromJson(reader, MbtInfo.class);
        }
    }

    /**
     * Convert an {@link MbtInfo} into the ordered, deduplicated list of
     * {@link InputSource}s to feed to {@code Scanner.scanAll}.
     *
     * <p>Order and dedup rules:
     * <ol>
     *   <li>Source folders of every target come first, deduped by
     *       absolute normalized path.</li>
     *   <li>A target's {@code classes} folders are added only when that
     *       target has no {@code sources}: we don't reindex the compiled
     *       output of sources we've already indexed.</li>
     *   <li>Dependency jars (one per distinct path) follow.</li>
     *   <li>One {@link JrtInput} per distinct {@code jdk} path referenced
     *       by any target is emitted last.</li>
     * </ol>
     */
    public static List<InputSource> toInputSources(MbtInfo info) {
        List<InputSource> out = new ArrayList<>();
        if (info == null) return out;

        Set<Path> seenDirs = new LinkedHashSet<>();
        Set<Path> seenJars = new LinkedHashSet<>();
        Set<Path> seenJdks = new LinkedHashSet<>();

        if (info.targets != null) {
            for (MbtTargetInfo t : info.targets) {
                if (t == null) continue;
                addDirs(t.sources, out, seenDirs);
            }
            for (MbtTargetInfo t : info.targets) {
                if (t == null) continue;
                boolean hasSources = t.sources != null && !t.sources.isEmpty();
                if (!hasSources) {
                    addDirs(t.classes, out, seenDirs);
                }
            }
        }

        if (info.dependencyModules != null) {
            for (MbtDependencyModuleInfo dm : info.dependencyModules) {
                if (dm == null || dm.path == null || dm.path.isBlank()) continue;
                Path p = normalize(dm.path);
                if (!Files.isRegularFile(p)) {
                    System.err.println("Skipping mbt dependency (not a regular file): " + p);
                    continue;
                }
                if (seenJars.add(p)) {
                    out.add(new JarInput(p));
                }
            }
        }

        if (info.targets != null) {
            for (MbtTargetInfo t : info.targets) {
                if (t == null || t.jdk == null || t.jdk.isBlank()) continue;
                Path jdk = normalize(t.jdk);
                if (seenJdks.add(jdk)) {
                    out.add(new JrtInput(JrtInput.ALL, jdk));
                }
            }
        }

        return out;
    }

    private static void addDirs(List<String> paths, List<InputSource> out, Set<Path> seen) {
        if (paths == null) return;
        for (String s : paths) {
            if (s == null || s.isBlank()) continue;
            Path p = normalize(s);
            if (seen.add(p)) {
                out.add(new DirInput(p));
            }
        }
    }

    private static Path normalize(String s) {
        return Path.of(s).toAbsolutePath().normalize();
    }
}
