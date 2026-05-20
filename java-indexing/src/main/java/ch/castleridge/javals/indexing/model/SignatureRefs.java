package ch.castleridge.javals.indexing.model;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.signature.SignatureReader;
import org.objectweb.asm.signature.SignatureVisitor;

import ch.castleridge.javals.indexing.intern.Interner;

/**
 * Parses JVM {@code Signature} attribute strings into {@link TypeRef} trees.
 */
public final class SignatureRefs {

    private static final int ASM_API = Opcodes.ASM9;

    private SignatureRefs() {}

    public record MethodRefs(
            List<TypeParamRef> typeParams,
            List<TypeRef> paramTypes,
            TypeRef returnType,
            List<TypeRef> throwsTypes) {
        public MethodRefs {
            typeParams = typeParams == null ? List.of() : List.copyOf(typeParams);
            paramTypes = paramTypes == null ? List.of() : List.copyOf(paramTypes);
            throwsTypes = throwsTypes == null ? List.of() : List.copyOf(throwsTypes);
        }
    }

    public record ClassRefs(TypeRef superClass, List<TypeRef> interfaces) {
        public ClassRefs {
            interfaces = interfaces == null ? List.of() : List.copyOf(interfaces);
        }
    }

    /**
     * Pull formal type parameter names from a signature prefix.
     */
    public static List<TypeParamRef> parseFormalTypeParameters(String signature) {
        if (signature == null || signature.isEmpty() || signature.charAt(0) != '<') {
            return List.of();
        }
        List<TypeParamRef> collected = new ArrayList<>();
        SignatureVisitor sv = new SignatureVisitor(ASM_API) {
            @Override
            public void visitFormalTypeParameter(String name) {
                if (name != null && !name.isEmpty()) {
                    collected.add(TypeParamRef.of(Interner.intern(name)));
                }
            }
        };
        new SignatureReader(signature).accept(sv);
        return collected.isEmpty() ? List.of() : List.copyOf(collected);
    }

    public static TypeRef parseType(String signature) {
        if (signature == null || signature.isEmpty()) return null;
        TypeCollector collector = new TypeCollector();
        new SignatureReader(signature).acceptType(collector.visitor());
        return collector.result();
    }

    public static MethodRefs parseMethod(String signature) {
        if (signature == null || signature.isEmpty()) return null;
        MethodCollector collector = new MethodCollector();
        new SignatureReader(signature).accept(collector.visitor());
        return collector.result();
    }

    /**
     * Parse a class {@code Signature} attribute: superclass plus implemented
     * interfaces (with type arguments). Formal type parameters are ignored
     * here; use {@link #parseFormalTypeParameters} for those.
     */
    public static ClassRefs parseClass(String signature) {
        if (signature == null || signature.isEmpty()) return null;
        ClassCollector collector = new ClassCollector();
        new SignatureReader(signature).accept(collector.visitor());
        return collector.result();
    }

    private static TypeRef primitiveFromDescriptor(char descriptor) {
        return switch (descriptor) {
            case 'V' -> TypeRef.Primitive.VOID;
            case 'Z' -> TypeRef.Primitive.BOOLEAN;
            case 'B' -> TypeRef.Primitive.BYTE;
            case 'C' -> TypeRef.Primitive.CHAR;
            case 'S' -> TypeRef.Primitive.SHORT;
            case 'I' -> TypeRef.Primitive.INT;
            case 'J' -> TypeRef.Primitive.LONG;
            case 'F' -> TypeRef.Primitive.FLOAT;
            case 'D' -> TypeRef.Primitive.DOUBLE;
            default -> TypeRef.resolved("java/lang/Object");
        };
    }

    private static TypeRef toWildcardArg(char wildcard, TypeRef bound) {
        return switch (wildcard) {
            case SignatureVisitor.EXTENDS -> bound == null
                    ? TypeRef.Wildcard.unbounded()
                    : TypeRef.Wildcard.extendsBound(bound);
            case SignatureVisitor.SUPER -> TypeRef.Wildcard.superBound(bound);
            case SignatureVisitor.INSTANCEOF -> bound == null
                    ? TypeRef.Wildcard.unbounded()
                    : bound;
            default -> bound == null
                    ? TypeRef.Wildcard.unbounded()
                    : bound;
        };
    }

