/**
 * Copyright 2026 by Castle Ridge Software GmbH
 *
 * Author: Thomas Mäder, Castle Ridge Software
 *
 */
package ch.castleridge.javals.mavenimporter;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;

/**
 * Decides whether an existing mbt.json is still fresh relative to scanned pom.xml files
 * (and any other input files such as a generated-source-rules JSON).
 */
public final class OutputUpToDate {

    private OutputUpToDate() {}

    /**
     * Returns {@code true} when {@code output} exists as a regular file and no path in
     * {@code inputs} has a last-modified time strictly after the output's.
     */
    public static boolean isUpToDate(Path output, Collection<Path> inputs) throws IOException {
        if (!Files.isRegularFile(output)) {
            return false;
        }
        long outputMtime = Files.getLastModifiedTime(output).toMillis();
        for (Path input : inputs) {
            if (input == null || !Files.isRegularFile(input)) {
                continue;
            }
            if (Files.getLastModifiedTime(input).toMillis() > outputMtime) {
                return false;
            }
        }
        return true;
    }
}
