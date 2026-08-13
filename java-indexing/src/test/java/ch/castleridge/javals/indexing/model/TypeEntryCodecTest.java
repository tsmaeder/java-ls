/**
 * Copyright 2026 by Anysphere Inc.
 * 
 * Licensed under the MIT License.
 * 
 * SPDX-License-Identifier: MIT
 *
 * Author: Thomas Mäder, Castle Ridge Software
 *
 */
package ch.castleridge.javals.indexing.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import org.junit.jupiter.api.Test;

import ch.castleridge.javals.indexing.index.Index;
import ch.castleridge.javals.indexing.index.InMemoryIndex;

/**
 * Round-trip tests for {@link TypeEntryCodec}.
 *
 * <p>Note: Java record {@code equals} compares array components by
 * <em>identity</em> (via {@code ObjectMethods}), not
 * {@link Arrays#equals}. Deep content comparison is done explicitly here.
 */
class TypeEntryCodecTest {

    @Test
    void roundTripsSparseClassFileTypeEntry() {
        ClassFileTypeEntry original = new ClassFileTypeEntry(
                "com/example/Hello.class",
                "file:///lib.jar",
                "com/example/Hello",
                0x0021,
                TypeRef.resolved("java/lang/Object"),
                EmptyArrays.TYPE,
                EmptyArrays.TYPE_PARAM,
                EmptyArrays.FIELD,
                EmptyArrays.METHOD,
                EmptyArrays.STRING,
                EmptyArrays.TYPE_REF,
                EmptyArrays.RECORD_COMPONENT,
                EmptyArrays.ANNOTATION_REF);

        TypeEntry decoded = TypeEntryCodec.decode(TypeEntryCodec.encode(original));
        assertInstanceOf(ClassFileTypeEntry.class, decoded);
        assertDeepEquals(original, decoded);
    }

