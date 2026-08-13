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
package ch.castleridge.javals.analysis.ecj;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.Map;
import java.util.Set;

import org.eclipse.jdt.internal.compiler.classfmt.ClassFileConstants;
import org.eclipse.jdt.internal.compiler.env.IBinaryField;
import org.eclipse.jdt.internal.compiler.env.IBinaryMethod;
import org.eclipse.jdt.internal.compiler.env.IBinaryNestedType;
import org.eclipse.jdt.internal.compiler.env.IBinaryType;
import org.eclipse.jdt.internal.compiler.env.IRecordComponent;
import org.eclipse.jdt.internal.compiler.lookup.ExtraCompilerModifiers;
import org.junit.jupiter.api.Test;

import ch.castleridge.javals.classpath.ClasspathOrder;
import ch.castleridge.javals.classpath.UriClasspathEntry;
import ch.castleridge.javals.indexing.index.InMemoryIndex;
import ch.castleridge.javals.indexing.model.EmptyArrays;
import ch.castleridge.javals.indexing.model.FieldEntry;
import ch.castleridge.javals.indexing.model.MethodEntry;
import ch.castleridge.javals.indexing.model.RecordComponentEntry;
import ch.castleridge.javals.indexing.model.SourceResolutionHints;
import ch.castleridge.javals.indexing.model.SourceTypeEntry;
import ch.castleridge.javals.indexing.model.Type;
import ch.castleridge.javals.indexing.model.TypeDeclKind;
import ch.castleridge.javals.indexing.model.TypeEntry;
import ch.castleridge.javals.indexing.model.TypeParamRef;
import ch.castleridge.javals.indexing.model.TypeRef;

class IndexBinaryTypeTest {
    private static final String SOURCE = "index:///src/";

    @Test
    void encodesGenericClassSignatureAndUnresolvedImports() {
        InMemoryIndex index = new InMemoryIndex();
        index.add(sourceClass("demo/Box", TypeDeclKind.CLASS));
        index.add(sourceClass("demo/Item", TypeDeclKind.CLASS));
        index.add(new SourceTypeEntry(
                "demo/Holder.java",
                SOURCE,
                "demo/Holder",
                ClassFileConstants.AccPublic,
                TypeDeclKind.CLASS,
                TypeRef.resolved("java/lang/Object"),
                EmptyArrays.TYPE,
                new TypeParamRef[] { TypeParamRef.of("T") },
                new FieldEntry[] {
                        new FieldEntry(ClassFileConstants.AccPrivate, "item",
                                TypeRef.unresolved("Item"), EmptyArrays.ANNOTATION_REF)
                },
                new MethodEntry[] {
                        MethodEntry.ofTypes(
                                ClassFileConstants.AccPublic,
                                "get",
                                Type.typeVariable("T"),
                                EmptyArrays.TYPE,
                                EmptyArrays.TYPE,
                                EmptyArrays.TYPE_PARAM,
                                false,
                                true,
                                null,
                                EmptyArrays.ANNOTATION_REF)
                },
                EmptyArrays.STRING,
                EmptyArrays.TYPE_REF,
                EmptyArrays.RECORD_COMPONENT,
                EmptyArrays.ANNOTATION_REF,
                new SourceResolutionHints("demo", Map.of(), EmptyArrays.STRING, Set.of("Item", "Holder", "Box"))));

        IBinaryType binary = IndexBinaryType.of(
                index.getAll("demo/Holder").get(0), index, classpath());

        assertEquals("demo/Holder", new String(binary.getName()));
        assertEquals("Holder", new String(binary.getSourceName()));
        assertNotNull(binary.getGenericSignature());
        assertTrue(new String(binary.getGenericSignature()).startsWith("<T:Ljava/lang/Object;>"));
        assertTrue(binary.isBinaryType());

        IBinaryField field = binary.getFields()[0];
        assertEquals("item", new String(field.getName()));
        assertEquals("Ldemo/Item;", new String(field.getTypeName()));

        IBinaryMethod get = findMethod(binary, "get");
        assertEquals("()Ljava/lang/Object;", new String(get.getMethodDescriptor()));
        assertEquals("()TT;", new String(get.getGenericSignature()));
        assertNotNull(findMethod(binary, "<init>"), "default constructor synthesized");
    }

