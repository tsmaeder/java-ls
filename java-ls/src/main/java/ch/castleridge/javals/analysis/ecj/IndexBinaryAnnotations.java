package ch.castleridge.javals.analysis.ecj;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.eclipse.jdt.internal.compiler.env.ClassSignature;
import org.eclipse.jdt.internal.compiler.env.EnumConstantSignature;
import org.eclipse.jdt.internal.compiler.env.IBinaryAnnotation;
import org.eclipse.jdt.internal.compiler.env.IBinaryElementValuePair;
import org.eclipse.jdt.internal.compiler.impl.BooleanConstant;
import org.eclipse.jdt.internal.compiler.impl.ByteConstant;
import org.eclipse.jdt.internal.compiler.impl.CharConstant;
import org.eclipse.jdt.internal.compiler.impl.DoubleConstant;
import org.eclipse.jdt.internal.compiler.impl.FloatConstant;
import org.eclipse.jdt.internal.compiler.impl.IntConstant;
import org.eclipse.jdt.internal.compiler.impl.LongConstant;
import org.eclipse.jdt.internal.compiler.impl.ShortConstant;
import org.eclipse.jdt.internal.compiler.impl.StringConstant;

import ch.castleridge.javals.indexing.model.AnnotationRef;
import ch.castleridge.javals.indexing.model.AnnotationValue;
import ch.castleridge.javals.indexing.model.Type;
import ch.castleridge.javals.indexing.model.Type.Array;
import ch.castleridge.javals.indexing.model.Type.Parameterized;
import ch.castleridge.javals.indexing.model.Type.Primitive;
import ch.castleridge.javals.indexing.model.TypeRef;

/** Converts indexed {@link AnnotationRef}s into ECJ {@link IBinaryAnnotation}s. */
final class IndexBinaryAnnotations {
    private IndexBinaryAnnotations() {}

    static IBinaryAnnotation[] of(AnnotationRef[] refs, IndexTypeEncoding encoding) {
        if (refs == null || refs.length == 0) return null;
        List<IBinaryAnnotation> out = new ArrayList<>(refs.length);
        for (AnnotationRef ref : refs) {
            IBinaryAnnotation made = of(ref, encoding);
            if (made != null) out.add(made);
        }
        return out.isEmpty() ? null : out.toArray(IBinaryAnnotation[]::new);
    }

    static IBinaryAnnotation of(AnnotationRef ref, IndexTypeEncoding encoding) {
        if (ref == null) return null;
        String jvm = resolveAnnotationType(ref, encoding);
        if (jvm == null || jvm.isBlank()) return null;
        char[] typeName = ("L" + jvm + ";").toCharArray();
        IBinaryElementValuePair[] pairs = pairs(ref.values(), encoding);
        boolean deprecated = "java/lang/Deprecated".equals(jvm);
        return new IndexBinaryAnnotation(typeName, pairs, deprecated);
    }

    static Object defaultValue(AnnotationValue value, IndexTypeEncoding encoding) {
        return encodeValue(value, encoding);
    }

    private static final IBinaryElementValuePair[] NO_PAIRS = new IBinaryElementValuePair[0];

    private static IBinaryElementValuePair[] pairs(
            Map<String, AnnotationValue> values, IndexTypeEncoding encoding) {
        if (values == null || values.isEmpty()) return NO_PAIRS;
        List<IBinaryElementValuePair> out = new ArrayList<>(values.size());
        for (Map.Entry<String, AnnotationValue> entry : values.entrySet()) {
            Object encoded = encodeValue(entry.getValue(), encoding);
            if (encoded == null) continue;
            out.add(new IndexBinaryElementValuePair(entry.getKey().toCharArray(), encoded));
        }
        return out.isEmpty() ? NO_PAIRS : out.toArray(IBinaryElementValuePair[]::new);
    }

