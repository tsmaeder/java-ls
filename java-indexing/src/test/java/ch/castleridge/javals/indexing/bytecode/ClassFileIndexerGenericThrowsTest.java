package ch.castleridge.javals.indexing.bytecode;

import java.net.URI;
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
import ch.castleridge.javals.indexing.model.MethodEntry;
import ch.castleridge.javals.indexing.model.Type;
import ch.castleridge.javals.indexing.model.TypeEntry;
import ch.castleridge.javals.indexing.model.TypeRef;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClassFileIndexerGenericThrowsTest {

    @Test
    void genericThrowsTypeVariableIndexedFromSignature() throws Exception {
        Path outDir = Files.createTempDirectory("generic-throws-index");
        try {
            JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
            StandardJavaFileManager fm = compiler.getStandardFileManager(
                    null, Locale.getDefault(), java.nio.charset.StandardCharsets.UTF_8);
            fm.setLocation(StandardLocation.CLASS_OUTPUT, List.of(outDir.toFile()));
            JavaFileObject src = new SimpleJavaFileObject(
                    URI.create("mem:///Sneaky.java"), JavaFileObject.Kind.SOURCE) {
                @Override
                public CharSequence getCharContent(boolean ignoreEncodingErrors) {
                    return """
                            class Sneaky {
                                @SuppressWarnings("unchecked")
                                static <E extends Throwable> void rethrow(Throwable t) throws E {
                                    throw (E) t;
                                }
                            }
                            """;
                }
            };
            assertTrue(compiler.getTask(null, fm, d -> {}, List.of(), List.of(), List.of(src)).call());

            Index index = new Index();
            ClassFileIndexer.index(
                    URI.create("index:///Sneaky.class"),
                    URI.create("index:///cp/"),
                    Files.readAllBytes(outDir.resolve("Sneaky.class")),
                    index);

            TypeEntry sneaky = index.get("Sneaky");
            assertNotNull(sneaky);
            MethodEntry rethrow = sneaky.methods().stream()
                    .filter(m -> m.name().equals("rethrow"))
                    .findFirst()
                    .orElseThrow();
            assertEquals(1, rethrow.throwsTypes().size());
            Type thrown = rethrow.throwsTypes().get(0);
            assertInstanceOf(Type.TypeVariable.class, thrown);
            assertEquals("E", ((Type.TypeVariable) thrown).name());
        } finally {
            Files.walk(outDir)
                    .sorted(java.util.Comparator.reverseOrder())
                    .forEach(p -> {
                        try {
                            Files.deleteIfExists(p);
                        } catch (Exception ignored) {
                        }
                    });
        }
    }

    @Test
    void erasedThrowsUsedWhenSignatureHasNoThrowsClause() throws Exception {
        Path outDir = Files.createTempDirectory("erased-throws-index");
        try {
            JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
            StandardJavaFileManager fm = compiler.getStandardFileManager(
                    null, Locale.getDefault(), java.nio.charset.StandardCharsets.UTF_8);
            fm.setLocation(StandardLocation.CLASS_OUTPUT, List.of(outDir.toFile()));
            JavaFileObject src = new SimpleJavaFileObject(
                    URI.create("mem:///Throws.java"), JavaFileObject.Kind.SOURCE) {
                @Override
                public CharSequence getCharContent(boolean ignoreEncodingErrors) {
                    return """
                            class Throws {
                                static void fail() throws java.io.IOException {}
                            }
                            """;
                }
            };
            assertTrue(compiler.getTask(null, fm, d -> {}, List.of(), List.of(), List.of(src)).call());

            Index index = new Index();
            ClassFileIndexer.index(
                    URI.create("index:///Throws.class"),
                    URI.create("index:///cp/"),
                    Files.readAllBytes(outDir.resolve("Throws.class")),
                    index);

            TypeEntry throwsClass = index.get("Throws");
            assertNotNull(throwsClass);
            MethodEntry fail = throwsClass.methods().stream()
                    .filter(m -> m.name().equals("fail"))
                    .findFirst()
                    .orElseThrow();
            assertEquals(1, fail.throwsTypes().size());
            assertInstanceOf(TypeRef.Resolved.class, fail.throwsTypes().get(0));
            assertEquals("java/io/IOException", ((TypeRef.Resolved) fail.throwsTypes().get(0)).jvmBinaryName());
        } finally {
            Files.walk(outDir)
                    .sorted(java.util.Comparator.reverseOrder())
                    .forEach(p -> {
                        try {
                            Files.deleteIfExists(p);
                        } catch (Exception ignored) {
                        }
                    });
        }
    }
}
