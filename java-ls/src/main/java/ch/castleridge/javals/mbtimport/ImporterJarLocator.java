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

/**
 * Locates the install directory that contains the running {@code java-ls} artifact
 * (directory of the jar, or {@code target/} when running from {@code classes}).
 */
public final class ImporterJarLocator {

    private ImporterJarLocator() {}

    /**
     * Returns the directory beside the jar/directory that contains {@code anchorClass},
     * when that location is resolvable; otherwise {@code null}.
     */
    public static Path installDirectory(Class<?> anchorClass) {
        try {
            URI location = anchorClass.getProtectionDomain().getCodeSource().getLocation().toURI();
            Path path = Paths.get(location);
            if (Files.isRegularFile(path)) {
                return path.getParent();
            }
            if (Files.isDirectory(path)) {
                // Running from target/classes during tests/dev: use target/ (parent of classes).
                if (path.getFileName() != null && "classes".equals(path.getFileName().toString())) {
                    return path.getParent();
                }
                return path;
            }
            return null;
        } catch (URISyntaxException | SecurityException | NullPointerException e) {
            return null;
        }
    }
}