    private static final class TypeCollector {
        private TypeRef result;

        SignatureVisitor visitor() {
            return new ClassTypeFrame(ref -> result = ref);
        }

        TypeRef result() {
            return result;
        }
    }

    private static final class MethodCollector {
        private final List<TypeParamRef> typeParams = new ArrayList<>();
        private final List<TypeRef> paramTypes = new ArrayList<>();
        private final List<TypeRef> throwsTypes = new ArrayList<>();
        private TypeRef returnType;

        SignatureVisitor visitor() {
            return new SignatureVisitor(ASM_API) {
                @Override
                public void visitFormalTypeParameter(String name) {
                    if (name != null && !name.isEmpty()) {
                        typeParams.add(TypeParamRef.of(Interner.intern(name)));
                    }
                }

                @Override
                public SignatureVisitor visitParameterType() {
                    return new ClassTypeFrame(paramTypes::add);
                }

                @Override
                public SignatureVisitor visitReturnType() {
                    return new ClassTypeFrame(ref -> returnType = ref);
                }

                @Override
                public SignatureVisitor visitExceptionType() {
                    return new ClassTypeFrame(throwsTypes::add);
                }
            };
        }

        MethodRefs result() {
            return new MethodRefs(typeParams, paramTypes, returnType, throwsTypes);
        }
    }

    private static final class ClassCollector {
        private TypeRef superClass;
        private final List<TypeRef> interfaces = new ArrayList<>();

        SignatureVisitor visitor() {
            return new SignatureVisitor(ASM_API) {
                @Override
                public SignatureVisitor visitSuperclass() {
                    return new ClassTypeFrame(ref -> superClass = ref);
                }

                @Override
                public SignatureVisitor visitInterface() {
                    return new ClassTypeFrame(interfaces::add);
                }
            };
        }

        ClassRefs result() {
            return new ClassRefs(superClass, interfaces);
        }
    }

    /**
     * Collects a class, array, primitive, or type-variable signature subtree.
     */
    private abstract static class TypeFrame extends SignatureVisitor {
        protected final Consumer<TypeRef> sink;
        protected boolean array;

        TypeFrame(Consumer<TypeRef> sink) {
            super(ASM_API);
            this.sink = sink;
        }

        @Override
        public void visitBaseType(char descriptor) {
            emit(primitiveFromDescriptor(descriptor));
        }

        @Override
        public void visitTypeVariable(String name) {
            emit(TypeRef.typeVariable(name));
        }

        @Override
        public SignatureVisitor visitArrayType() {
            array = true;
            return this;
        }

        protected void emit(TypeRef type) {
            if (array) {
                type = new TypeRef.Array(type);
                array = false;
            }
            sink.accept(type);
        }
    }

    /**
     * Collects a reference type, including inner classes and type arguments.
     */
    private static final class ClassTypeFrame extends TypeFrame {
        private String className;
        private final List<TypeRef> typeArgs = new ArrayList<>();
        private ClassTypeFrame inner;

        ClassTypeFrame(Consumer<TypeRef> sink) {
            super(sink);
        }

        @Override
        public void visitClassType(String name) {
            className = Interner.intern(name);
        }

        @Override
        public void visitInnerClassType(String name) {
            inner = new ClassTypeFrame(sink);
            inner.className = Interner.intern(className + "$" + name);
        }

        @Override
        public SignatureVisitor visitTypeArgument(char wildcard) {
            return new ClassTypeFrame(bound -> typeArgs.add(toWildcardArg(wildcard, bound)));
        }

        @Override
        public void visitTypeArgument() {
            typeArgs.add(TypeRef.Wildcard.unbounded());
        }

        @Override
        public void visitEnd() {
            if (inner != null) {
                inner.visitEnd();
                return;
            }
            TypeRef raw = TypeRef.resolved(className);
            TypeRef type = typeArgs.isEmpty()
                    ? raw
                    : new TypeRef.Parameterized(raw, List.copyOf(typeArgs));
            emit(type);
        }
    }
}
