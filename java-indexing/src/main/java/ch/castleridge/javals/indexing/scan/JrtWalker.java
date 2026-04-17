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
import java.util.stream.Stream;

import ch.castleridge.javals.indexing.index.Index;

final class JrtWalker {

    private JrtWalker() {}

    static void walk(JrtInput in, ResourceSink sink) {
        FileSystem fs = FileSystems.getFileSystem(URI.create("jrt:/"));
        Path modulesRoot = fs.getPath("modules");
        try {
            if (JrtInput.ALL.equals(in.moduleOrAll())) {
                try (Stream<Path> list = Files.list(modulesRoot)) {
                    for (Path mod : (Iterable<Path>) list::iterator) {
                        walkModule(mod, sink);
                    }
                }
            } else {
                walkModule(modulesRoot.resolve(in.moduleOrAll()), sink);
            }
        } catch (IOException e) {
            throw new RuntimeException("Failed walking jrt:/" + in.moduleOrAll(), e);
        }
    }

    private static void walkModule(Path moduleRoot, ResourceSink sink) throws IOException {
        if (!Files.exists(moduleRoot)) return;
        Files.walkFileTree(moduleRoot, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                String name = file.getFileName().toString();
                if (!isIndexable(name)) return FileVisitResult.CONTINUE;
                if (Index.isSkippedFileName(name)) return FileVisitResult.CONTINUE;
                sink.accept(file.toUri(), name, () -> Files.readAllBytes(file));
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private static boolean isIndexable(String name) {
        return name.endsWith(".java") || name.endsWith(".class");
    }
}
