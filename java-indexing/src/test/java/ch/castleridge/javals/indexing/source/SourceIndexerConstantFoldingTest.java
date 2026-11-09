/**
 * Copyright 2026 by Castle Ridge Software GmbH
 *
 * Author: Thomas Mäder, Castle Ridge Software
 */
package ch.castleridge.javals.indexing.source;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.Arrays;
import java.util.stream.Stream;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import ch.castleridge.javals.indexing.IndexTestUtils;
import ch.castleridge.javals.indexing.index.InMemoryIndex;
import ch.castleridge.javals.indexing.index.Index;
import ch.castleridge.javals.indexing.model.FieldEntry;
import ch.castleridge.javals.indexing.model.TypeEntry;
import ch.castleridge.javals.indexing.source.ecj.EcjSourceIndexer;
import ch.castleridge.javals.indexing.source.javac.JavacSourceIndexer;
import ch.castleridge.javals.indexing.source.turbine.TurbineSourceIndexer;

/**
 * Same-type constant expression folding shared by all source indexers.
 */
class SourceIndexerConstantFoldingTest {
    private static final String RESOURCE_URI = "mem:///Constants.java";
    private static final String SOURCE_URI = "index:///source/";

    static Stream<SourceIndexer> indexers() {
        return Stream.of(
                JavacSourceIndexer.INSTANCE,
                EcjSourceIndexer.INSTANCE,
                TurbineSourceIndexer.INSTANCE);
    }

    @ParameterizedTest
    @MethodSource("indexers")
    void foldsSameTypeStringConcat(SourceIndexer indexer) {
        TypeEntry type = index(indexer, """
                package p;
                public class Constants {
                    public static final String PKG = "org.gradle";
                    public static final String FQCN = PKG + ".internal.Foo";
                }
                """);
        assertEquals("org.gradle", field(type, "PKG").constantValue());
        assertEquals("org.gradle.internal.Foo", field(type, "FQCN").constantValue());
        assertNull(field(type, "FQCN").constantOwner());
        assertNull(field(type, "FQCN").constantName());
    }

    @ParameterizedTest
    @MethodSource("indexers")
    void foldsForwardDeclaredSameTypeConcat(SourceIndexer indexer) {
        TypeEntry type = index(indexer, """
                package p;
                public class Constants {
                    public static final String FQCN = PKG + ".internal.Foo";
                    public static final String PKG = "org.gradle";
                }
                """);
        assertEquals("org.gradle.internal.Foo", field(type, "FQCN").constantValue());
    }

    @ParameterizedTest
    @MethodSource("indexers")
    void foldsChainedSameTypeConcat(SourceIndexer indexer) {
        TypeEntry type = index(indexer, """
                package p;
                public class Constants {
                    public static final String A = "a";
                    public static final String B = A + "b";
                    public static final String C = B + "c";
                }
                """);
        assertEquals("abc", field(type, "C").constantValue());
    }

    @ParameterizedTest
    @MethodSource("indexers")
    void foldsSameTypeIntAdd(SourceIndexer indexer) {
        TypeEntry type = index(indexer, """
                package p;
                public class Constants {
                    public static final int BASE = 1;
                    public static final int SUM = BASE + 2;
                }
                """);
        assertEquals(3, field(type, "SUM").constantValue());
    }

    @ParameterizedTest
    @MethodSource("indexers")
    void foldsInterfaceSameTypeConcat(SourceIndexer indexer) {
        TypeEntry type = index(indexer, """
                package p;
                public interface Constants {
                    String PKG = "org.gradle";
                    String FQCN = PKG + ".internal.Foo";
                }
                """);
        assertEquals("org.gradle.internal.Foo", field(type, "FQCN").constantValue());
    }

    @ParameterizedTest
    @MethodSource("indexers")
    void leavesCrossTypeConcatUnfolded(SourceIndexer indexer) {
        Index index = new InMemoryIndex();
        indexer.index(
                "mem:///Other.java",
                SOURCE_URI,
                """
                        package other;
                        public class Type {
                            public static final String CONST = "base";
                        }
                        """,
                index);
        indexer.index(
                RESOURCE_URI,
                SOURCE_URI,
                """
                        package p;
                        public class Constants {
                            public static final String X = other.Type.CONST + "z";
                        }
                        """,
                index);

        TypeEntry type = IndexTestUtils.get(index, "p/Constants");
        assertNotNull(type);
        FieldEntry x = field(type, "X");
        assertNull(x.constantValue());
        assertNull(x.constantOwner());
        assertNull(x.constantName());
    }

    private static TypeEntry index(SourceIndexer indexer, String source) {
        Index index = new InMemoryIndex();
        indexer.index(RESOURCE_URI, SOURCE_URI, source, index);
        TypeEntry type = IndexTestUtils.get(index, "p/Constants");
        assertNotNull(type);
        return type;
    }

    private static FieldEntry field(TypeEntry type, String name) {
        return Arrays.stream(type.fields())
                .filter(f -> name.equals(f.name()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("missing field " + name));
    }
}
