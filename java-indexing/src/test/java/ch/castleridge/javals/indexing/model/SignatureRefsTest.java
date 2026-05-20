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
        TypeRef.Parameterized param = assertInstanceOf(TypeRef.Parameterized.class, refs.paramTypes().get(0));
        TypeRef.Wildcard wildcard = assertInstanceOf(TypeRef.Wildcard.class, param.typeArgs().get(0));
        assertEquals(TypeRef.Wildcard.BoundKind.SUPER, wildcard.kind());
        assertInstanceOf(TypeRef.TypeVariable.class, wildcard.bound());
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
        TypeRef.Parameterized completionStage =
                assertInstanceOf(TypeRef.Parameterized.class, refs.interfaces().get(1));
        TypeRef.Resolved raw = assertInstanceOf(TypeRef.Resolved.class, completionStage.raw());
        assertEquals("java/util/concurrent/CompletionStage", raw.jvmBinaryName());
        TypeRef.TypeVariable typeArg = assertInstanceOf(TypeRef.TypeVariable.class, completionStage.typeArgs().get(0));
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
        TypeRef.Parameterized cs = assertInstanceOf(TypeRef.Parameterized.class, cf.interfaceRefs().get(0));
        assertEquals("java/util/concurrent/CompletionStage",
                assertInstanceOf(TypeRef.Resolved.class, cs.raw()).jvmBinaryName());
        assertEquals("T", assertInstanceOf(TypeRef.TypeVariable.class, cs.typeArgs().get(0)).name());
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
        TypeRef.Parameterized param = assertInstanceOf(TypeRef.Parameterized.class, expecting.paramTypes().get(0));
        TypeRef.Wildcard wildcard = assertInstanceOf(TypeRef.Wildcard.class, param.typeArgs().get(0));
        assertEquals(TypeRef.Wildcard.BoundKind.SUPER, wildcard.kind());
    }
}
