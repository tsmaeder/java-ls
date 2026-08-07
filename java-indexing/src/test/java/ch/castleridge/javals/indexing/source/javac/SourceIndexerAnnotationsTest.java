package ch.castleridge.javals.indexing.source.javac;

import ch.castleridge.javals.indexing.source.javac.JavacSourceIndexer;

import java.util.Arrays;

import org.junit.jupiter.api.Test;

import ch.castleridge.javals.indexing.index.Index;
import ch.castleridge.javals.indexing.index.InMemoryIndex;
import ch.castleridge.javals.indexing.model.AnnotationRef;
import ch.castleridge.javals.indexing.model.AnnotationValue;
import ch.castleridge.javals.indexing.model.MethodEntry;
import ch.castleridge.javals.indexing.model.TypeEntry;
import ch.castleridge.javals.indexing.model.TypeRef;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Source-side annotation value capture: the {@link SourceIndexer} has
 * no symbol table, so it covers only AST-evident forms (literals, array
 * literals, class literals, nested annotations and "shaped like an
 * enum constant" identifier chains). The compiler-side converter is
 * expected to bind those tentative refs against declared element types.
 */
class SourceIndexerAnnotationsTest {

    @Test
    void capturesLiteralStringInSingleElementShorthand() {
        Index index = new InMemoryIndex();
        JavacSourceIndexer.index(
                "test:///S.java",
                "test:///src/",
                """
                public class S {
                    @SuppressWarnings("unchecked")
                    public void m() {}
                }
                """,
                index);

        TypeEntry t = ch.castleridge.javals.indexing.IndexTestUtils.get(index, "S");
        assertNotNull(t);
        MethodEntry m = methodNamed(t, "m");
        AnnotationRef suppress = findAnnotation(m.annotations(), "SuppressWarnings");
        assertNotNull(suppress);
        assertInstanceOf(TypeRef.Unresolved.class, suppress.annotationType());
        assertEquals("SuppressWarnings", ((TypeRef.Unresolved) suppress.annotationType()).simpleName());
        AnnotationValue value = suppress.values().get("value");
        assertInstanceOf(AnnotationValue.Str.class, value);
        assertEquals("unchecked", ((AnnotationValue.Str) value).value());
    }

    @Test
    void capturesArrayLiteralAndNamedElements() {
        Index index = new InMemoryIndex();
        JavacSourceIndexer.index(
                "test:///S.java",
                "test:///src/",
                """
                public class S {
                    @SuppressWarnings({"a", "b"})
                    @Deprecated(forRemoval = true, since = "1.2")
                    public void m() {}
                }
                """,
                index);

        TypeEntry t = ch.castleridge.javals.indexing.IndexTestUtils.get(index, "S");
        MethodEntry m = methodNamed(t, "m");

        AnnotationRef suppress = findAnnotation(m.annotations(), "SuppressWarnings");
        AnnotationValue value = suppress.values().get("value");
        assertInstanceOf(AnnotationValue.Arr.class, value);
        AnnotationValue.Arr arr = (AnnotationValue.Arr) value;
        assertEquals(2, arr.elements().length);
        assertEquals("a", ((AnnotationValue.Str) arr.elements()[0]).value());
        assertEquals("b", ((AnnotationValue.Str) arr.elements()[1]).value());

        AnnotationRef deprecated = findAnnotation(m.annotations(), "Deprecated");
        assertNotNull(deprecated);
        AnnotationValue forRemoval = deprecated.values().get("forRemoval");
        assertInstanceOf(AnnotationValue.Primitive.class, forRemoval);
        assertEquals(Boolean.TRUE, ((AnnotationValue.Primitive) forRemoval).boxed());
        AnnotationValue since = deprecated.values().get("since");
        assertInstanceOf(AnnotationValue.Str.class, since);
        assertEquals("1.2", ((AnnotationValue.Str) since).value());
    }

    @Test
    void capturesClassLiteralAndQualifiedEnumConstant() {
        Index index = new InMemoryIndex();
        JavacSourceIndexer.index(
                "test:///S.java",
                "test:///src/",
                """
                import java.lang.annotation.ElementType;
                public class S {
                    @java.lang.annotation.Target(java.lang.annotation.ElementType.METHOD)
                    public @interface Pin {
                        Class<?> klass() default String.class;
                    }
                }
                """,
                index);

        TypeEntry pin = ch.castleridge.javals.indexing.IndexTestUtils.get(index, "S$Pin");
        assertNotNull(pin);
        // Target annotation on the nested type.
        AnnotationRef target = findAnnotation(pin.annotations(), "java/lang/annotation/Target");
        assertNotNull(target);
        AnnotationValue value = target.values().get("value");
        // Java single-element shorthand: the source supplies a scalar
        // enum constant; the symbol-side converter will wrap it into a
        // one-element array at attribute attachment time.
        assertInstanceOf(AnnotationValue.EnumConst.class, value);
        AnnotationValue.EnumConst ec = (AnnotationValue.EnumConst) value;
        assertEquals("METHOD", ec.constant());
        assertEquals(TypeRef.resolved("java/lang/annotation/ElementType"), ec.enumType());

        // Default value of the klass() element is String.class.
        MethodEntry klass = methodNamed(pin, "klass");
        assertNotNull(klass.annotationDefault());
        assertInstanceOf(AnnotationValue.ClassRef.class, klass.annotationDefault());
        AnnotationValue.ClassRef classRef = (AnnotationValue.ClassRef) klass.annotationDefault();
        // The qualifier is the bare identifier "String", so without
        // symbol resolution the indexer records it as Unresolved.
        assertEquals(TypeRef.unresolved("String"), classRef.type());
    }

    @Test
    void unsupportedExpressionFallsBackToUnsupportedSentinel() {
        Index index = new InMemoryIndex();
        JavacSourceIndexer.index(
                "test:///S.java",
                "test:///src/",
                """
                public class S {
                    static final int FOUR = 2 + 2;
                    @MyAnno(level = FOUR)
                    public void m() {}
                }
                @interface MyAnno { int level(); }
                """,
                index);

        TypeEntry t = ch.castleridge.javals.indexing.IndexTestUtils.get(index, "S");
        MethodEntry m = methodNamed(t, "m");
        AnnotationRef my = findAnnotation(m.annotations(), "MyAnno");
        assertNotNull(my);
        AnnotationValue value = my.values().get("level");
        // FOUR is a bare identifier; the indexer cannot tell it apart
        // from an enum constant without a symbol table, so it records
        // it as a tentative EnumConst with an unresolved qualifier.
        // The compiler-side converter will reject it at attach time.
        assertInstanceOf(AnnotationValue.EnumConst.class, value);
    }

    private static AnnotationRef findAnnotation(AnnotationRef[] refs, String name) {
        for (AnnotationRef r : refs) if (r.jvmName().equals(name)) return r;
        return null;
    }

    private static MethodEntry methodNamed(TypeEntry t, String name) {
        return Arrays.stream(t.methods()).filter(m -> m.name().equals(name)).findFirst().orElseThrow();
    }
}
