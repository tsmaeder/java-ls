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
package ch.castleridge.javals.indexing.mbt;

import java.io.File;
import java.io.IOException;
import java.io.Reader;
import java.net.URI;
import java.net.URISyntaxException;
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
 * Reads {@code mbt.json} files (Metals MBT schema) and projects them into the
 * {@link InputSource}s that the scanner consumes.
 */
public final class MbtJson {

    private MbtJson() {
    }

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
     * <p>Paths in {@code sources} are resolved relative to {@code workspacePath}
     * when they are not absolute. {@code javaHome} is a filesystem path
     * (optionally a {@code file:} URI).
     *
     * <p>Order and dedup rules:
     * <ol>
     *   <li>Source folders of every namespace come first, deduped by
     *       absolute normalized path.</li>
     *   <li>Dependency jars (one per distinct path) follow.</li>
     *   <li>One {@link JrtInput} per distinct {@code javaHome} path referenced
     *       by any namespace is emitted last.</li>
     * </ol>
     */
    public static List<InputSource> toInputSources(MbtInfo info, Path workspacePath) {
        List<InputSource> out = new ArrayList<>();
        if (info == null) return out;

        Path base = workspacePath == null ? null : workspacePath.toAbsolutePath().normalize();
        Set<Path> seenDirs = new LinkedHashSet<>();
        Set<Path> seenJars = new LinkedHashSet<>();
        Set<Path> seenJdks = new LinkedHashSet<>();

        if (info.namespaces != null) {
            for (MbtTargetInfo t : info.namespaces.values()) {
                addDirs(t.sources, base, out, seenDirs);
                if (t.javaHome != null && !t.javaHome.isBlank()) {
                    Path jdk = resolveJavaHome(t.javaHome);
                    if (jdk != null && seenJdks.add(jdk)) {
                        out.add(new JrtInput(jdk));
                    }
                }
            }
        }

        if (info.dependencyModules != null) {
            for (MbtDependencyModuleInfo dm : info.dependencyModules) {
                addJar(dm.jar, out, seenJars);
            }
        }
        return out;
    }

    private static void addDirs(
            List<String> paths, Path base, List<InputSource> out, Set<Path> seen) {
        if (paths == null) return;
        for (String s : paths) {
            if (s == null || s.isBlank()) continue;
            Path p = resolvePath(s, base);
            if (seen.add(p)) {
                out.add(new DirInput(p));
            }
        }
    }

    private static void addJar(
            String location, List<InputSource> out, Set<Path> seen) {
        if (location == null || location.isBlank()) return;
        try {
            Path p = Path.of(new URI(location)).toAbsolutePath().normalize();
            if (!Files.isRegularFile(p)) {
                System.err.println("Skipping mbt dependency (not a regular file): " + p);
                return;
            }
            if (seen.add(p)) {
                out.add(new JarInput(p));
            }
        } catch (URISyntaxException e) {
            System.err.println("Skipping mbt dependency (invalid URI): " + location);
        }
    }

    /**
     * Resolves {@code javaHome} as a filesystem path. Accepts a bare path or a
     * {@code file:} URI.
     */
    static Path resolveJavaHome(String javaHome) {
        if (javaHome == null || javaHome.isBlank()) {
            return null;
        }
        String trimmed = javaHome.trim();
        if (trimmed.contains("://") || trimmed.startsWith("file:")) {
            try {
                return Path.of(new URI(trimmed)).toAbsolutePath().normalize();
            } catch (URISyntaxException | IllegalArgumentException e) {
                System.err.println("Skipping mbt target (invalid javaHome URI): " + javaHome);
                return null;
            }
        }
        try {
            return Path.of(trimmed).toAbsolutePath().normalize();
        } catch (java.nio.file.InvalidPathException e) {
            System.err.println("Skipping mbt target (invalid javaHome path): " + javaHome);
            return null;
        }
    }

    private static Path resolvePath(String s, Path base) {
        Path relative = Path.of(s.replace('/', File.separatorChar));
        if (relative.isAbsolute()) {
            return relative.toAbsolutePath().normalize();
        }
        if (base != null) {
            return base.resolve(relative).toAbsolutePath().normalize();
        }
        return relative.toAbsolutePath().normalize();
    }
}
