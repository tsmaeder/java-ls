/**
 * Copyright 2026 by Castle Ridge Software GmbH
 */
package ch.castleridge.javals.mavenimporter;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Recursively finds {@code pom.xml} files and orders them by path segment count.
 */
public final class PomScanner {

    private PomScanner() {}

    public static Set<Path> scan(Path root) throws IOException {
        List<Path> found = new ArrayList<>();
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                if ("pom.xml".equals(file.getFileName().toString())) {
                    found.add(file.toAbsolutePath().normalize());
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                String name = dir.getFileName() != null ? dir.getFileName().toString() : "";
                if (name.equals(".git")
                        || name.equals("target")
                        || name.equals("node_modules")
                        || name.equals("src")) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                return FileVisitResult.CONTINUE;
            }
        });
        found.sort(Comparator
                .comparingInt(PomScanner::pathSegmentCount)
                .thenComparing(Path::toString));
        return new LinkedHashSet<>(found);
    }

    static int pathSegmentCount(Path path) {
        return path.getNameCount();
    }

    /**
     * Returns the pom with the fewest path segments, or {@code null} if empty.
     */
    public static Path takeShortest(Set<Path> todo) {
        return todo.stream()
                .min(Comparator.comparingInt(PomScanner::pathSegmentCount).thenComparing(Path::toString))
                .orElse(null);
    }
}
