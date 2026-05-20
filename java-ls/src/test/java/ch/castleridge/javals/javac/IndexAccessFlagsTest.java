package ch.castleridge.javals.javac;

import java.net.URI;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.Opcodes;

import ch.castleridge.javals.indexing.index.Index;
import ch.castleridge.javals.indexing.model.FieldEntry;
import ch.castleridge.javals.indexing.model.MethodEntry;
import ch.castleridge.javals.indexing.model.TypeEntry;
import ch.castleridge.javals.indexing.source.SourceIndexer;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IndexAccessFlagsTest {
    private static final URI RESOURCE_URI = URI.create("mem:///Test.java");
    private static final URI SOURCE_URI = URI.create("index:///source/");

    @Test
    void interfaceMembersApplyImplicitClassfileFlags() {
        TypeEntry entry = indexSingle(
                "package p;\n"
                        + "interface I {\n"
                        + "    int X = 1;\n"
                        + "    void a();\n"
                        + "    default void d() {}\n"
                        + "    static void s() {}\n"
                        + "    private void p() {}\n"
                        + "    private static void ps() {}\n"
                        + "}\n",
                "p/I");

        FieldEntry x = field(entry, "X");
        assertHas(IndexAccessFlags.fieldFlags(entry, x), Opcodes.ACC_PUBLIC);
        assertHas(IndexAccessFlags.fieldFlags(entry, x), Opcodes.ACC_STATIC);
        assertHas(IndexAccessFlags.fieldFlags(entry, x), Opcodes.ACC_FINAL);

        MethodEntry a = method(entry, "a");
        assertHas(IndexAccessFlags.methodFlags(entry, a), Opcodes.ACC_PUBLIC);
        assertHas(IndexAccessFlags.methodFlags(entry, a), Opcodes.ACC_ABSTRACT);

        MethodEntry d = method(entry, "d");
        assertHas(IndexAccessFlags.methodFlags(entry, d), Opcodes.ACC_PUBLIC);
        assertLacks(IndexAccessFlags.methodFlags(entry, d), Opcodes.ACC_ABSTRACT);

        MethodEntry s = method(entry, "s");
        assertHas(IndexAccessFlags.methodFlags(entry, s), Opcodes.ACC_PUBLIC);
        assertHas(IndexAccessFlags.methodFlags(entry, s), Opcodes.ACC_STATIC);
        assertLacks(IndexAccessFlags.methodFlags(entry, s), Opcodes.ACC_ABSTRACT);

        MethodEntry p = method(entry, "p");
        assertHas(IndexAccessFlags.methodFlags(entry, p), Opcodes.ACC_PRIVATE);
        assertLacks(IndexAccessFlags.methodFlags(entry, p), Opcodes.ACC_PUBLIC);

        MethodEntry ps = method(entry, "ps");
        assertHas(IndexAccessFlags.methodFlags(entry, ps), Opcodes.ACC_PRIVATE);
        assertHas(IndexAccessFlags.methodFlags(entry, ps), Opcodes.ACC_STATIC);
        assertLacks(IndexAccessFlags.methodFlags(entry, ps), Opcodes.ACC_PUBLIC);
    }

    @Test
    void annotationMembersApplyImplicitClassfileFlags() {
        TypeEntry entry = indexSingle(
                "package p;\n"
                        + "@interface A {\n"
                        + "    int C = 1;\n"
                        + "    String value();\n"
                        + "}\n",
                "p/A");

        FieldEntry c = field(entry, "C");
        assertHas(IndexAccessFlags.fieldFlags(entry, c), Opcodes.ACC_PUBLIC);
        assertHas(IndexAccessFlags.fieldFlags(entry, c), Opcodes.ACC_STATIC);
        assertHas(IndexAccessFlags.fieldFlags(entry, c), Opcodes.ACC_FINAL);

        MethodEntry value = method(entry, "value");
        assertHas(IndexAccessFlags.methodFlags(entry, value), Opcodes.ACC_PUBLIC);
        assertHas(IndexAccessFlags.methodFlags(entry, value), Opcodes.ACC_ABSTRACT);
    }

    @Test
    void varargsMethodsCarryAccVarargs() {
        TypeEntry entry = indexSingle(
                "package p;\n"
                        + "class V {\n"
                        + "    void varg(String... xs) {}\n"
                        + "    void arr(String[] xs) {}\n"
                        + "}\n",
                "p/V");

        MethodEntry varg = method(entry, "varg");
        MethodEntry arr = method(entry, "arr");
        assertHas(IndexAccessFlags.methodFlags(entry, varg), Opcodes.ACC_VARARGS);
        assertLacks(IndexAccessFlags.methodFlags(entry, arr), Opcodes.ACC_VARARGS);
    }

    private static TypeEntry indexSingle(String source, String jvmName) {
        Index index = new Index();
        SourceIndexer.index(RESOURCE_URI, SOURCE_URI, source, index);
        TypeEntry entry = index.get(jvmName);
        assertNotNull(entry, "Expected indexed type " + jvmName);
        return entry;
    }

    private static FieldEntry field(TypeEntry owner, String name) {
        return owner.fields().stream()
                .filter(f -> f.name().equals(name))
                .findFirst()
                .orElseThrow();
    }

    private static MethodEntry method(TypeEntry owner, String name) {
        return owner.methods().stream()
                .filter(m -> m.name().equals(name))
                .findFirst()
                .orElseThrow();
    }

    private static void assertHas(int flags, int expectedBit) {
        assertTrue((flags & expectedBit) != 0,
                () -> "Expected bit 0x" + Integer.toHexString(expectedBit) + " in flags 0x" + Integer.toHexString(flags));
    }

    private static void assertLacks(int flags, int expectedAbsentBit) {
        assertTrue((flags & expectedAbsentBit) == 0,
                () -> "Did not expect bit 0x" + Integer.toHexString(expectedAbsentBit) + " in flags 0x" + Integer.toHexString(flags));
    }
}