    @Test
    void roundTripsRichSourceEntry() {
        Type listOfString = Type.parameterized(
                TypeRef.resolved("java/util/List"),
                new Type[]{TypeRef.resolved("java/lang/String")});
        Type annotated = Type.Annotated.wrap(
                TypeRef.resolved("java/lang/String"),
                new AnnotationRef[]{
                        new AnnotationRef(TypeRef.resolved("java/lang/Deprecated"), Map.of())
                });

        Map<String, AnnotationValue> suppressValues = new LinkedHashMap<>();
        suppressValues.put("value", new AnnotationValue.Arr(new AnnotationValue[]{
                new AnnotationValue.Str("unchecked"),
                new AnnotationValue.Str("rawtypes")
        }));

        Map<String, AnnotationValue> nestedValues = new LinkedHashMap<>();
        nestedValues.put("flag", new AnnotationValue.Primitive(Boolean.TRUE));
        nestedValues.put("count", new AnnotationValue.Primitive(Integer.valueOf(42)));
        nestedValues.put("ratio", new AnnotationValue.Primitive(Double.valueOf(1.5)));
        nestedValues.put("label", new AnnotationValue.Str("x"));
        nestedValues.put("type", new AnnotationValue.ClassRef(TypeRef.resolved("java/lang/String")));
        nestedValues.put("kind", new AnnotationValue.EnumConst(
                TypeRef.resolved("java/lang/annotation/ElementType"), "METHOD"));
        nestedValues.put("inner", new AnnotationValue.Nested(
                new AnnotationRef(TypeRef.unresolved("Inner"), Map.of())));
        nestedValues.put("unknown", new AnnotationValue.Unsupported("expr"));

        FieldEntry field = new FieldEntry(
                0x0019,
                "SIZE",
                Type.Primitive.INT,
                Integer.valueOf(16),
                new AnnotationRef[]{
                        new AnnotationRef(TypeRef.resolved("java/lang/Deprecated"), Map.of())
                });

        MethodEntry method = new MethodEntry(
                0x0001,
                "map",
                listOfString,
                new ParameterEntry[]{
                        new ParameterEntry("input", 0x0010, annotated, EmptyArrays.ANNOTATION_REF)
                },
                new Type[]{TypeRef.resolved("java/io/IOException")},
                new TypeParamRef[]{TypeParamRef.of("T"), new TypeParamRef("U", new Type[]{
                        Type.parameterized(
                                TypeRef.resolved("java/lang/Comparable"),
                                new Type[]{Type.typeVariable("U")})
                })},
                true,
                true,
                new AnnotationValue.Str("default"),
                new AnnotationRef[]{
                        new AnnotationRef(TypeRef.resolved("java/lang/SuppressWarnings"), suppressValues),
                        new AnnotationRef(TypeRef.unresolved("Meta"), nestedValues)
                });

        SourceResolutionHints hints = new SourceResolutionHints(
                "com/example",
                Map.of("List", "java/util/List", "Map", "java/util/Map"),
                new String[]{"java/util", "java/io"},
                Set.of("Hello", "Helper"));

        SourceTypeEntry original = new SourceTypeEntry(
                "com/example/Hello.java",
                "file:///src/",
                "com/example/Hello",
                0x0001,
                TypeDeclKind.CLASS,
                Type.parameterized(
                        TypeRef.resolved("java/lang/Enum"),
                        new Type[]{TypeRef.unresolved("Hello")}),
                new Type[]{
                        TypeRef.resolved("java/io/Serializable"),
                        Type.Wildcard.extendsBound(TypeRef.resolved("java/lang/Number"))
                },
                new TypeParamRef[]{TypeParamRef.of("E")},
                new FieldEntry[]{field},
                new MethodEntry[]{method},
                new String[]{"com/example/Hello$Inner"},
                new TypeRef[]{TypeRef.resolved("com/example/Hello$A")},
                new RecordComponentEntry[]{
                        new RecordComponentEntry(
                                "name",
                                TypeRef.resolved("java/lang/String"),
                                EmptyArrays.ANNOTATION_REF)
                },
                new AnnotationRef[]{
                        new AnnotationRef(TypeRef.resolved("java/lang/Deprecated"), Map.of())
                },
                hints);

        TypeEntry decoded = TypeEntryCodec.decode(TypeEntryCodec.encode(original));
        assertInstanceOf(SourceTypeEntry.class, decoded);
        assertDeepEquals(original, decoded);
    }

    @Test
    void roundTripsTypeParamWithDefaultObjectBound() {
        TypeParamRef original = TypeParamRef.of("E");
        ClassFileTypeEntry entry = new ClassFileTypeEntry(
                "pkg/A.class", "file:///a.jar", "pkg/A", 1,
                null, EmptyArrays.TYPE,
                new TypeParamRef[]{original},
                EmptyArrays.FIELD, EmptyArrays.METHOD, EmptyArrays.STRING,
                EmptyArrays.TYPE_REF, EmptyArrays.RECORD_COMPONENT, EmptyArrays.ANNOTATION_REF);
        ClassFileTypeEntry decoded = (ClassFileTypeEntry) TypeEntryCodec.decode(TypeEntryCodec.encode(entry));
        assertTrue(deepEquals(original, decoded.typeParams()[0]));
    }

