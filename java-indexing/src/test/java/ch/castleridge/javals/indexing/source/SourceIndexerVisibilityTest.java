package ch.castleridge.javals.indexing.source;

import java.net.URI;
import java.util.Arrays;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.Opcodes;

import ch.castleridge.javals.indexing.index.Index;
import ch.castleridge.javals.indexing.model.MethodEntry;
import ch.castleridge.javals.indexing.model.TypeEntry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SourceIndexerVisibilityTest {
    private static final URI RESOURCE_URI = URI.create("mem:///Visible.java");
    private static final URI SOURCE_URI = URI.create("index:///source/");

    @Test
    void dropsPrivateMembersKeepsPackagePrivateAndPrivateCtor() {
        Index index = new Index();
        SourceIndexer.index(
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

        TypeEntry visible = index.get("p/Visible");
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
        assertNotNull(index.get("p/Visible$NestedPub"));
        assertNotNull(index.get("p/Visible$NestedPkg"));
        assertNull(index.get("p/Visible$NestedPriv"));
        assertFalse(Arrays.asList(visible.innerTypeJvmNames()).contains("p/Visible$NestedPriv"));
    }
}
