/**
 * Copyright 2026 by Castle Ridge Software GmbH
 *
 * Author: Thomas Mäder, Castle Ridge Software
 *
 */
package ch.castleridge.javals.mbtimport;

import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;

/**
 * Locates {@code mavenimporter.jar} next to the running {@code java-ls} artifact.
 */
public final class ImporterJarLocator {

    private ImporterJarLocator() {}

    /**
     * Returns the sibling {@code mavenimporter.jar} beside the jar/directory that contains
     * {@code anchorClass}, when that location is resolvable.
     */
    public static Optional<Path> locateMavenImporter(Class<?> anchorClass) {
        Path installDir = installDirectory(anchorClass);
        if (installDir == null) {
            return Optional.empty();
        }
        Path jar = installDir.resolve("mavenimporter.jar");
        return Files.isRegularFile(jar) ? Optional.of(jar) : Optional.empty();
    }

    static Path installDirectory(Class<?> anchorClass) {
        try {
            URI location = anchorClass.getProtectionDomain().getCodeSource().getLocation().toURI();
            Path path = Paths.get(location);
            if (Files.isRegularFile(path)) {
                return path.getParent();
            }
            if (Files.isDirectory(path)) {
                // Running from target/classes during tests/dev: look in ../ or sibling target.
                Path target = path.getFileName() != null && "classes".equals(path.getFileName().toString())
                        ? path.getParent()
                        : path;
                return target;
            }
            return null;
        } catch (URISyntaxException | SecurityException | NullPointerException e) {
            return null;
        }
    }
}
