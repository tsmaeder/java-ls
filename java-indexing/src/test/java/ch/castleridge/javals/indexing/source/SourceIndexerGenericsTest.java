package ch.castleridge.javals.indexing.source;

import java.util.Arrays;

import org.junit.jupiter.api.Test;

import ch.castleridge.javals.indexing.index.Index;
import ch.castleridge.javals.indexing.index.InMemoryIndex;
import ch.castleridge.javals.indexing.model.FieldEntry;
import ch.castleridge.javals.indexing.model.MethodEntry;
import ch.castleridge.javals.indexing.model.TypeEntry;
import ch.castleridge.javals.indexing.model.Type;
import ch.castleridge.javals.indexing.model.TypeRef;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class SourceIndexerGenericsTest {
    private static final String RESOURCE_URI = "mem:///Test.java";
    private static final String SOURCE_URI = "index:///source/";

    @Test
    void expectingMethodPreservesWildcardAndTypeArguments() {
        TypeEntry entry = indexSingle(
                "package p;\n"
                        + "interface I<T> {\n"
                        + "    I<T> expecting(Expectation<? super T> expectation);\n"
                        + "}\n"
                        + "interface Expectation<X> {}\n",
                "p/I");

        MethodEntry expecting = method(entry, "expecting");
        assertInstanceOf(Type.Parameterized.class, expecting.returnType());
        Type.Parameterized returnType = (Type.Parameterized) expecting.returnType();
        assertInstanceOf(Type.TypeVariable.class, returnType.typeArgs()[0]);

        assertEquals(1, expecting.paramTypes().length);
        Type.Parameterized param = assertInstanceOf(Type.Parameterized.class, expecting.paramTypes()[0]);
        assertEquals(1, param.typeArgs().length);
        Type.Wildcard wildcard = assertInstanceOf(Type.Wildcard.class, param.typeArgs()[0]);
        assertEquals(Type.Wildcard.BoundKind.SUPER, wildcard.kind());
        assertInstanceOf(Type.TypeVariable.class, wildcard.bound());
        assertEquals("T", ((Type.TypeVariable) wildcard.bound()).name());
    }

    @Test
    void varargsMethodSetsVarargsFlag() {
        TypeEntry entry = indexSingle(
                "package p;\n"
                        + "class V {\n"
                        + "    static void all(Future<?>... results) {}\n"
                        + "}\n"
                        + "interface Future<T> {}\n",
                "p/V");

        MethodEntry all = method(entry, "all");
        org.junit.jupiter.api.Assertions.assertTrue(all.varargs(),
                "Future<?>... should be indexed as a varargs method");
    }

    @Test
    void qualifiedNestedTypeUsesDollarInJvmName() {
        TypeEntry entry = indexSingle(
                "package p;\n"
                        + "class Foo {\n"
                        + "    public interface Bar {}\n"
                        + "    Foo.Bar field;\n"
                        + "}\n",
                "p/Foo");

        FieldEntry field = Arrays.stream(entry.fields())
                .filter(f -> f.name().equals("field"))
                .findFirst()
                .orElseThrow();
        TypeRef.Resolved type = assertInstanceOf(TypeRef.Resolved.class, field.type());
        assertEquals("p/Foo$Bar", type.jvmBinaryName());
    }

    @Test
    void futureLikeInterfaceIndexesExpectingWithSuperWildcard() {
        TypeEntry entry = indexSingle(
                "package ch.castleridge.javals.test;\n"
                        + "interface Expectation<T> {}\n"
                        + "interface Future<T> {\n"
                        + "    Future<T> expecting(Expectation<? super T> expectation);\n"
                        + "}\n",
                "ch/castleridge/javals/test/Future");

        MethodEntry expecting = method(entry, "expecting");
        Type.Parameterized param = assertInstanceOf(Type.Parameterized.class, expecting.paramTypes()[0]);
        Type.Wildcard wildcard = assertInstanceOf(Type.Wildcard.class, param.typeArgs()[0]);
        assertEquals(Type.Wildcard.BoundKind.SUPER, wildcard.kind());
    }

    @Test
    void starImportedMemberSelectFieldTypeIsStoredPackageLess() {
        // Lazy model: the source indexer cannot know java.util.* binds
        // Base64, so it records the member select as the package-less binary
        // form Base64$Encoder. TypeRefResolver later qualifies it using the
        // entry's import hints + classpath. See TypeRefResolver#qualifyResolved.
        TypeEntry entry = indexSingle("""
                package example;
                import java.util.*;
                class Util {
                    static final Base64.Encoder ENCODER = null;
                }
                """, "example/Util");

        FieldEntry encoder = Arrays.stream(entry.fields())
                .filter(f -> "ENCODER".equals(f.name()))
                .findFirst()
                .orElseThrow();
        assertInstanceOf(TypeRef.Resolved.class, encoder.type());
        assertEquals("Base64$Encoder", ((TypeRef.Resolved) encoder.type()).jvmBinaryName());
    }

    private static TypeEntry indexSingle(String source, String jvmName) {
        Index index = new InMemoryIndex();
        SourceIndexer.index(RESOURCE_URI, SOURCE_URI, source, index);
        TypeEntry entry = ch.castleridge.javals.indexing.IndexTestUtils.get(index, jvmName);
        assertNotNull(entry, "Expected indexed type " + jvmName);
        return entry;
    }

    private static MethodEntry method(TypeEntry owner, String name) {
        return Arrays.stream(owner.methods())
                .filter(m -> m.name().equals(name))
                .findFirst()
                .orElseThrow();
    }
}
