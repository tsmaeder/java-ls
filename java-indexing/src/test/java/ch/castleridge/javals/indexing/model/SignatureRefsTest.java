package ch.castleridge.javals.indexing.model;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import ch.castleridge.javals.indexing.bytecode.ClassFileIndexer;
import ch.castleridge.javals.indexing.index.Index;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class SignatureRefsTest {

    @Test
    void parseMethodPreservesSuperWildcard() {
        SignatureRefs.MethodRefs refs = SignatureRefs.parseMethod(
                "(Lpkg/Expectation<-TT;>;)Lpkg/Future<TT;>;");
        assertNotNull(refs);
        assertEquals(1, refs.paramTypes().size());
        Type.Parameterized param = assertInstanceOf(Type.Parameterized.class, refs.paramTypes().get(0));
        Type.Wildcard wildcard = assertInstanceOf(Type.Wildcard.class, param.typeArgs().get(0));
        assertEquals(Type.Wildcard.BoundKind.SUPER, wildcard.kind());
        assertInstanceOf(Type.TypeVariable.class, wildcard.bound());
    }

    @Test
    void parseClassPreservesParameterizedInterfaces() {
        SignatureRefs.ClassRefs refs = SignatureRefs.parseClass(
                "<U:Ljava/lang/Object;>Ljava/lang/Object;"
                        + "Ljava/util/concurrent/Future<TU;>;"
                        + "Ljava/util/concurrent/CompletionStage<TU;>;");
        assertNotNull(refs);
        assertInstanceOf(TypeRef.Resolved.class, refs.superClass());
        assertEquals(2, refs.interfaces().size());
        Type.Parameterized completionStage =
                assertInstanceOf(Type.Parameterized.class, refs.interfaces().get(1));
        TypeRef.Resolved raw = assertInstanceOf(TypeRef.Resolved.class, completionStage.raw());
        assertEquals("java/util/concurrent/CompletionStage", raw.jvmBinaryName());
        Type.TypeVariable typeArg = assertInstanceOf(Type.TypeVariable.class, completionStage.typeArgs().get(0));
        assertEquals("U", typeArg.name());
    }

    @Test
    void classFileIndexerUsesClassSignatureForInterfaces() {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC,
                "pkg/Cf", "<T:Ljava/lang/Object;>Ljava/lang/Object;Ljava/util/concurrent/CompletionStage<TT;>;",
                "java/lang/Object", new String[] {"java/util/concurrent/CompletionStage"});
        cw.visitEnd();

        Index index = new Index();
        ClassFileIndexer.index(
                java.net.URI.create("index:///pkg/Cf.class"),
                java.net.URI.create("index:///cp/"),
                cw.toByteArray(),
                index);

        TypeEntry cf = index.get("pkg/Cf");
        assertNotNull(cf);
        assertEquals(1, cf.interfaceRefs().size());
        Type.Parameterized cs = assertInstanceOf(Type.Parameterized.class, cf.interfaceRefs().get(0));
        assertEquals("java/util/concurrent/CompletionStage",
                assertInstanceOf(TypeRef.Resolved.class, cs.raw()).jvmBinaryName());
        assertEquals("T", assertInstanceOf(Type.TypeVariable.class, cs.typeArgs().get(0)).name());
    }

    @Test
    void parseFormalTypeParametersCapturesBounds() {
        var params = SignatureRefs.parseFormalTypeParameters(
                "<T:Ljava/lang/Comparable<TT;>;:Ljava/io/Serializable;U:Ljava/lang/Object;>Ljava/lang/Object;");
        assertEquals(2, params.size());

        TypeParamRef t = params.get(0);
        assertEquals("T", t.name());
        assertEquals(2, t.bounds().size());
        Type.Parameterized comparable = assertInstanceOf(Type.Parameterized.class, t.bounds().get(0));
        assertEquals("java/lang/Comparable",
                assertInstanceOf(TypeRef.Resolved.class, comparable.raw()).jvmBinaryName());
        Type.TypeVariable comparableArg =
                assertInstanceOf(Type.TypeVariable.class, comparable.typeArgs().get(0));
        assertEquals("T", comparableArg.name());
        assertEquals("java/io/Serializable",
                assertInstanceOf(TypeRef.Resolved.class, t.bounds().get(1)).jvmBinaryName());

        TypeParamRef u = params.get(1);
        assertEquals("U", u.name());
        // Object-only bound is normalised by TypeParamRef back to the
        // canonical singleton list - nothing fancy required from callers.
        assertEquals(1, u.bounds().size());
        assertEquals("java/lang/Object",
                assertInstanceOf(TypeRef.Resolved.class, u.bounds().get(0)).jvmBinaryName());
    }

    @Test
    void parseMethodCapturesMethodTypeParameterBounds() {
        SignatureRefs.MethodRefs refs = SignatureRefs.parseMethod(
                "<E:Ljava/lang/Throwable;>(Ljava/lang/Throwable;)TE;");
        assertNotNull(refs);
        assertEquals(1, refs.typeParams().size());
        TypeParamRef e = refs.typeParams().get(0);
        assertEquals("E", e.name());
        assertEquals(1, e.bounds().size());
        assertEquals("java/lang/Throwable",
                assertInstanceOf(TypeRef.Resolved.class, e.bounds().get(0)).jvmBinaryName());
    }

    @Test
    void classFileIndexerUsesMethodSignatureAttribute() {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_INTERFACE,
                "pkg/Future", "<T:Ljava/lang/Object;>Ljava/lang/Object;",
                "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(
                Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT,
                "expecting",
                "(Lpkg/Expectation;)Lpkg/Future;",
                "(Lpkg/Expectation<-TT;>;)Lpkg/Future<TT;>;",
                null);
        mv.visitEnd();
        cw.visitEnd();

        Index index = new Index();
        ClassFileIndexer.index(
                java.net.URI.create("index:///pkg/Future.class"),
                java.net.URI.create("index:///cp/"),
                cw.toByteArray(),
                index);

        MethodEntry expecting = index.get("pkg/Future").methods().stream()
                .filter(m -> m.name().equals("expecting"))
                .findFirst()
                .orElseThrow();
        Type.Parameterized param = assertInstanceOf(Type.Parameterized.class, expecting.paramTypes().get(0));
        Type.Wildcard wildcard = assertInstanceOf(Type.Wildcard.class, param.typeArgs().get(0));
        assertEquals(Type.Wildcard.BoundKind.SUPER, wildcard.kind());
    }
}
