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

/**
 * JRT image entries for a JDK install.
 *
 * <p>A fresh {@code jrt:/} filesystem is opened with a {@code java.home}
 * override so arbitrary JDK installs can be indexed.
 *
 * <p>{@link #sourceUri()} has the shape {@code jrt:///<absolute-java-home>}.
 * Walkers emit paths inside the jrt filesystem (e.g.
 * {@code modules/java.base/java/lang/Object.class}); full resource URIs are
 * rebuilt later as {@code sourceUri + "!/" + relativePath}. Embedding the
 * JDK install path in {@code sourceUri} means entries from different JDKs
 * never collide on the same key.
 */
public final class JrtInput implements InputSource {
    public final Path javaHome;
    private final ScanCollector collector;

    public JrtInput(Path javaHome) {
        this(javaHome, null);
    }

    public JrtInput(Path javaHome, ScanCollector collector) {
        this.javaHome = javaHome;
        this.collector = collector;
    }

    @Override
    public void walk(ResourceSink sink) {
        try (FileSystem fs = FileSystems.newFileSystem(
                URI.create("jrt:/"),
                Map.of("java.home", javaHome.toString()))) {
            Path modulesRoot = fs.getPath("modules");
            try (Stream<Path> list = Files.list(modulesRoot)) {
                for (Path mod : (Iterable<Path>) list::iterator) {
                    walkModule(mod, sink);
                }
            }
        } catch (IOException e) {
            throw new RuntimeException("Failed opening jrt:/ for " + javaHome, e);
        }
    }

    @Override
    public String sourceUri() {
        return "jrt://" + this.javaHome.toUri().getRawPath();
    }

    private void walkModule(Path moduleRoot, ResourceSink sink) throws IOException {
        if (!Files.exists(moduleRoot)) return;
        Files.walkFileTree(moduleRoot, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                String name = file.getFileName().toString();
                if (isIndexable(name)) {
                    String relativePath = jrtRelativePath(file);
                    recordStats(name, attrs.size());
                    // Read eagerly: the sink typically defers to an async task,
                    // and the jrt filesystem may close before that task runs.
                    try {
                        byte[] bytes = Files.readAllBytes(file);
                        if (collector != null && name.endsWith(".class") && attrs.size() < 0) {
                            collector.addClassFileBytes(bytes.length);
                        }
                        sink.accept(relativePath, name, () -> bytes);
                    } catch (IOException ioe) {
                        System.err.println("Skipping unreadable jrt entry " + relativePath
                                + ": " + ioe.getClass().getSimpleName() + ": " + ioe.getMessage());
                    }
                }
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private void recordStats(String name, long size) {
        if (collector == null) return;
        if (name.endsWith(".java")) {
            collector.addSourceFile();
        } else if (name.endsWith(".class")) {
            collector.addClassFileBytes(size);
        }
    }

    /** Path within the jrt filesystem, without a leading slash. */
    private static String jrtRelativePath(Path file) {
        String inside = file.toUri().getRawPath();
        if (inside.startsWith("/")) inside = inside.substring(1);
        return inside;
    }

    private static boolean isIndexable(String name) {
        return (name.endsWith(".java") || name.endsWith(".class")) && !Index.isSkippedFileName(name);
    }
}