    private static Object encodeValue(AnnotationValue value, IndexTypeEncoding encoding) {
        if (value == null) return null;
        return switch (value) {
            case AnnotationValue.Primitive p -> constant(p.boxed());
            case AnnotationValue.Str s -> StringConstant.fromValue(s.value());
            case AnnotationValue.ClassRef c -> {
                String descriptor = classDescriptor(c.type(), encoding);
                yield descriptor == null ? null : new ClassSignature(descriptor.toCharArray());
            }
            case AnnotationValue.EnumConst e -> {
                String type = enumTypeDescriptor(e.enumType(), encoding);
                yield type == null
                        ? null
                        : new EnumConstantSignature(type.toCharArray(), e.constant().toCharArray());
            }
            case AnnotationValue.Arr a -> {
                Object[] elements = new Object[a.elements().length];
                int n = 0;
                for (AnnotationValue element : a.elements()) {
                    Object encoded = encodeValue(element, encoding);
                    if (encoded != null) elements[n++] = encoded;
                }
                if (n == 0) yield null;
                if (n == elements.length) yield elements;
                Object[] trimmed = new Object[n];
                System.arraycopy(elements, 0, trimmed, 0, n);
                yield trimmed;
            }
            case AnnotationValue.Nested n -> of(n.annotation(), encoding);
            case AnnotationValue.Unsupported ignored -> null;
        };
    }

    private static Object constant(Object boxed) {
        return switch (boxed) {
            case Boolean b -> BooleanConstant.fromValue(b);
            case Byte b -> ByteConstant.fromValue(b);
            case Short s -> ShortConstant.fromValue(s);
            case Character c -> CharConstant.fromValue(c);
            case Integer i -> IntConstant.fromValue(i);
            case Long l -> LongConstant.fromValue(l);
            case Float f -> FloatConstant.fromValue(f);
            case Double d -> DoubleConstant.fromValue(d);
            default -> null;
        };
    }

    private static String classDescriptor(Type type, IndexTypeEncoding encoding) {
        Type plain = IndexTypeEncoding.unwrap(type);
        if (plain instanceof Primitive primitive) {
            return switch (primitive) {
                case VOID -> "V";
                case BOOLEAN -> "Z";
                case BYTE -> "B";
                case CHAR -> "C";
                case SHORT -> "S";
                case INT -> "I";
                case LONG -> "J";
                case FLOAT -> "F";
                case DOUBLE -> "D";
            };
        }
        if (plain instanceof Array || plain instanceof Parameterized || plain instanceof TypeRef) {
            return encoding.descriptor(plain);
        }
        return null;
    }

    private static String enumTypeDescriptor(Type enumType, IndexTypeEncoding encoding) {
        Type plain = IndexTypeEncoding.unwrap(enumType);
        if (plain instanceof TypeRef ref) return "L" + encoding.resolve(ref) + ";";
        if (plain instanceof Parameterized parameterized) {
            return "L" + encoding.resolve(parameterized.raw()) + ";";
        }
        return null;
    }

    private static String resolveAnnotationType(AnnotationRef ref, IndexTypeEncoding encoding) {
        return switch (ref.annotationType()) {
            case TypeRef.Resolved resolved -> resolved.jvmBinaryName();
            case TypeRef.Unresolved unresolved -> encoding.resolve(unresolved);
        };
    }

    private record IndexBinaryAnnotation(
            char[] typeName,
            IBinaryElementValuePair[] pairs,
            boolean deprecated) implements IBinaryAnnotation {
        @Override
        public char[] getTypeName() {
            return typeName;
        }

        @Override
        public IBinaryElementValuePair[] getElementValuePairs() {
            return pairs;
        }

        @Override
        public boolean isDeprecatedAnnotation() {
            return deprecated;
        }
    }

    private record IndexBinaryElementValuePair(char[] name, Object value)
            implements IBinaryElementValuePair {
        @Override
        public char[] getName() {
            return name;
        }

        @Override
        public Object getValue() {
            return value;
        }
    }
}
