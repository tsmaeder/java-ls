package ch.castleridge.javals.indexing.cli;

import ch.castleridge.javals.indexing.classfile.JavaClassIndex;
import ch.castleridge.javals.indexing.declaration.DeclarationIndex;
import ch.castleridge.javals.indexing.store.IndexEntry;
import ch.castleridge.javals.indexing.store.SearchPredicate;

import java.io.IOException;
import java.net.URI;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.stream.Stream;

/** Indexes {@code .class} files, {@code .jar} / {@code .jmod} archives, and JDK runtime modules, then prints declaration skeletons to stdout. */
public final class IndexDecompilerMain {

    private static final int JDK_INDEX_PROGRESS_EVERY = 1000;

    private IndexDecompilerMain() {}

    public static void main(String[] args) {
        List<String> positional = new ArrayList<>();
        List<String> jdkModules = new ArrayList<>();
        boolean jdkAll = false;
        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            if ("--jdk".equals(a)) {
                jdkAll = true;
            } else if ("--jdk-module".equals(a)) {
                if (i + 1 >= args.length) {
                    System.err.println("--jdk-module requires a module name");
                    usage();
                    System.exit(2);
                }
                jdkModules.add(args[++i]);
            } else if (a.startsWith("-")) {
                System.err.println("Unknown option: " + a);
                usage();
                System.exit(2);
            } else {
                positional.add(a);
            }
        }
        if (jdkAll && !jdkModules.isEmpty()) {
            System.err.println("Cannot combine --jdk with --jdk-module");
            usage();
            System.exit(2);
        }
        if (positional.isEmpty() && !jdkAll && jdkModules.isEmpty()) {
            usage();
            System.exit(2);
        }
        ExecutorService exec = Executors.newSingleThreadExecutor();
        boolean anyError = false;
        try {
            JavaClassIndex index = new JavaClassIndex(exec);
            if (jdkAll || !jdkModules.isEmpty()) {
                anyError |= indexJdkModules(index, jdkAll, jdkModules);
            }
            for (String arg : positional) {
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
                } else if (fileName.endsWith(".jmod")) {
                    anyError |= indexJmod(path, index);
                } else {
                    System.err.println("Expected .class, .jar, or .jmod: " + path);
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
        System.err.println(
                "Usage: java ... IndexDecompilerMain [OPTIONS] <file.class|file.jar|file.jmod>...");
        System.err.println("  --jdk                 Index all classes from the runtime JDK image (jrt:/)");
        System.err.println("  --jdk-module <name>   Index one JDK module by name (repeatable), e.g. java.base");
    }

    /**
     * Indexes classes from the run-time JDK module image ({@code jrt:/}). For tests, package-private.
     *
     * @param allModules when {@code true}, every module under {@code jrt:/modules} is walked; {@code moduleNames} must be empty
     * @param moduleNames when {@code allModules} is {@code false}, each entry is a top-level module directory (e.g. {@code java.base})
     */
    static boolean indexJdkModules(JavaClassIndex index, boolean allModules, List<String> moduleNames) {
        try {
            FileSystem jrt = FileSystems.getFileSystem(URI.create("jrt:/"));
            Path jrtModules = jrt.getPath("/modules");
            AtomicInteger jdkClassCount = new AtomicInteger(0);
            if (allModules) {
                return indexJrtTree(jrtModules, index, jdkClassCount);
            }
            boolean anyError = false;
            for (String mod : moduleNames) {
                Path moduleRoot = jrtModules.resolve(mod);
                if (!Files.isDirectory(moduleRoot)) {
                    System.err.println("JDK module not found in jrt:/modules: " + mod);
                    anyError = true;
                    continue;
                }
                anyError |= indexJrtTree(moduleRoot, index, jdkClassCount);
            }
            return anyError;
        } catch (Exception e) {
            System.err.println("JDK module indexing failed (requires a modular run-time image): " + e.getMessage());
            return true;
        }
    }

    private static boolean indexJrtTree(Path root, JavaClassIndex index, AtomicInteger jdkClassCount) {
        boolean anyError = false;
        try (Stream<Path> stream = Files.walk(root)) {
            for (Path path : (Iterable<Path>) stream::iterator) {
                if (!Files.isRegularFile(path)) {
                    continue;
                }
                String name = path.getFileName().toString();
                if (!name.endsWith(".class")) {
                    continue;
                }
                if ("module-info.class".equals(name)) {
                    continue;
                }
                try {
                    byte[] bytes = Files.readAllBytes(path);
                    index.indexClassFile(path.toUri(), bytes).join();
                    int n = jdkClassCount.incrementAndGet();
                    if (n % JDK_INDEX_PROGRESS_EVERY == 0) {
                        System.err.println("JDK indexing: " + n + " class files indexed");
                    }
                } catch (Exception e) {
                    System.err.println("Failed to index " + path.toUri() + ": " + e.getMessage());
                    anyError = true;
                }
            }
        } catch (IOException e) {
            System.err.println("Failed to walk " + root + ": " + e.getMessage());
            return true;
        }
        return anyError;
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

    /** JDK {@code .jmod} files are ZIP archives; compiled classes live under {@code classes/}. */
    private static boolean indexJmod(Path jmodPath, JavaClassIndex index) {
        boolean anyError = false;
        try (JarFile jar = new JarFile(jmodPath.toFile())) {
            for (JarEntry entry : Collections.list(jar.entries())) {
                if (entry.isDirectory()) {
                    continue;
                }
                String name = entry.getName();
                if (!name.startsWith("classes/") || !name.endsWith(".class")) {
                    continue;
                }
                if (name.endsWith("module-info.class")) {
                    continue;
                }
                URI classUri = URI.create("jar:" + jmodPath.toUri() + "!/" + name);
                try {
                    byte[] bytes = jar.getInputStream(entry).readAllBytes();
                    index.indexClassFile(classUri, bytes).join();
                } catch (Exception e) {
                    System.err.println("Failed to index " + jmodPath + "!/" + name + ": " + e.getMessage());
                    anyError = true;
                }
            }
        } catch (Exception e) {
            System.err.println("Failed to open jmod " + jmodPath + ": " + e.getMessage());
            return true;
        }
        return anyError;
    }
}
