package ch.castleridge.javals.indexing.scan;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
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
        if (in.javaHome() == null) {
            FileSystem fs = FileSystems.getFileSystem(URI.create("jrt:/"));
            walkOn(fs, in, sink, null);
            return;
        }
        try (FileSystem fs = FileSystems.newFileSystem(
                URI.create("jrt:/"),
                Map.of("java.home", in.javaHome().toString()))) {
            String jdkSuffix = "java.home="
                    + URLEncoder.encode(in.javaHome().toString(), StandardCharsets.UTF_8);
            walkOn(fs, in, sink, jdkSuffix);
        } catch (IOException e) {
            throw new RuntimeException("Failed opening jrt:/ for " + in.javaHome(), e);
        }
    }

    private static void walkOn(FileSystem fs, JrtInput in, ResourceSink sink, String querySuffix) {
        Path modulesRoot = fs.getPath("modules");
        try {
            if (JrtInput.ALL.equals(in.moduleOrAll())) {
                try (Stream<Path> list = Files.list(modulesRoot)) {
                    for (Path mod : (Iterable<Path>) list::iterator) {
                        walkModule(mod, sink, querySuffix);
                    }
                }
            } else {
                walkModule(modulesRoot.resolve(in.moduleOrAll()), sink, querySuffix);
            }
        } catch (IOException e) {
            throw new RuntimeException("Failed walking jrt:/" + in.moduleOrAll(), e);
        }
    }

    private static void walkModule(Path moduleRoot, ResourceSink sink, String querySuffix) throws IOException {
        if (!Files.exists(moduleRoot)) return;
        Files.walkFileTree(moduleRoot, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                String name = file.getFileName().toString();
                if (!isIndexable(name)) return FileVisitResult.CONTINUE;
                if (Index.isSkippedFileName(name)) return FileVisitResult.CONTINUE;
                URI uri = disambiguate(file.toUri(), querySuffix);
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
     * Append {@code querySuffix} (e.g. {@code java.home=...}) to the URI's
     * query component so entries emitted from different JDK installs don't
     * collide on the same {@code jrt:/modules/<mod>/...} string.
     */
    private static URI disambiguate(URI uri, String querySuffix) {
        if (querySuffix == null) return uri;
        String s = uri.toString();
        if (s.indexOf('?') >= 0) {
            return URI.create(s + "&" + querySuffix);
        }
        return URI.create(s + "?" + querySuffix);
    }

    private static boolean isIndexable(String name) {
        return name.endsWith(".java") || name.endsWith(".class");
    }
}