    @Test
    void roundTripsFieldConstantValues() {
        FieldEntry[] fields = new FieldEntry[]{
                new FieldEntry(9, "S", TypeRef.resolved("java/lang/String"), "hi",
                        EmptyArrays.ANNOTATION_REF),
                new FieldEntry(9, "L", Type.Primitive.LONG, Long.valueOf(7L),
                        EmptyArrays.ANNOTATION_REF),
                new FieldEntry(9, "F", Type.Primitive.FLOAT, Float.valueOf(1.25f),
                        EmptyArrays.ANNOTATION_REF),
                new FieldEntry(9, "D", Type.Primitive.DOUBLE, Double.valueOf(2.5),
                        EmptyArrays.ANNOTATION_REF),
                new FieldEntry(9, "I", Type.Primitive.INT, Integer.valueOf(-3),
                        EmptyArrays.ANNOTATION_REF)
        };
        ClassFileTypeEntry original = new ClassFileTypeEntry(
                "pkg/Holder.class", "file:///a.jar", "pkg/Holder", 1,
                null, EmptyArrays.TYPE, EmptyArrays.TYPE_PARAM,
                fields, EmptyArrays.METHOD, EmptyArrays.STRING,
                EmptyArrays.TYPE_REF, EmptyArrays.RECORD_COMPONENT, EmptyArrays.ANNOTATION_REF);
        ClassFileTypeEntry decoded = (ClassFileTypeEntry) TypeEntryCodec.decode(TypeEntryCodec.encode(original));
        for (int i = 0; i < fields.length; i++) {
            assertEquals(fields[i].constantValue(), decoded.fields()[i].constantValue(),
                    "constant for " + fields[i].name());
            assertEquals(fields[i].constantValue().getClass(),
                    decoded.fields()[i].constantValue().getClass(),
                    "class for " + fields[i].name());
        }
        assertDeepEquals(original, decoded);
    }

    @Test
    void roundTripsAnnotatedArrayType() {
        Type tree = Type.Annotated.wrap(
                Type.array(Type.parameterized(
                        TypeRef.resolved("java/util/Map"),
                        new Type[]{
                                TypeRef.unresolved("K"),
                                Type.Wildcard.superBound(Type.typeVariable("V"))
                        })),
                new AnnotationRef[]{
                        new AnnotationRef(TypeRef.resolved("org/jspecify/annotations/Nullable"), Map.of())
                });
        ClassFileTypeEntry original = new ClassFileTypeEntry(
                "pkg/Holder.class", "file:///a.jar", "pkg/Holder", 1,
                null,
                new Type[]{tree, Type.Wildcard.unbounded(), Type.Primitive.VOID},
                EmptyArrays.TYPE_PARAM,
                EmptyArrays.FIELD,
                EmptyArrays.METHOD,
                EmptyArrays.STRING,
                EmptyArrays.TYPE_REF,
                EmptyArrays.RECORD_COMPONENT,
                EmptyArrays.ANNOTATION_REF);
        ClassFileTypeEntry decoded = (ClassFileTypeEntry) TypeEntryCodec.decode(TypeEntryCodec.encode(original));
        assertDeepEquals(original, decoded);
        assertNull(decoded.superRef());
    }

    @Test
    void indexStoresBlobsAndDecodesOnRead() {
        Index index = new InMemoryIndex();
        SourceTypeEntry entry = new SourceTypeEntry(
                "com/Foo.java",
                "file:///src/",
                "com/Foo",
                1,
                TypeDeclKind.CLASS,
                TypeRef.resolved("java/lang/Object"),
                EmptyArrays.TYPE,
                EmptyArrays.TYPE_PARAM,
                EmptyArrays.FIELD,
                EmptyArrays.METHOD,
                EmptyArrays.STRING,
                EmptyArrays.TYPE_REF,
                EmptyArrays.RECORD_COMPONENT,
                EmptyArrays.ANNOTATION_REF,
                new SourceResolutionHints("", Map.of(), EmptyArrays.STRING, Set.of()));

        index.add(entry);
        assertEquals(1, index.size());
        assertEquals(1, index.entryCount());
        assertTrue(index.contains("com/Foo"));

        TypeEntry got = ch.castleridge.javals.indexing.IndexTestUtils.get(index, "com/Foo");
        assertInstanceOf(SourceTypeEntry.class, got);
        assertDeepEquals(entry, got);
        assertSame(got, ch.castleridge.javals.indexing.IndexTestUtils.get(index, "com/Foo"));

        Index merged = new InMemoryIndex();
        merged.addAll(index);
        assertDeepEquals(entry, ch.castleridge.javals.indexing.IndexTestUtils.get(merged, "com/Foo"));
        assertEquals(1, merged.searchTypesBySimpleNamePrefix("Fo", 10).size());
        assertEquals(0, merged.searchTypesBySimpleNamePrefix("Bar", 10).size());
    }

