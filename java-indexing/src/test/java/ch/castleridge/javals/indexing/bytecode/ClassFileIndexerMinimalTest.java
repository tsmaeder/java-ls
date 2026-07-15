package ch.castleridge.javals.indexing.bytecode;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.StandardLocation;
import javax.tools.ToolProvider;

import org.junit.jupiter.api.Test;

import ch.castleridge.javals.indexing.index.Index;
import ch.castleridge.javals.indexing.model.ClassFileEntry;
import ch.castleridge.javals.indexing.model.ModuleFileEntry;
import ch.castleridge.javals.indexing.model.TypeEntry;

class ClassFileIndexerMinimalTest {

    @Test
    void indexClassCatalogDerivesJvmNameFromUriWithoutBytes() {
        String resourcePath = "com/example/Hello.class";
        String sourceUri = "file:///lib.jar";

        Index index = new Index();
        ClassFileIndexer.indexClassCatalog(resourcePath, sourceUri, index);

        assertEquals(1, index.classFileSize());
        ClassFileEntry entry = index.getAllClassFiles("com/example/Hello").get(0);
        assertEquals("com/example/Hello", entry.jvmOwnerName());
        assertEquals("jar:file:///lib.jar!/com/example/Hello.class", entry.resourceUri());
    }

    @Test
    void minimalModeRecordsClassFileEntryNotTypeEntry() throws Exception {
        byte[] classBytes = compileClass("class Sample { }", "Sample");
        String resourcePath = "index:///Sample.class";
        String sourceUri = "index:///cp/";

        Index index = new Index();
        ClassFileIndexer.index(resourcePath, sourceUri, classBytes, index, true);

        assertEquals(0, index.size());
        assertEquals(1, index.classFileSize());
        assertNull(index.get("Sample"));
        ClassFileEntry entry = index.getAllClassFiles("Sample").get(0);
        assertEquals("Sample", entry.jvmOwnerName());
        assertEquals(resourcePath, entry.resourceUri());
    }

    @Test
    void minimalModeRecordsModuleFileEntryForModuleInfo() throws Exception {
        byte[] moduleBytes = compileModule("module sample.module { }", "module-info");
        String resourcePath = "index:///module-info.class";
        String sourceUri = "index:///cp/";

        Index index = new Index();
        ClassFileIndexer.index(resourcePath, sourceUri, moduleBytes, index, true);

        assertEquals(0, index.size());
        assertEquals(1, index.moduleFileCount());
        ModuleFileEntry mf = index.getModuleFile("sample.module");
        assertNotNull(mf);
        assertEquals("sample.module", mf.name());
        assertEquals(resourcePath, mf.resourceUri());
    }

    @Test
    void fullModeStillProducesTypeEntry() throws Exception {
        byte[] classBytes = compileClass("class Sample { }", "Sample");
        String resourcePath = "index:///Sample.class";
        String sourceUri = "index:///cp/";

        Index index = new Index();
        ClassFileIndexer.index(resourcePath, sourceUri, classBytes, index, false);

        assertEquals(1, index.size());
        assertEquals(0, index.classFileSize());
        assertNotNull(index.get("Sample"));
    }

    private static byte[] compileClass(String source, String className) throws Exception {
        Path outDir = Files.createTempDirectory("minimal-class-index");
        try {
            compile(source, className + ".java", outDir);
            return Files.readAllBytes(outDir.resolve(className + ".class"));
        } finally {
            deleteRecursively(outDir);
        }
    }

    private static byte[] compileModule(String source, String fileName) throws Exception {
        Path outDir = Files.createTempDirectory("minimal-module-index");
        try {
            compile(source, fileName + ".java", outDir);
            return Files.readAllBytes(outDir.resolve(fileName + ".class"));
        } finally {
            deleteRecursively(outDir);
        }
    }

    private static void compile(String source, String fileName, Path outDir) throws Exception {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        StandardJavaFileManager fm = compiler.getStandardFileManager(
                null, Locale.getDefault(), StandardCharsets.UTF_8);
        fm.setLocation(StandardLocation.CLASS_OUTPUT, List.of(outDir.toFile()));
        JavaFileObject src = new SimpleJavaFileObject(
                URI.create("mem:///" + fileName), JavaFileObject.Kind.SOURCE) {
            @Override
            public CharSequence getCharContent(boolean ignoreEncodingErrors) {
                return source;
            }
        };
        assertTrue(compiler.getTask(null, fm, d -> {}, List.of(), List.of(), List.of(src)).call());
    }

    private static void deleteRecursively(Path root) throws Exception {
        if (!Files.exists(root)) return;
        Files.walk(root)
                .sorted(java.util.Comparator.reverseOrder())
                .forEach(p -> {
                    try {
                        Files.deleteIfExists(p);
                    } catch (Exception ignored) {
                    }
                });
    }
}
