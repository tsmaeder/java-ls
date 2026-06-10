package ch.castleridge.javals.indexing.model;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.signature.SignatureReader;
import org.objectweb.asm.signature.SignatureVisitor;

import ch.castleridge.javals.indexing.intern.Interner;

/**
 * Parses JVM {@code Signature} attribute strings into {@link Type} trees.
 */
public final class SignatureRefs {

    private static final int ASM_API = Opcodes.ASM9;

    private SignatureRefs() {}

    public record MethodRefs(
            List<TypeParamRef> typeParams,
            List<Type> paramTypes,
            Type returnType,
            List<Type> throwsTypes) {
        public MethodRefs {
            typeParams = typeParams == null ? List.of() : List.copyOf(typeParams);
            paramTypes = paramTypes == null ? List.of() : List.copyOf(paramTypes);
            throwsTypes = throwsTypes == null ? List.of() : List.copyOf(throwsTypes);
        }
    }

    public record ClassRefs(Type superClass, List<Type> interfaces) {
        public ClassRefs {
            interfaces = interfaces == null ? List.of() : List.copyOf(interfaces);
        }
    }

    /**
     * Pull formal type parameter names <em>and bounds</em> from a
     * signature prefix. Each declared bound is captured as a
     * {@link Type}; if a parameter has no declared bound the result is
     * the canonical {@code [java/lang/Object]} list (see
     * {@link TypeParamRef}).
     */
    public static List<TypeParamRef> parseFormalTypeParameters(String signature) {
        if (signature == null || signature.isEmpty() || signature.charAt(0) != '<') {
            return List.of();
        }
        FormalTypeParameterCollector collector = new FormalTypeParameterCollector();
        new SignatureReader(signature).accept(collector);
        return collector.result();
    }

