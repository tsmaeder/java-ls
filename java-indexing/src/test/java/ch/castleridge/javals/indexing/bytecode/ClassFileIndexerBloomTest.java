package ch.castleridge.javals.indexing.bytecode;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.StandardLocation;
import javax.tools.ToolProvider;

import org.junit.jupiter.api.Test;

import ch.castleridge.javals.indexing.bloom.IdentifierBloomFilter;
import ch.castleridge.javals.indexing.index.InMemoryIndex;
import ch.castleridge.javals.indexing.model.ResourceUris;

class ClassFileIndexerBloomTest {

    @Test
    void registersBloomWithSuperclassSimpleNameFromConstantPool() throws Exception {
        Path outDir = Files.createTempDirectory("classfile-bloom");
        try {
            JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
            StandardJavaFileManager fm = compiler.getStandardFileManager(
                    null, Locale.getDefault(), java.nio.charset.StandardCharsets.UTF_8);
            fm.setLocation(StandardLocation.CLASS_OUTPUT, List.of(outDir.toFile()));
            JavaFileObject animal = source("mem:///Animal.java", """
                    package p;
                    public class Animal {}
                    """);
            JavaFileObject dog = source("mem:///Dog.java", """
                    package p;
                    public class Dog extends Animal implements Runnable {
                        public void run() {}
                    }
                    """);
            assertTrue(compiler.getTask(null, fm, d -> {}, List.of(), List.of(),
                    List.of(animal, dog)).call());

            InMemoryIndex index = new InMemoryIndex();
            String sourceUri = "file:///lib.jar";
            String dogPath = "p/Dog.class";
            ClassFileIndexer.index(
                    dogPath,
                    sourceUri,
                    Files.readAllBytes(outDir.resolve("p").resolve("Dog.class")),
                    index);

            String resourceUri = ResourceUris.resolve(sourceUri, dogPath);
            Map<String, IdentifierBloomFilter> blooms = index.bloomFilters();
            IdentifierBloomFilter bloom = blooms.get(resourceUri);
            assertNotNull(bloom, "expected bloom keyed by " + resourceUri);
            assertTrue(bloom.mightContain("Animal"));
            assertTrue(bloom.mightContain("Runnable"));
            assertFalse(bloom.mightContain("DefinitelyNotInThisClass"));
        } finally {
            deleteRecursive(outDir);
        }
    }

    private static JavaFileObject source(String uri, String content) {
        return new SimpleJavaFileObject(URI.create(uri), JavaFileObject.Kind.SOURCE) {
            @Override
            public CharSequence getCharContent(boolean ignoreEncodingErrors) {
                return content;
            }
        };
    }

    private static void deleteRecursive(Path root) throws Exception {
        if (!Files.exists(root)) return;
        try (var walk = Files.walk(root)) {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (Exception ignored) {
                }
            });
        }
    }
}
