package ch.castleridge.javals.indexing.bytecode;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.StandardLocation;
import javax.tools.ToolProvider;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.Opcodes;

import ch.castleridge.javals.indexing.index.Index;
import ch.castleridge.javals.indexing.index.InMemoryIndex;
import ch.castleridge.javals.indexing.model.MethodEntry;
import ch.castleridge.javals.indexing.model.TypeEntry;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClassFileIndexerVisibilityTest {

    @Test
    void dropsPrivateMembersKeepsPackagePrivateAndPrivateCtor() throws Exception {
        Path outDir = Files.createTempDirectory("visibility-index");
        try {
            JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
            StandardJavaFileManager fm = compiler.getStandardFileManager(
                    null, Locale.getDefault(), java.nio.charset.StandardCharsets.UTF_8);
            fm.setLocation(StandardLocation.CLASS_OUTPUT, List.of(outDir.toFile()));
            JavaFileObject src = new SimpleJavaFileObject(
                    URI.create("mem:///Visible.java"), JavaFileObject.Kind.SOURCE) {
                @Override
                public CharSequence getCharContent(boolean ignoreEncodingErrors) {
                    return """
                            package p;
                            public class Visible {
                                public int pub;
                                int pkg;
                                private int priv;

                                public void pubM() {}
                                void pkgM() {}
                                private void privM() {}

                                private Visible() {}

                                public static class NestedPub {}
                                private static class NestedPriv {}
                            }
                            """;
                }
            };
            assertTrue(compiler.getTask(null, fm, d -> {}, List.of(), List.of(), List.of(src)).call());

            Index index = new InMemoryIndex();
            String sourceUri = "index:///cp/";
            for (String name : List.of(
                    "Visible.class",
                    "Visible$NestedPub.class",
                    "Visible$NestedPriv.class")) {
                Path classFile = outDir.resolve("p").resolve(name);
                ClassFileIndexer.index(
                        "index:///p/" + name,
                        sourceUri,
                        Files.readAllBytes(classFile),
                        index);
            }

            TypeEntry visible = ch.castleridge.javals.indexing.IndexTestUtils.get(index, "p/Visible");
            assertNotNull(visible);

            assertTrue(Arrays.stream(visible.fields()).anyMatch(f -> f.name().equals("pub")));
            assertTrue(Arrays.stream(visible.fields()).anyMatch(f -> f.name().equals("pkg")));
            assertTrue(Arrays.stream(visible.fields()).noneMatch(f -> f.name().equals("priv")));

            assertTrue(Arrays.stream(visible.methods()).anyMatch(m -> m.name().equals("pubM")));
            assertTrue(Arrays.stream(visible.methods()).anyMatch(m -> m.name().equals("pkgM")));
            assertTrue(Arrays.stream(visible.methods()).noneMatch(m -> m.name().equals("privM")));

            MethodEntry ctor = Arrays.stream(visible.methods())
                    .filter(m -> m.name().equals("<init>"))
                    .findFirst()
                    .orElseThrow();
            assertTrue((ctor.modifiers() & Opcodes.ACC_PRIVATE) != 0);

            assertTrue(Arrays.asList(visible.innerTypeJvmNames()).contains("p/Visible$NestedPub"));
            assertTrue(!Arrays.asList(visible.innerTypeJvmNames()).contains("p/Visible$NestedPriv"));
            assertNotNull(ch.castleridge.javals.indexing.IndexTestUtils.get(index, "p/Visible$NestedPub"));
            assertNull(ch.castleridge.javals.indexing.IndexTestUtils.get(index, "p/Visible$NestedPriv"));
        } finally {
            Files.walk(outDir)
                    .sorted(Comparator.reverseOrder())
                    .forEach(p -> {
                        try {
                            Files.deleteIfExists(p);
                        } catch (Exception ignored) {
                        }
                    });
        }
    }
}
