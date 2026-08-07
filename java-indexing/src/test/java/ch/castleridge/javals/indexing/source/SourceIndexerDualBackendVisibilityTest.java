package ch.castleridge.javals.indexing.source;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.stream.Stream;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.objectweb.asm.Opcodes;

import ch.castleridge.javals.indexing.IndexTestUtils;
import ch.castleridge.javals.indexing.index.InMemoryIndex;
import ch.castleridge.javals.indexing.index.Index;
import ch.castleridge.javals.indexing.model.MethodEntry;
import ch.castleridge.javals.indexing.model.TypeEntry;
import ch.castleridge.javals.indexing.source.ecj.EcjSourceIndexer;
import ch.castleridge.javals.indexing.source.javac.JavacSourceIndexer;

/**
 * Dual-run visibility contract shared by javac and ECJ source indexers.
 */
class SourceIndexerDualBackendVisibilityTest {
    private static final String RESOURCE_URI = "mem:///Visible.java";
    private static final String SOURCE_URI = "index:///source/";

    static Stream<SourceIndexer> indexers() {
        return Stream.of(JavacSourceIndexer.INSTANCE, EcjSourceIndexer.INSTANCE);
    }

    @ParameterizedTest
    @MethodSource("indexers")
    void dropsPrivateMembersKeepsPackagePrivateAndPrivateCtor(SourceIndexer indexer) {
        Index index = new InMemoryIndex();
        indexer.index(
                RESOURCE_URI,
                SOURCE_URI,
                """
                        package p;

                        public class Visible {
                            public int pub;
                            protected int prot;
                            int pkg;
                            private int priv;

                            public void pubM() {}
                            void pkgM() {}
                            private void privM() {}

                            private Visible() {}

                            public static class NestedPub {}
                            static class NestedPkg {}
                            private static class NestedPriv {}
                        }
                        """,
                index);

        TypeEntry visible = IndexTestUtils.get(index, "p/Visible");
        assertNotNull(visible);

        assertTrue(Arrays.stream(visible.fields()).anyMatch(f -> f.name().equals("pub")));
        assertTrue(Arrays.stream(visible.fields()).anyMatch(f -> f.name().equals("prot")));
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

        assertEquals(
                Arrays.asList("p/Visible$NestedPub", "p/Visible$NestedPkg"),
                Arrays.asList(visible.innerTypeJvmNames()));
        assertNotNull(IndexTestUtils.get(index, "p/Visible$NestedPub"));
        assertNotNull(IndexTestUtils.get(index, "p/Visible$NestedPkg"));
        assertNull(IndexTestUtils.get(index, "p/Visible$NestedPriv"));
        assertFalse(Arrays.asList(visible.innerTypeJvmNames()).contains("p/Visible$NestedPriv"));
    }
}
