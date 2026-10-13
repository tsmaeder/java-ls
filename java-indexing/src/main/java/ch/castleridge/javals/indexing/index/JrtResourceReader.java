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
package ch.castleridge.javals.indexing.index;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * Reads bytes from resource URIs in the
 * {@code jdk:///<java-home>!/<module>/...} form produced by
 * {@link ch.castleridge.javals.indexing.scan.JrtInput}.
 */
final class JrtResourceReader {

    private static final String SCHEME_PREFIX = "jdk://";

    private JrtResourceReader() {}

    static byte[] readAllBytes(String jdkResourceUri) throws IOException {
        int bang = jdkResourceUri.indexOf("!/");
        if (bang < 0 || !jdkResourceUri.startsWith(SCHEME_PREFIX)) {
            throw new IOException("Malformed jdk resource URI: " + jdkResourceUri);
        }
        String javaHomeRaw = jdkResourceUri.substring(SCHEME_PREFIX.length(), bang);
        String entryPath = jdkResourceUri.substring(bang + 2);
        Path javaHome = Path.of(URI.create("file:" + javaHomeRaw));
        try (FileSystem fs = FileSystems.newFileSystem(
                URI.create("jrt:/"),
                Map.of("java.home", javaHome.toString()))) {
            return Files.readAllBytes(fs.getPath("modules/" + entryPath));
        }
    }

    static InputStream openStream(String jdkResourceUri) throws IOException {
        return new java.io.ByteArrayInputStream(readAllBytes(jdkResourceUri));
    }
}
