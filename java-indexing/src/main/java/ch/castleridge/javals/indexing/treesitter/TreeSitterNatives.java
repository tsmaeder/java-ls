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
 * {@code tree-sitter-kotlin} shared libraries for the host platform.
 *
 * <p>Natives are laid out as {@code {root}/{os}-{arch}/} (e.g.
 * {@code natives/windows-x64/}). Property/env values and search roots point at
 * the parent; this class resolves into the host platform subdirectory.
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
                return Optional.of(cachedDir);
            }
            return Optional.empty();
        }
    }

    public static Path requireDirectory() {
        return directory().orElseThrow(() -> new IllegalStateException(
                "tree-sitter natives not found. Set " + ENV_DIR
                        + " or place libs next to the jar / in target/natives/"
                        + hostPlatformStem() + "."));
    }

    public static Path runtimeLibrary() {
        return requireDirectory().resolve(System.mapLibraryName("tree-sitter"));
    }

    public static Path kotlinLibrary() {
        return requireDirectory().resolve(System.mapLibraryName("tree-sitter-kotlin"));
    }

    /** Host platform stem matching release layout ({@code windows-x64}, etc.). */
    public static String hostPlatformStem() {
        String osName = System.getProperty("os.name", "").toLowerCase();
        String arch = System.getProperty("os.arch", "").toLowerCase();
        boolean x64 = arch.equals("amd64") || arch.equals("x86_64") || arch.equals("x64");
        boolean arm64 = arch.equals("aarch64") || arch.equals("arm64");

        if (osName.contains("win") && x64) {
            return "windows-x64";
        }
        if (osName.contains("linux") && x64) {
            return "linux-x64";
        }
        if ((osName.contains("mac") || osName.contains("darwin")) && arm64) {
            return "macos-arm64";
        }
        throw new IllegalStateException(
                "Unsupported host platform for tree-sitter natives: os.name="
                        + System.getProperty("os.name") + " os.arch="
                        + System.getProperty("os.arch"));
    }

    private static Path locate() {
        String prop = System.getProperty(PROP_DIR);
        if (prop != null && !prop.isBlank()) {
            Path found = resolvePlatformDir(Path.of(prop).toAbsolutePath().normalize());
            if (found != null) return found;
        }
        String env = System.getenv(ENV_DIR);
        if (env != null && !env.isBlank()) {
            Path found = resolvePlatformDir(Path.of(env).toAbsolutePath().normalize());
            if (found != null) return found;
        }

        Path besideCode = directoryBesideCodeSource();
        if (besideCode != null) {
            Path found = resolvePlatformDir(besideCode);
            if (found != null) return found;
        }

        // Maven test / module target/natives relative to cwd or class location.
        Path fromClasses = directoryBesideCodeSource();
        if (fromClasses != null) {
            Path found = resolvePlatformDir(fromClasses.resolve("natives"));
            if (found != null) return found;
            // classes -> target/classes; natives is target/natives
            Path target = fromClasses.getFileName() != null
                    && "classes".equals(fromClasses.getFileName().toString())
                    ? fromClasses.getParent()
                    : null;
            if (target != null) {
                found = resolvePlatformDir(target.resolve("natives"));
                if (found != null) return found;
            }
        }

        Path found = resolvePlatformDir(Path.of("target/natives").toAbsolutePath().normalize());
        if (found != null) return found;

        return resolvePlatformDir(
                Path.of("java-indexing/target/natives").toAbsolutePath().normalize());
    }

    /** Resolves {@code root/{hostPlatform}} when that subdirectory holds the libs. */
    private static Path resolvePlatformDir(Path root) {
        if (root == null || !Files.isDirectory(root)) {
            return null;
        }
        Path platform = root.resolve(hostPlatformStem());
        return hasLibs(platform) ? platform : null;
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
