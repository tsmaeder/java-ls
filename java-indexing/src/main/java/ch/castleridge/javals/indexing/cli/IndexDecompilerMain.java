package ch.castleridge.javals.indexing.cli;

import ch.castleridge.javals.indexing.classfile.JavaClassIndex;
import ch.castleridge.javals.indexing.declaration.DeclarationIndex;
import ch.castleridge.javals.indexing.store.IndexEntry;
import ch.castleridge.javals.indexing.store.SearchPredicate;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/** Indexes {@code .class} files and {@code .jar} archives, then prints declaration skeletons to stdout. */
public final class IndexDecompilerMain {

    private IndexDecompilerMain() {}

    public static void main(String[] args) {
        if (args.length == 0) {
            usage();
            System.exit(2);
        }
        ExecutorService exec = Executors.newSingleThreadExecutor();
        boolean anyError = false;
        try {
            JavaClassIndex index = new JavaClassIndex(exec);
            for (String arg : args) {
                Path path = Paths.get(arg);
                if (!Files.isRegularFile(path)) {
                    System.err.println("Not a file: " + path);
                    anyError = true;
                    continue;
                }
                String fileName = path.getFileName().toString().toLowerCase(Locale.ROOT);
                if (fileName.endsWith(".class")) {
                    anyError |= indexClassPath(path, index, path.toUri());
                } else if (fileName.endsWith(".jar")) {
                    anyError |= indexJar(path, index);
                } else {
                    System.err.println("Expected .class or .jar: " + path);
                    anyError = true;
                }
            }
            DeclarationIndex decl = index.declarations();
            List<IndexEntry> all = new ArrayList<>();
            decl.store().search(new SearchPredicate(Collections.emptyList()), all::add, exec).join();
            String out = IndexedSkeletonRenderer.renderAll(all);
            if (!out.isEmpty()) {
                System.out.print(out);
            }
        } finally {
            exec.shutdown();
            try {
                if (!exec.awaitTermination(60, TimeUnit.SECONDS)) {
                    System.err.println("Executor did not terminate in time");
                    anyError = true;
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                anyError = true;
            }
        }
        if (anyError) {
            System.exit(1);
        }
    }

    private static void usage() {
        System.err.println("Usage: java ... IndexDecompilerMain <file.class|file.jar>...");
    }

    private static boolean indexClassPath(Path path, JavaClassIndex index, URI resourceUri) {
        try {
            byte[] bytes = Files.readAllBytes(path);
            index.indexClassFile(resourceUri, bytes).join();
            return false;
        } catch (Exception e) {
            System.err.println("Failed to index " + path + ": " + e.getMessage());
            return true;
        }
    }

    private static boolean indexJar(Path jarPath, JavaClassIndex index) {
        boolean anyError = false;
        try (JarFile jar = new JarFile(jarPath.toFile())) {
            for (JarEntry entry : Collections.list(jar.entries())) {
                if (entry.isDirectory()) {
                    continue;
                }
                String name = entry.getName();
                if (!name.endsWith(".class")) {
                    continue;
                }
                if (name.startsWith("META-INF/")) {
                    continue;
                }
                if ("module-info.class".equals(name)) {
                    continue;
                }
                URI classUri = URI.create("jar:" + jarPath.toUri() + "!/" + name);
                try {
                    byte[] bytes = jar.getInputStream(entry).readAllBytes();
                    index.indexClassFile(classUri, bytes).join();
                } catch (Exception e) {
                    System.err.println("Failed to index " + jarPath + "!/" + name + ": " + e.getMessage());
                    anyError = true;
                }
            }
        } catch (Exception e) {
            System.err.println("Failed to open jar " + jarPath + ": " + e.getMessage());
            return true;
        }
        return anyError;
    }
}