    public static Type parseType(String signature) {
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

    private static Type primitiveFromDescriptor(char descriptor) {
        return switch (descriptor) {
            case 'V' -> Type.Primitive.VOID;
            case 'Z' -> Type.Primitive.BOOLEAN;
            case 'B' -> Type.Primitive.BYTE;
            case 'C' -> Type.Primitive.CHAR;
            case 'S' -> Type.Primitive.SHORT;
            case 'I' -> Type.Primitive.INT;
            case 'J' -> Type.Primitive.LONG;
            case 'F' -> Type.Primitive.FLOAT;
            case 'D' -> Type.Primitive.DOUBLE;
            default -> TypeRef.resolved("java/lang/Object");
        };
    }

    private static Type toWildcardArg(char wildcard, Type bound) {
        return switch (wildcard) {
            case SignatureVisitor.EXTENDS -> bound == null
                    ? Type.Wildcard.unbounded()
                    : Type.Wildcard.extendsBound(bound);
            case SignatureVisitor.SUPER -> Type.Wildcard.superBound(bound);
            case SignatureVisitor.INSTANCEOF -> bound == null
                    ? Type.Wildcard.unbounded()
                    : bound;
            default -> bound == null
                    ? Type.Wildcard.unbounded()
                    : bound;
        };
    }

    private static final class TypeCollector {
        private Type result;

        SignatureVisitor visitor() {
            return new ClassTypeFrame(ref -> result = ref);
        }

        Type result() {
            return result;
        }
    }

    private static final class MethodCollector {
        private final List<Type> paramTypes = new ArrayList<>();
        private final List<Type> throwsTypes = new ArrayList<>();
        private Type returnType;
        private final FormalTypeParameterCollector typeParamCollector = new FormalTypeParameterCollector();

        SignatureVisitor visitor() {
            return new SignatureVisitor(ASM_API) {
                @Override
                public void visitFormalTypeParameter(String name) {
                    typeParamCollector.visitFormalTypeParameter(name);
                }

                @Override
                public SignatureVisitor visitClassBound() {
                    return typeParamCollector.visitClassBound();
                }

                @Override
                public SignatureVisitor visitInterfaceBound() {
                    return typeParamCollector.visitInterfaceBound();
                }

                @Override
                public SignatureVisitor visitParameterType() {
                    typeParamCollector.flush();
                    return new ClassTypeFrame(paramTypes::add);
                }

                @Override
                public SignatureVisitor visitReturnType() {
                    typeParamCollector.flush();
                    return new ClassTypeFrame(ref -> returnType = ref);
                }

                @Override
                public SignatureVisitor visitExceptionType() {
                    return new ClassTypeFrame(throwsTypes::add);
                }
            };
        }

        MethodRefs result() {
            return new MethodRefs(typeParamCollector.result(), paramTypes, returnType, throwsTypes);
        }
    }

    /**
     * Streams formal type parameters into a list of {@link TypeParamRef}s,
     * collecting class and interface bounds emitted between
     * {@link SignatureVisitor#visitFormalTypeParameter} calls.
     *
     * <p>Must be {@link #flush()}ed once after the last formal parameter
     * (typically right before a {@code visitSuperclass} /
     * {@code visitParameterType} / {@code visitReturnType} transition)
     * so the trailing parameter is materialised.
     */
    private static final class FormalTypeParameterCollector extends SignatureVisitor {
        private final List<TypeParamRef> collected = new ArrayList<>();
        private String currentName;
        private List<Type> currentBounds;

        FormalTypeParameterCollector() {
            super(ASM_API);
        }

        @Override
        public void visitFormalTypeParameter(String name) {
            flush();
            if (name != null && !name.isEmpty()) {
                currentName = Interner.intern(name);
                currentBounds = new ArrayList<>();
            }
        }

        @Override
        public SignatureVisitor visitClassBound() {
            return new ClassTypeFrame(ref -> {
                if (currentBounds != null) currentBounds.add(ref);
            });
        }

        @Override
        public SignatureVisitor visitInterfaceBound() {
            return new ClassTypeFrame(ref -> {
                if (currentBounds != null) currentBounds.add(ref);
            });
        }

        @Override
        public SignatureVisitor visitSuperclass() {
            flush();
            return new ClassTypeFrame(ref -> { });
        }

        @Override
        public SignatureVisitor visitInterface() {
            flush();
            return new ClassTypeFrame(ref -> { });
        }

        @Override
        public SignatureVisitor visitParameterType() {
            flush();
            return new ClassTypeFrame(ref -> { });
        }

        @Override
        public SignatureVisitor visitReturnType() {
            flush();
            return new ClassTypeFrame(ref -> { });
        }

        void flush() {
            if (currentName != null) {
                collected.add(new TypeParamRef(currentName, currentBounds));
                currentName = null;
                currentBounds = null;
            }
        }

        List<TypeParamRef> result() {
            flush();
            return collected.isEmpty() ? List.of() : List.copyOf(collected);
        }
    }

    private static final class ClassCollector {
        private Type superClass;
        private final List<Type> interfaces = new ArrayList<>();

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
        protected final Consumer<Type> sink;
        protected boolean array;

        TypeFrame(Consumer<Type> sink) {
            super(ASM_API);
            this.sink = sink;
        }

        @Override
        public void visitBaseType(char descriptor) {
            emit(primitiveFromDescriptor(descriptor));
        }

        @Override
        public void visitTypeVariable(String name) {
            emit(Type.typeVariable(name));
        }

        @Override
        public SignatureVisitor visitArrayType() {
            array = true;
            return this;
        }

        protected void emit(Type type) {
            if (array) {
                type = new Type.Array(type);
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
        private final List<Type> typeArgs = new ArrayList<>();
        private ClassTypeFrame inner;

        ClassTypeFrame(Consumer<Type> sink) {
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
            typeArgs.add(Type.Wildcard.unbounded());
        }

        @Override
        public void visitEnd() {
            if (inner != null) {
                inner.visitEnd();
                return;
            }
            TypeRef raw = TypeRef.resolved(className);
            Type type = typeArgs.isEmpty()
                    ? raw
                    : new Type.Parameterized(raw, List.copyOf(typeArgs));
            emit(type);
        }
    }
}