    @Test
    void synthesizesEnumHelpersAndNestedMemberMetadata() {
        InMemoryIndex index = new InMemoryIndex();
        index.add(new SourceTypeEntry(
                "demo/Color.java",
                SOURCE,
                "demo/Color",
                ClassFileConstants.AccPublic,
                TypeDeclKind.ENUM,
                TypeRef.resolved("java/lang/Enum"),
                EmptyArrays.TYPE,
                EmptyArrays.TYPE_PARAM,
                EmptyArrays.FIELD,
                EmptyArrays.METHOD,
                new String[] { "demo/Color$Shade" },
                EmptyArrays.TYPE_REF,
                EmptyArrays.RECORD_COMPONENT,
                EmptyArrays.ANNOTATION_REF,
                new SourceResolutionHints("demo", Map.of(), EmptyArrays.STRING, Set.of("Color"))));
        index.add(new SourceTypeEntry(
                "demo/Color.java",
                SOURCE,
                "demo/Color$Shade",
                ClassFileConstants.AccPublic | ClassFileConstants.AccStatic,
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
                new SourceResolutionHints("demo", Map.of(), EmptyArrays.STRING, Set.of("Color", "Shade"))));

        IBinaryType binary = IndexBinaryType.of(
                index.getAll("demo/Color").get(0), index, classpath());

        assertEquals("java/lang/Enum", new String(binary.getSuperclassName()));
        assertNotNull(findMethod(binary, "values"));
        assertEquals("()[Ldemo/Color;", new String(findMethod(binary, "values").getMethodDescriptor()));
        assertNotNull(findMethod(binary, "valueOf"));
        assertFalse(Arrays.stream(binary.getMethods()).anyMatch(IBinaryMethod::isConstructor));

        IBinaryNestedType[] members = binary.getMemberTypes();
        assertNotNull(members);
        assertEquals(1, members.length);
        assertEquals("demo/Color$Shade", new String(members[0].getName()));
        assertEquals("demo/Color", new String(members[0].getEnclosingTypeName()));
    }

    @Test
    void exposesRecordComponentsAndSealedPermits() {
        InMemoryIndex index = new InMemoryIndex();
        index.add(new SourceTypeEntry(
                "demo/Point.java",
                SOURCE,
                "demo/Point",
                ClassFileConstants.AccPublic,
                TypeDeclKind.RECORD,
                TypeRef.resolved("java/lang/Record"),
                EmptyArrays.TYPE,
                EmptyArrays.TYPE_PARAM,
                EmptyArrays.FIELD,
                EmptyArrays.METHOD,
                EmptyArrays.STRING,
                new TypeRef[] { TypeRef.resolved("demo/Origin") },
                new RecordComponentEntry[] {
                        new RecordComponentEntry("x", Type.Primitive.INT, EmptyArrays.ANNOTATION_REF),
                        new RecordComponentEntry("y", Type.Primitive.INT, EmptyArrays.ANNOTATION_REF)
                },
                EmptyArrays.ANNOTATION_REF,
                new SourceResolutionHints("demo", Map.of(), EmptyArrays.STRING, Set.of("Point", "Origin"))));

        IBinaryType binary = IndexBinaryType.of(
                index.getAll("demo/Point").get(0), index, classpath());

        assertTrue(binary.isRecord());
        assertTrue((binary.getModifiers() & ExtraCompilerModifiers.AccRecord) != 0);
        assertTrue((binary.getModifiers() & ExtraCompilerModifiers.AccSealed) != 0);
        assertEquals("java/lang/Record", new String(binary.getSuperclassName()));

        IRecordComponent[] components = binary.getRecordComponents();
        assertNotNull(components);
        assertEquals(2, components.length);
        assertEquals("x", new String(components[0].getName()));
        assertEquals("I", new String(components[0].getTypeName()));

        char[][] permits = binary.getPermittedSubtypesNames();
        assertNotNull(permits);
        assertEquals("demo/Origin", new String(permits[0]));
    }