    @Test
    void stringTableInternsNullAsZero() {
        assertEquals(0, StringTable.intern(null));
        assertNull(StringTable.get(0));
        int a = StringTable.intern("java/lang/Object");
        int b = StringTable.intern("java/lang/Object");
        assertEquals(a, b);
        assertEquals("java/lang/Object", StringTable.get(a));
    }

    @Test
    void peekIdentityReadsLeadingSourceUriAndResourcePath() {
        ClassFileTypeEntry original = new ClassFileTypeEntry(
                "com/example/Hello.class",
                "file:///lib.jar",
                "com/example/Hello",
                0x0021,
                TypeRef.resolved("java/lang/Object"),
                EmptyArrays.TYPE,
                EmptyArrays.TYPE_PARAM,
                EmptyArrays.FIELD,
                EmptyArrays.METHOD,
                EmptyArrays.STRING,
                EmptyArrays.TYPE_REF,
                EmptyArrays.RECORD_COMPONENT,
                EmptyArrays.ANNOTATION_REF);

        byte[] blob = TypeEntryCodec.encode(original);
        assertEquals("file:///lib.jar", TypeEntryCodec.peekSourceUri(blob));
        assertEquals("com/example/Hello.class", TypeEntryCodec.peekResourcePath(blob));
        String[] identity = TypeEntryCodec.peekIdentity(blob);
        assertEquals("file:///lib.jar", identity[0]);
        assertEquals("com/example/Hello.class", identity[1]);

        TypeEntry decoded = TypeEntryCodec.decode(blob);
        assertInstanceOf(ClassFileTypeEntry.class, decoded);
        assertEquals("com/example/Hello.class", ((ClassFileTypeEntry) decoded).resourcePath());
        assertDeepEquals(original, decoded);
    }

    @Test
    void encodeStoresEffectiveSourceResourcePathForNestedType() {
        SourceTypeEntry original = new SourceTypeEntry(
                "pkg/Outer.java",
                "file:///src/",
                "pkg/Outer$Inner",
                0x0001,
                TypeDeclKind.CLASS,
                TypeRef.resolved("java/lang/Object"),
                EmptyArrays.TYPE,
                EmptyArrays.TYPE_PARAM,
                EmptyArrays.FIELD,
                EmptyArrays.METHOD,
                EmptyArrays.STRING,
                EmptyArrays.TYPE_REF,
                EmptyArrays.RECORD_COMPONENT,
                EmptyArrays.ANNOTATION_REF,
                new SourceResolutionHints("", Map.of(), EmptyArrays.STRING, Set.of()));

        byte[] blob = TypeEntryCodec.encode(original);
        assertEquals("file:///src/", TypeEntryCodec.peekSourceUri(blob));
        assertEquals("pkg/Outer.java", TypeEntryCodec.peekResourcePath(blob));

        TypeEntry decoded = TypeEntryCodec.decode(blob);
        assertInstanceOf(SourceTypeEntry.class, decoded);
        assertEquals("pkg/Outer.java", ((SourceTypeEntry) decoded).resourcePath());
        assertDeepEquals(original, decoded);
    }

    @Test
    void encodeKeepsMismatchedResourcePath() {
        // Secondary top-level type: Helper lives in Foo.java.
        SourceTypeEntry original = new SourceTypeEntry(
                "pkg/Foo.java",
                "file:///src/",
                "pkg/Helper",
                0x0000,
                TypeDeclKind.CLASS,
                TypeRef.resolved("java/lang/Object"),
                EmptyArrays.TYPE,
                EmptyArrays.TYPE_PARAM,
                EmptyArrays.FIELD,
                EmptyArrays.METHOD,
                EmptyArrays.STRING,
                EmptyArrays.TYPE_REF,
                EmptyArrays.RECORD_COMPONENT,
                EmptyArrays.ANNOTATION_REF,
                new SourceResolutionHints("pkg", Map.of(), EmptyArrays.STRING, Set.of("Foo", "Helper")));

        assertEquals("pkg/Foo.java",
                ResourcePaths.forStorage(original.resourcePath(), original.jvmOwnerName(),
                        ResourcePaths.Kind.SOURCE));

        byte[] blob = TypeEntryCodec.encode(original);
        assertEquals("pkg/Foo.java", TypeEntryCodec.peekResourcePath(blob));

        TypeEntry decoded = TypeEntryCodec.decode(blob);
        assertInstanceOf(SourceTypeEntry.class, decoded);
        assertEquals("pkg/Foo.java", ((SourceTypeEntry) decoded).resourcePath());
        assertDeepEquals(original, decoded);
    }

