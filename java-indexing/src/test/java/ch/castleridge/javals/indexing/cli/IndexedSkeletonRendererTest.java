package ch.castleridge.javals.indexing.cli;

import ch.castleridge.javals.indexing.declaration.DeclarationIndex;
import ch.castleridge.javals.indexing.store.IndexEntry;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.Opcodes;

import java.net.URI;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IndexedSkeletonRendererTest {

    @Test
    void rendersPublicClassWithFieldAndMethod() {
        URI uri = URI.create("file:///tmp/Foo.class");
        List<IndexEntry> rows =
                List.of(
                        DeclarationIndex.typeRow(
                                uri,
                                "pkg/Foo",
                                "",
                                "java/lang/Object",
                                "",
                                "",
                                Opcodes.ACC_PUBLIC),
                        DeclarationIndex.fieldRow(
                                uri, "pkg/Foo", "x", "I", "I", "", Opcodes.ACC_PRIVATE),
                        DeclarationIndex.methodRow(
                                uri,
                                "pkg/Foo",
                                "run",
                                "()V",
                                "",
                                "V",
                                "",
                                "",
                                "",
                                Opcodes.ACC_PUBLIC));

        String out = IndexedSkeletonRenderer.renderAll(rows);
        assertTrue(out.contains("package pkg;"), out);
        assertTrue(out.contains("public class Foo"), out);
        assertTrue(out.contains("private int x;"), out);
        assertTrue(out.contains("public void run()"), out);
    }

    @Test
    void rendersInterfaceWithExtendsClause() {
        URI uri = URI.create("file:///tmp/Bar.class");
        List<IndexEntry> rows =
                List.of(
                        DeclarationIndex.typeRow(
                                uri,
                                "pkg/Bar",
                                "",
                                "java/lang/Object",
                                "java/lang/Runnable,java/io/Serializable",
                                "",
                                Opcodes.ACC_PUBLIC | Opcodes.ACC_INTERFACE | Opcodes.ACC_ABSTRACT));

        String out = IndexedSkeletonRenderer.renderAll(rows);
        assertTrue(out.contains("public interface Bar"), out);
        assertTrue(out.contains("extends java.lang.Runnable, java.io.Serializable"), out);
    }

    @Test
    void toJavaType_convertsInternalAndDescriptors() {
        assertEquals("java.lang.String", IndexedSkeletonRenderer.toJavaType("java/lang/String"));
        assertEquals("int", IndexedSkeletonRenderer.toJavaType("I"));
        assertEquals("int[]", IndexedSkeletonRenderer.toJavaType("[I"));
    }
}
