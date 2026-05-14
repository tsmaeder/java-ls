package ch.castleridge.javals.indexing.scan;

import java.io.IOException;
import java.net.URI;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Map;
import java.util.stream.Stream;

import ch.castleridge.javals.indexing.index.Index;

final class JrtWalker {

    private JrtWalker() {}

    static void walk(JrtInput in, ResourceSink sink) {

        try (FileSystem fs = FileSystems.newFileSystem(
                URI.create("jrt:/"),
                Map.of("java.home", in.javaHome.toString()))) {
            walkOn(fs, in, sink, in.sourceUri());
        } catch (IOException e) {
            throw new RuntimeException("Failed opening jrt:/ for " + in.javaHome, e);
        }
    }

    private static void walkOn(FileSystem fs, JrtInput in, ResourceSink sink, String javaHomeUriPath) {
        Path modulesRoot = fs.getPath("modules");
        try {
                try (Stream<Path> list = Files.list(modulesRoot)) {
                    for (Path mod : (Iterable<Path>) list::iterator) {
                        walkModule(mod, sink, javaHomeUriPath);
                    }
                }
        } catch (IOException e) {
            throw new RuntimeException("Failed walking " + in.sourceUri(), e);
        }
    }

    private static void walkModule(Path moduleRoot, ResourceSink sink, String javaHomeUriPath) throws IOException {
        if (!Files.exists(moduleRoot)) return;
        Files.walkFileTree(moduleRoot, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                String name = file.getFileName().toString();
                if (!isIndexable(name)) return FileVisitResult.CONTINUE;
                if (Index.isSkippedFileName(name)) return FileVisitResult.CONTINUE;
                String uri = jrtUri(javaHomeUriPath, file);
                // Read eagerly: the sink typically defers to an async task,
                // and the jrt filesystem may close before that task runs.
                byte[] bytes;
                try {
                    bytes = Files.readAllBytes(file);
                } catch (IOException ioe) {
                    System.err.println("Skipping unreadable jrt entry " + uri
                            + ": " + ioe.getClass().getSimpleName() + ": " + ioe.getMessage());
                    return FileVisitResult.CONTINUE;
                }
                sink.accept(uri, name, () -> bytes);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    /**
     * Build the resource URI for {@code file} in the
     * {@code jrt:///<absolute-java-home>?<path-within-jrt-fs>} form.
     * Embedding the java home means entries from different JDK installs
     * can never collide on the same key, and the install is recoverable
     * from any URI later.
     */
    private static String jrtUri(String javaHomeUriPath, Path file) {
        String inside = file.toUri().getRawPath();
        if (inside.startsWith("/")) inside = inside.substring(1);
        return javaHomeUriPath + "!/" + inside;
    }

    private static boolean isIndexable(String name) {
        return name.endsWith(".java") || name.endsWith(".class");
    }
}
