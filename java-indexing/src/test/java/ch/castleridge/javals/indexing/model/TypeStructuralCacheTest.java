package ch.castleridge.javals.indexing.model;

import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;

import org.junit.jupiter.api.Test;

class TypeStructuralCacheTest {

    @Test
    void arraySharesAnnotationFreeShapes() {
        Type string = TypeRef.resolved("java/lang/String");
        assertSame(Type.array(string), Type.array(string));
        assertSame(Type.array(Type.Primitive.INT), Type.array(Type.Primitive.INT));
        Type stringArray = Type.array(string);
        assertSame(Type.array(stringArray), Type.array(Type.array(string)));
    }

    @Test
    void arrayDoesNotCacheAnnotatedElements() {
        Type string = TypeRef.resolved("java/lang/String");
        AnnotationRef ann = new AnnotationRef(TypeRef.resolved("java/lang/Deprecated"), java.util.Map.of());
        Type annotated = Type.Annotated.wrap(string, new AnnotationRef[]{ann});
        assertNotSame(Type.array(annotated), Type.array(annotated));
    }

    @Test
    void parameterizedSharesAnnotationFreeShapes() {
        TypeRef list = TypeRef.resolved("java/util/List");
        Type string = TypeRef.resolved("java/lang/String");
        Type[] args = {string};
        assertSame(Type.parameterized(list, args), Type.parameterized(list, new Type[]{string}));
    }

    @Test
    void parameterizedDoesNotCacheAnnotatedArgs() {
        TypeRef list = TypeRef.resolved("java/util/List");
        Type string = TypeRef.resolved("java/lang/String");
        AnnotationRef ann = new AnnotationRef(TypeRef.resolved("java/lang/Deprecated"), java.util.Map.of());
        Type annotated = Type.Annotated.wrap(string, new AnnotationRef[]{ann});
        assertNotSame(
                Type.parameterized(list, new Type[]{annotated}),
                Type.parameterized(list, new Type[]{annotated}));
    }

    @Test
    void wildcardFactoriesShare() {
        assertSame(Type.Wildcard.unbounded(), Type.Wildcard.unbounded());
        Type object = TypeRef.resolved("java/lang/Object");
        assertSame(Type.Wildcard.extendsBound(object), Type.Wildcard.extendsBound(object));
        assertSame(Type.Wildcard.superBound(object), Type.Wildcard.superBound(object));
    }

    @Test
    void descriptorsAndFactoriesShareArrayShapes() {
        Type fromDescriptor = Descriptors.parseField("[Ljava/lang/String;");
        Type fromFactory = Type.array(TypeRef.resolved("java/lang/String"));
        assertSame(fromFactory, fromDescriptor);
    }
}