    private static void assertDeepEquals(TypeEntry expected, TypeEntry actual) {
        assertTrue(deepEquals(expected, actual),
                () -> "deep equals failed\nexpected=" + expected + "\nactual=" + actual);
    }

    private static boolean deepEquals(TypeEntry a, TypeEntry b) {
        if (a == b) return true;
        if (a == null || b == null) return false;
        if (a instanceof SourceTypeEntry sa && b instanceof SourceTypeEntry sb) {
            return Objects.equals(sa.resourcePath(), sb.resourcePath())
                    && Objects.equals(sa.sourceUri(), sb.sourceUri())
                    && Objects.equals(sa.jvmOwnerName(), sb.jvmOwnerName())
                    && sa.modifiers() == sb.modifiers()
                    && sa.declKind() == sb.declKind()
                    && deepEquals(sa.superRef(), sb.superRef())
                    && deepEquals(sa.interfaceRefs(), sb.interfaceRefs())
                    && deepEquals(sa.typeParams(), sb.typeParams())
                    && deepEquals(sa.fields(), sb.fields())
                    && deepEquals(sa.methods(), sb.methods())
                    && Arrays.equals(sa.innerTypeJvmNames(), sb.innerTypeJvmNames())
                    && deepEquals(sa.permittedSubclasses(), sb.permittedSubclasses())
                    && deepEquals(sa.recordComponents(), sb.recordComponents())
                    && deepEquals(sa.annotations(), sb.annotations())
                    && deepEquals(sa.hints(), sb.hints());
        }
        if (a instanceof ClassFileTypeEntry ca && b instanceof ClassFileTypeEntry cb) {
            return Objects.equals(ca.resourcePath(), cb.resourcePath())
                    && Objects.equals(ca.sourceUri(), cb.sourceUri())
                    && Objects.equals(ca.jvmOwnerName(), cb.jvmOwnerName())
                    && ca.modifiers() == cb.modifiers()
                    && deepEquals(ca.superRef(), cb.superRef())
                    && deepEquals(ca.interfaceRefs(), cb.interfaceRefs())
                    && deepEquals(ca.typeParams(), cb.typeParams())
                    && deepEquals(ca.fields(), cb.fields())
                    && deepEquals(ca.methods(), cb.methods())
                    && Arrays.equals(ca.innerTypeJvmNames(), cb.innerTypeJvmNames())
                    && deepEquals(ca.permittedSubclasses(), cb.permittedSubclasses())
                    && deepEquals(ca.recordComponents(), cb.recordComponents())
                    && deepEquals(ca.annotations(), cb.annotations());
        }
        return false;
    }

    private static boolean deepEquals(SourceResolutionHints a, SourceResolutionHints b) {
        if (a == b) return true;
        if (a == null || b == null) return false;
        return Objects.equals(a.sourcePackage(), b.sourcePackage())
                && Objects.equals(a.singleTypeImports(), b.singleTypeImports())
                && Arrays.equals(a.onDemandImports(), b.onDemandImports())
                && Objects.equals(a.siblingSimpleNames(), b.siblingSimpleNames());
    }

    private static boolean deepEquals(TypeParamRef[] a, TypeParamRef[] b) {
        if (a == b) return true;
        if (a == null || b == null || a.length != b.length) return false;
        for (int i = 0; i < a.length; i++) {
            if (!deepEquals(a[i], b[i])) return false;
        }
        return true;
    }

