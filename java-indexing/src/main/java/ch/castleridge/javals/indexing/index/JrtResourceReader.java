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
 * Reads bytes from resource URIs in the custom
 * {@code jrt://<java-home-path>!<path-within-jrt-fs>} form produced by
 * {@link ch.castleridge.javals.indexing.scan.JrtInput}.
 */
final class JrtResourceReader {

    private JrtResourceReader() {}

    static byte[] readAllBytes(String jrtResourceUri) throws IOException {
        int bang = jrtResourceUri.indexOf("!/");
        if (bang < 0) {
            throw new IOException("Malformed jrt resource URI (missing !/): " + jrtResourceUri);
        }
        String javaHomeRaw = jrtResourceUri.substring("jrt://".length(), bang);
        String entryPath = jrtResourceUri.substring(bang + 2);
        Path javaHome = Path.of(URI.create("file:" + javaHomeRaw));
        try (FileSystem fs = FileSystems.newFileSystem(
                URI.create("jrt:/"),
                Map.of("java.home", javaHome.toString()))) {
            return Files.readAllBytes(fs.getPath(entryPath));
        }
    }

    static InputStream openStream(String jrtResourceUri) throws IOException {
        return new java.io.ByteArrayInputStream(readAllBytes(jrtResourceUri));
    }
}
