package ch.castleridge.javals.javac;

import java.net.URI;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.Opcodes;

import com.sun.tools.javac.code.Flags;

import ch.castleridge.javals.indexing.index.Index;
import ch.castleridge.javals.indexing.model.ClassFileTypeEntry;
import ch.castleridge.javals.indexing.model.EmptyArrays;
import ch.castleridge.javals.indexing.model.FieldEntry;
import ch.castleridge.javals.indexing.model.MethodEntry;
import ch.castleridge.javals.indexing.model.SourceTypeEntry;
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
        assertHas(IndexAccessFlags.methodFlags(entry, d), Flags.DEFAULT);

        MethodEntry s = method(entry, "s");
        assertHas(IndexAccessFlags.methodFlags(entry, s), Opcodes.ACC_PUBLIC);
        assertHas(IndexAccessFlags.methodFlags(entry, s), Opcodes.ACC_STATIC);
        assertLacks(IndexAccessFlags.methodFlags(entry, s), Opcodes.ACC_ABSTRACT);
        // Static interface methods are not "default" methods.
        assertLacks(IndexAccessFlags.methodFlags(entry, s), Flags.DEFAULT);

        assertTrue(Arrays.stream(entry.methods()).noneMatch(m -> m.name().equals("p")),
                "private interface methods must not be indexed");
        assertTrue(Arrays.stream(entry.methods()).noneMatch(m -> m.name().equals("ps")),
                "private static interface methods must not be indexed");
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
    void classFlagsStripAccSuperBitForBytecodeEntries() {
        TypeEntry bytecode = new ClassFileTypeEntry(
                "index:///p/C.class",
                "index:///bytecode/",
                "p/C",
                Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER,
                null,
                EmptyArrays.TYPE,
                EmptyArrays.TYPE_PARAM,
                EmptyArrays.FIELD,
                EmptyArrays.METHOD,
                EmptyArrays.STRING,
                EmptyArrays.ANNOTATION_REF);
        long flags = IndexAccessFlags.classFlags(bytecode);
        assertLacks(flags, Opcodes.ACC_SUPER);
        assertHas(flags, Opcodes.ACC_PUBLIC);
    }

    @Test
    void classFlagsStripAccSuperBitForSourceEntries() {
        TypeEntry entry = indexSingle(
                "package p;\n"
                        + "public class C {}\n",
                "p/C");
        // Source modifiers don't normally include ACC_SUPER, but make
        // sure the masking still applies if it ever slips through.
        SourceTypeEntry source = (SourceTypeEntry) entry;
        TypeEntry tampered = new SourceTypeEntry(
                entry.resourceUri(),
                entry.sourceUri(),
                entry.jvmOwnerName(),
                entry.modifiers() | Opcodes.ACC_SUPER,
                source.declKind(),
                entry.superRef(),
                entry.interfaceRefs(),
                entry.typeParams(),
                entry.fields(),
                entry.methods(),
                entry.innerTypeJvmNames(),
                entry.permittedSubclasses(),
                entry.recordComponents(),
                entry.annotations(),
                source.hints());
        long flags = IndexAccessFlags.classFlags(tampered);
        assertLacks(flags, Opcodes.ACC_SUPER);
    }

    @Test
    void classFlagsMapAccModuleToFlagsModule() {
        TypeEntry moduleInfo = new ClassFileTypeEntry(
                "index:///module-info.class",
                "index:///bytecode/",
                "io/example/module-info",
                Opcodes.ACC_MODULE,
                null,
                EmptyArrays.TYPE,
                EmptyArrays.TYPE_PARAM,
                EmptyArrays.FIELD,
                EmptyArrays.METHOD,
                EmptyArrays.STRING,
                EmptyArrays.ANNOTATION_REF);
        long flags = IndexAccessFlags.classFlags(moduleInfo);
        assertHas(flags, Flags.MODULE);
        assertLacks(flags, Opcodes.ACC_MODULE);
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
        assertHas(IndexAccessFlags.methodFlags(entry, varg), Flags.VARARGS);
        assertLacks(IndexAccessFlags.methodFlags(entry, arr), Flags.VARARGS);
    }

    private static TypeEntry indexSingle(String source, String jvmName) {
        Index index = new Index();
        SourceIndexer.index(RESOURCE_URI, SOURCE_URI, source, index);
        TypeEntry entry = index.get(jvmName);
        assertNotNull(entry, "Expected indexed type " + jvmName);
        return entry;
    }

    private static FieldEntry field(TypeEntry owner, String name) {
        return Arrays.stream(owner.fields())
                .filter(f -> f.name().equals(name))
                .findFirst()
                .orElseThrow();
    }

    private static MethodEntry method(TypeEntry owner, String name) {
        return Arrays.stream(owner.methods())
                .filter(m -> m.name().equals(name))
                .findFirst()
                .orElseThrow();
    }

    private static void assertHas(long flags, long expectedBit) {
        assertTrue((flags & expectedBit) != 0,
                () -> "Expected bit 0x" + Long.toHexString(expectedBit) + " in flags 0x" + Long.toHexString(flags));
    }

    private static void assertLacks(long flags, long expectedAbsentBit) {
        assertTrue((flags & expectedAbsentBit) == 0,
                () -> "Did not expect bit 0x" + Long.toHexString(expectedAbsentBit) + " in flags 0x" + Long.toHexString(flags));
    }
}
