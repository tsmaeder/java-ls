package ch.castleridge.javals.indexing.scan;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;

import ch.castleridge.javals.indexing.index.Index;

final class DirWalker {

    private DirWalker() {}

    static void walk(DirInput in, ResourceSink sink, boolean catalogClassFilesOnly) {
        Path root = in.root();
        if (!Files.exists(root)) return;
        try {
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    String name = file.getFileName().toString();
                    if (!isIndexable(name)) return FileVisitResult.CONTINUE;
                    if (Index.isSkippedFileName(name)) return FileVisitResult.CONTINUE;
                    String uri = file.toUri().toString();
                    if (catalogClassOnly(catalogClassFilesOnly, name)) {
                        sink.accept(uri, name, null);
                        return FileVisitResult.CONTINUE;
                    }
                    sink.accept(uri, name, () -> Files.readAllBytes(file));
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            throw new RuntimeException("Failed walking " + root, e);
        }
    }

    private static boolean catalogClassOnly(boolean catalog, String name) {
        return catalog && name.endsWith(".class") && !Index.isModuleInfoFileName(name);
    }

    private static boolean isIndexable(String name) {
        return name.endsWith(".java") || name.endsWith(".class");
    }
}