    private static boolean deepEquals(TypeParamRef a, TypeParamRef b) {
        if (a == b) return true;
        if (a == null || b == null) return false;
        return Objects.equals(a.name(), b.name()) && deepEquals(a.bounds(), b.bounds());
    }

    private static boolean deepEquals(FieldEntry[] a, FieldEntry[] b) {
        if (a == b) return true;
        if (a == null || b == null || a.length != b.length) return false;
        for (int i = 0; i < a.length; i++) {
            if (!deepEquals(a[i], b[i])) return false;
        }
        return true;
    }

    private static boolean deepEquals(FieldEntry a, FieldEntry b) {
        if (a == b) return true;
        if (a == null || b == null) return false;
        return a.modifiers() == b.modifiers()
                && Objects.equals(a.name(), b.name())
                && deepEquals(a.type(), b.type())
                && Objects.equals(a.constantValue(), b.constantValue())
                && deepEquals(a.annotations(), b.annotations());
    }

    private static boolean deepEquals(MethodEntry[] a, MethodEntry[] b) {
        if (a == b) return true;
        if (a == null || b == null || a.length != b.length) return false;
        for (int i = 0; i < a.length; i++) {
            if (!deepEquals(a[i], b[i])) return false;
        }
        return true;
    }

    private static boolean deepEquals(MethodEntry a, MethodEntry b) {
        if (a == b) return true;
        if (a == null || b == null) return false;
        return a.modifiers() == b.modifiers()
                && Objects.equals(a.name(), b.name())
                && deepEquals(a.returnType(), b.returnType())
                && deepEquals(a.parameters(), b.parameters())
                && deepEquals(a.throwsTypes(), b.throwsTypes())
                && deepEquals(a.typeParams(), b.typeParams())
                && a.varargs() == b.varargs()
                && a.hasBody() == b.hasBody()
                && deepEquals(a.annotationDefault(), b.annotationDefault())
                && deepEquals(a.annotations(), b.annotations());
    }

    private static boolean deepEquals(ParameterEntry[] a, ParameterEntry[] b) {
        if (a == b) return true;
        if (a == null || b == null || a.length != b.length) return false;
        for (int i = 0; i < a.length; i++) {
            if (!deepEquals(a[i], b[i])) return false;
        }
        return true;
    }

    private static boolean deepEquals(ParameterEntry a, ParameterEntry b) {
        if (a == b) return true;
        if (a == null || b == null) return false;
        return Objects.equals(a.name(), b.name())
                && a.modifiers() == b.modifiers()
                && deepEquals(a.type(), b.type())
                && deepEquals(a.annotations(), b.annotations());
    }

    private static boolean deepEquals(RecordComponentEntry[] a, RecordComponentEntry[] b) {
        if (a == b) return true;
        if (a == null || b == null || a.length != b.length) return false;
        for (int i = 0; i < a.length; i++) {
            if (!deepEquals(a[i], b[i])) return false;
        }
        return true;
    }

    private static boolean deepEquals(RecordComponentEntry a, RecordComponentEntry b) {
        if (a == b) return true;
        if (a == null || b == null) return false;
        return Objects.equals(a.name(), b.name())
                && deepEquals(a.type(), b.type())
                && deepEquals(a.annotations(), b.annotations());
    }

    private static boolean deepEquals(AnnotationRef[] a, AnnotationRef[] b) {
        if (a == b) return true;
        if (a == null || b == null || a.length != b.length) return false;
        for (int i = 0; i < a.length; i++) {
            if (!deepEquals(a[i], b[i])) return false;
        }
        return true;
    }

    private static boolean deepEquals(AnnotationRef a, AnnotationRef b) {
        if (a == b) return true;
        if (a == null || b == null) return false;
        if (!deepEquals(a.annotationType(), b.annotationType())) return false;
        if (a.values().size() != b.values().size()) return false;
        for (Map.Entry<String, AnnotationValue> e : a.values().entrySet()) {
            if (!deepEquals(e.getValue(), b.values().get(e.getKey()))) return false;
        }
        return true;
    }

