package ch.castleridge.javals.indexing.source;

import java.net.URI;

import org.junit.jupiter.api.Test;

import ch.castleridge.javals.indexing.index.Index;
import ch.castleridge.javals.indexing.model.MethodEntry;
import ch.castleridge.javals.indexing.model.TypeEntry;
import ch.castleridge.javals.indexing.model.TypeRef;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class SourceIndexerGenericsTest {
    private static final URI RESOURCE_URI = URI.create("mem:///Test.java");
    private static final URI SOURCE_URI = URI.create("index:///source/");

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
        assertInstanceOf(TypeRef.Parameterized.class, expecting.returnType());
        TypeRef.Parameterized returnType = (TypeRef.Parameterized) expecting.returnType();
        assertInstanceOf(TypeRef.TypeVariable.class, returnType.typeArgs().get(0));

        assertEquals(1, expecting.paramTypes().size());
        TypeRef.Parameterized param = assertInstanceOf(TypeRef.Parameterized.class, expecting.paramTypes().get(0));
        assertEquals(1, param.typeArgs().size());
        TypeRef.Wildcard wildcard = assertInstanceOf(TypeRef.Wildcard.class, param.typeArgs().get(0));
        assertEquals(TypeRef.Wildcard.BoundKind.SUPER, wildcard.kind());
        assertInstanceOf(TypeRef.TypeVariable.class, wildcard.bound());
        assertEquals("T", ((TypeRef.TypeVariable) wildcard.bound()).name());
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
    void futureLikeInterfaceIndexesExpectingWithSuperWildcard() {
        TypeEntry entry = indexSingle(
                "package ch.castleridge.javals.test;\n"
                        + "interface Expectation<T> {}\n"
                        + "interface Future<T> {\n"
                        + "    Future<T> expecting(Expectation<? super T> expectation);\n"
                        + "}\n",
                "ch/castleridge/javals/test/Future");

        MethodEntry expecting = method(entry, "expecting");
        TypeRef.Parameterized param = assertInstanceOf(TypeRef.Parameterized.class, expecting.paramTypes().get(0));
        TypeRef.Wildcard wildcard = assertInstanceOf(TypeRef.Wildcard.class, param.typeArgs().get(0));
        assertEquals(TypeRef.Wildcard.BoundKind.SUPER, wildcard.kind());
    }

    private static TypeEntry indexSingle(String source, String jvmName) {
        Index index = new Index();
        SourceIndexer.index(RESOURCE_URI, SOURCE_URI, source, index);
        TypeEntry entry = index.get(jvmName);
        assertNotNull(entry, "Expected indexed type " + jvmName);
        return entry;
    }

    private static MethodEntry method(TypeEntry owner, String name) {
        return owner.methods().stream()
                .filter(m -> m.name().equals(name))
                .findFirst()
                .orElseThrow();
    }
}