    @Test
    void annotationTypeExtendsAnnotationAndDefaultConstructorSkipped() {
        InMemoryIndex index = new InMemoryIndex();
        index.add(new SourceTypeEntry(
                "demo/Marker.java",
                SOURCE,
                "demo/Marker",
                ClassFileConstants.AccPublic,
                TypeDeclKind.ANNOTATION,
                null,
                EmptyArrays.TYPE,
                EmptyArrays.TYPE_PARAM,
                EmptyArrays.FIELD,
                new MethodEntry[] {
                        MethodEntry.ofTypes(
                                0,
                                "value",
                                TypeRef.resolved("java/lang/String"),
                                EmptyArrays.TYPE,
                                EmptyArrays.TYPE,
                                EmptyArrays.TYPE_PARAM,
                                false,
                                false,
                                null,
                                EmptyArrays.ANNOTATION_REF)
                },
                EmptyArrays.STRING,
                EmptyArrays.TYPE_REF,
                EmptyArrays.RECORD_COMPONENT,
                EmptyArrays.ANNOTATION_REF,
                new SourceResolutionHints("demo", Map.of(), EmptyArrays.STRING, Set.of("Marker"))));

        IBinaryType binary = IndexBinaryType.of(
                index.getAll("demo/Marker").get(0), index, classpath());

        assertTrue((binary.getModifiers() & ClassFileConstants.AccAnnotation) != 0);
        assertTrue((binary.getModifiers() & ClassFileConstants.AccInterface) != 0);
        char[][] ifaces = binary.getInterfaceNames();
        assertNotNull(ifaces);
        assertTrue(Arrays.stream(ifaces)
                .anyMatch(name -> "java/lang/annotation/Annotation".equals(new String(name))));
        assertFalse(Arrays.stream(binary.getMethods()).anyMatch(IBinaryMethod::isConstructor));
        IBinaryMethod value = findMethod(binary, "value");
        assertTrue((value.getModifiers() & ClassFileConstants.AccAbstract) != 0);
        assertTrue((value.getModifiers() & ClassFileConstants.AccPublic) != 0);
    }

    private static TypeEntry sourceClass(String jvm, TypeDeclKind kind) {
        return new SourceTypeEntry(
                jvm + ".java",
                SOURCE,
                jvm,
                ClassFileConstants.AccPublic,
                kind,
                TypeRef.resolved("java/lang/Object"),
                EmptyArrays.TYPE,
                EmptyArrays.TYPE_PARAM,
                EmptyArrays.FIELD,
                EmptyArrays.METHOD,
                EmptyArrays.STRING,
                EmptyArrays.TYPE_REF,
                EmptyArrays.RECORD_COMPONENT,
                EmptyArrays.ANNOTATION_REF,
                new SourceResolutionHints(
                        jvm.contains("/") ? jvm.substring(0, jvm.lastIndexOf('/')) : "",
                        Map.of(),
                        EmptyArrays.STRING,
                        Set.of(jvm.substring(jvm.lastIndexOf('/') + 1))));
    }

    private static ClasspathOrder classpath() {
        return new ClasspathOrder(java.util.List.of(UriClasspathEntry.of(SOURCE)), false);
    }

    private static IBinaryMethod findMethod(IBinaryType binary, String name) {
        IBinaryMethod[] methods = binary.getMethods();
        if (methods == null) return null;
        for (IBinaryMethod method : methods) {
            if (name.equals(new String(method.getSelector()))) return method;
        }
        return null;
    }
}
