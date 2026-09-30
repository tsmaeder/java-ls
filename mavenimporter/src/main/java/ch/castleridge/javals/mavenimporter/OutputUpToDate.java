/**
 * Copyright 2026 by Castle Ridge Software GmbH
 */
package ch.castleridge.javals.mavenimporter;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;

/**
 * Decides whether an existing mbt.json is still fresh relative to scanned pom.xml files.
 */
public final class OutputUpToDate {

    private OutputUpToDate() {}

    /**
     * Returns {@code true} when {@code output} exists as a regular file and no pom in
     * {@code poms} has a last-modified time strictly after the output's.
     */
    public static boolean isUpToDate(Path output, Collection<Path> poms) throws IOException {
        if (!Files.isRegularFile(output)) {
            return false;
        }
        long outputMtime = Files.getLastModifiedTime(output).toMillis();
        for (Path pom : poms) {
            if (Files.getLastModifiedTime(pom).toMillis() > outputMtime) {
                return false;
            }
        }
        return true;
    }
}
