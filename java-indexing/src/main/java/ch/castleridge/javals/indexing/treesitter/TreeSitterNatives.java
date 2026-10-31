/**
 * Copyright 2026 by Castle Ridge Software GmbH
 *
 * Author: Thomas Mäder, Castle Ridge Software
 *
 */
package ch.castleridge.javals.indexing.treesitter;

import java.net.URI;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/**
 * Resolves the directory that holds {@code tree-sitter} and
 * {@code tree-sitter-kotlin} shared libraries.
 */
public final class TreeSitterNatives {

    public static final String ENV_DIR = "JAVALS_TREE_SITTER_DIR";
    public static final String PROP_DIR = "javals.treeSitter.dir";

    private static volatile Path cachedDir;

    private TreeSitterNatives() {}

    /** Directory containing the two shared libraries, or empty if not found. */
    public static Optional<Path> directory() {
        Path cached = cachedDir;
        if (cached != null) {
            return Optional.of(cached);
        }
        synchronized (TreeSitterNatives.class) {
            if (cachedDir != null) {
                return Optional.of(cachedDir);
            }
            Path found = locate();
            if (found != null) {
                cachedDir = found;
                return Optional.of(found);
            }
            return Optional.empty();
        }
    }

    public static Path requireDirectory() {
        return directory().orElseThrow(() -> new IllegalStateException(
                "tree-sitter natives not found. Set " + ENV_DIR
                        + " or place libs next to the jar / in target/natives."));
    }

    public static Path runtimeLibrary() {
        return requireDirectory().resolve(System.mapLibraryName("tree-sitter"));
    }

    public static Path kotlinLibrary() {
        return requireDirectory().resolve(System.mapLibraryName("tree-sitter-kotlin"));
    }

    private static Path locate() {
        String prop = System.getProperty(PROP_DIR);
        if (prop != null && !prop.isBlank()) {
            Path p = Path.of(prop).toAbsolutePath().normalize();
            if (hasLibs(p)) return p;
        }
        String env = System.getenv(ENV_DIR);
        if (env != null && !env.isBlank()) {
            Path p = Path.of(env).toAbsolutePath().normalize();
            if (hasLibs(p)) return p;
        }

        Path besideCode = directoryBesideCodeSource();
        if (besideCode != null && hasLibs(besideCode)) return besideCode;

        // Maven test / module target/natives relative to cwd or class location.
        Path fromClasses = directoryBesideCodeSource();
        if (fromClasses != null) {
            Path natives = fromClasses.resolve("natives");
            if (hasLibs(natives)) return natives;
            // classes -> target/classes; natives is target/natives
            Path target = fromClasses.getFileName() != null
                    && "classes".equals(fromClasses.getFileName().toString())
                    ? fromClasses.getParent()
                    : null;
            if (target != null) {
                Path n = target.resolve("natives");
                if (hasLibs(n)) return n;
            }
        }

        Path cwdNatives = Path.of("target/natives").toAbsolutePath().normalize();
        if (hasLibs(cwdNatives)) return cwdNatives;

        Path indexingNatives = Path.of("java-indexing/target/natives").toAbsolutePath().normalize();
        if (hasLibs(indexingNatives)) return indexingNatives;

        return null;
    }

    private static Path directoryBesideCodeSource() {
        try {
            URL loc = TreeSitterNatives.class.getProtectionDomain().getCodeSource().getLocation();
            if (loc == null) return null;
            URI uri = loc.toURI();
            Path path = Path.of(uri);
            if (Files.isRegularFile(path)) {
                // Running from a jar: natives sit next to the jar.
                return path.getParent();
            }
            if (Files.isDirectory(path)) {
                return path;
            }
        } catch (Exception ignored) {
            // fall through
        }
        return null;
    }

    private static boolean hasLibs(Path dir) {
        if (dir == null || !Files.isDirectory(dir)) return false;
        return Files.isRegularFile(dir.resolve(System.mapLibraryName("tree-sitter")))
                && Files.isRegularFile(dir.resolve(System.mapLibraryName("tree-sitter-kotlin")));
    }
}
