package ch.castleridge.javals.indexing.scan;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;

import ch.castleridge.javals.indexing.index.Index;

/** Directory on the local filesystem, walked recursively. */
public record DirInput(Path root) implements InputSource {
    @Override
    public void walk(ResourceSink sink, boolean indexClassFiles) {
        if (!Files.exists(root)) return;
        try {
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    String name = file.getFileName().toString();
                    if (isIndexable(name)) {
                        String uri = file.toUri().toString();
                        if (shouldReadContents(indexClassFiles, name)) {
                            sink.accept(uri, name, () -> Files.readAllBytes(file));
                        } else {
                            sink.accept(uri, name, null);
                        }
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            throw new RuntimeException("Failed walking " + root, e);
        }
    }

    @Override
    public String sourceUri() {
        return root.toUri().toString();
    }

    private static boolean shouldReadContents(boolean indexClassFiles, String name) {
        return indexClassFiles || !name.endsWith(".class") || Index.isModuleInfoFileName(name);
    }

    private static boolean isIndexable(String name) {
        return (name.endsWith(".java") || name.endsWith(".class")) && !Index.isSkippedFileName(name);
    }
}