    private static boolean deepEquals(AnnotationValue a, AnnotationValue b) {
        if (a == b) return true;
        if (a == null || b == null) return false;
        if (a instanceof AnnotationValue.Primitive pa && b instanceof AnnotationValue.Primitive pb) {
            return Objects.equals(pa.boxed(), pb.boxed());
        }
        if (a instanceof AnnotationValue.Str sa && b instanceof AnnotationValue.Str sb) {
            return Objects.equals(sa.value(), sb.value());
        }
        if (a instanceof AnnotationValue.ClassRef ca && b instanceof AnnotationValue.ClassRef cb) {
            return deepEquals(ca.type(), cb.type());
        }
        if (a instanceof AnnotationValue.EnumConst ea && b instanceof AnnotationValue.EnumConst eb) {
            return deepEquals(ea.enumType(), eb.enumType())
                    && Objects.equals(ea.constant(), eb.constant());
        }
        if (a instanceof AnnotationValue.Arr aa && b instanceof AnnotationValue.Arr ab) {
            if (aa.elements().length != ab.elements().length) return false;
            for (int i = 0; i < aa.elements().length; i++) {
                if (!deepEquals(aa.elements()[i], ab.elements()[i])) return false;
            }
            return true;
        }
        if (a instanceof AnnotationValue.Nested na && b instanceof AnnotationValue.Nested nb) {
            return deepEquals(na.annotation(), nb.annotation());
        }
        if (a instanceof AnnotationValue.Unsupported ua && b instanceof AnnotationValue.Unsupported ub) {
            return Objects.equals(ua.reason(), ub.reason());
        }
        return false;
    }

    private static boolean deepEquals(TypeRef[] a, TypeRef[] b) {
        if (a == b) return true;
        if (a == null || b == null || a.length != b.length) return false;
        for (int i = 0; i < a.length; i++) {
            if (!deepEquals(a[i], b[i])) return false;
        }
        return true;
    }

    private static boolean deepEquals(Type[] a, Type[] b) {
        if (a == b) return true;
        if (a == null || b == null || a.length != b.length) return false;
        for (int i = 0; i < a.length; i++) {
            if (!deepEquals(a[i], b[i])) return false;
        }
        return true;
    }

    private static boolean deepEquals(Type a, Type b) {
        if (a == b) return true;
        if (a == null || b == null) return false;
        if (a instanceof Type.Annotated aa && b instanceof Type.Annotated ab) {
            return deepEquals(aa.inner(), ab.inner()) && deepEquals(aa.annotations(), ab.annotations());
        }
        if (a instanceof Type.Primitive || b instanceof Type.Primitive) {
            return a == b;
        }
        if (a instanceof Type.Array aa && b instanceof Type.Array ab) {
            return deepEquals(aa.element(), ab.element());
        }
        if (a instanceof Type.TypeVariable aa && b instanceof Type.TypeVariable ab) {
            return Objects.equals(aa.name(), ab.name());
        }
        if (a instanceof Type.Wildcard aa && b instanceof Type.Wildcard ab) {
            return aa.kind() == ab.kind() && deepEquals(aa.bound(), ab.bound());
        }
        if (a instanceof Type.Parameterized aa && b instanceof Type.Parameterized ab) {
            return deepEquals(aa.raw(), ab.raw()) && deepEquals(aa.typeArgs(), ab.typeArgs());
        }
        if (a instanceof TypeRef.Resolved aa && b instanceof TypeRef.Resolved ab) {
            return Objects.equals(aa.jvmBinaryName(), ab.jvmBinaryName());
        }
        if (a instanceof TypeRef.Unresolved aa && b instanceof TypeRef.Unresolved ab) {
            return Objects.equals(aa.simpleName(), ab.simpleName());
        }
        return false;
    }
}
