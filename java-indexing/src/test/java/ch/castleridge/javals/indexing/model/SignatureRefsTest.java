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
