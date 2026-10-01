/**
 * Copyright 2026 by Castle Ridge Software GmbH
 */
package ch.castleridge.javals;

import java.net.URI;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Parses client-supplied path or {@code file:} URI strings into {@link Path}s.
 */
final class ClientPaths {

    private ClientPaths() {}

    static Path fromClientString(String s) {
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
}
