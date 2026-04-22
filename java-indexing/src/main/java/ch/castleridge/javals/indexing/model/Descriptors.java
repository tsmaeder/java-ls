package ch.castleridge.javals.indexing.model;

import java.util.ArrayList;
import java.util.List;

import ch.castleridge.javals.indexing.intern.Interner;

/**
 * Helpers for translating JVM descriptor strings into {@link TypeRef}
 * values. Classfile-sourced entries go through here exclusively; source
 * indexing builds {@link TypeRef}s directly from the AST and never touches
 * descriptors.
 *
 * <p>Only descriptors (as used in the classfile's {@code Code} / method /
 * field attributes) are understood - generic signatures live elsewhere.
 */
public final class Descriptors {

    private Descriptors() {}

    /**
     * Parse a single field descriptor (e.g. {@code Ljava/util/List;},
     * {@code [I}, {@code Z}) into a {@link TypeRef}.
     */
    public static TypeRef parseField(String descriptor) {
        if (descriptor == null || descriptor.isEmpty()) {
            return TypeRef.resolved("java/lang/Object");
        }
        int[] pos = {0};
        TypeRef t = parseOne(descriptor, pos);
        return t == null ? TypeRef.resolved("java/lang/Object") : t;
    }

    /**
     * Parse a method descriptor (e.g. {@code (ILjava/lang/String;)V}) into
     * its return type and parameter types.
     */
    public static MethodRefs parseMethod(String descriptor) {
        if (descriptor == null || descriptor.isEmpty() || descriptor.charAt(0) != '(') {
            return new MethodRefs(TypeRef.Primitive.VOID, List.of());
        }
        int[] pos = {1};
        List<TypeRef> params = new ArrayList<>();
        while (pos[0] < descriptor.length() && descriptor.charAt(pos[0]) != ')') {
            TypeRef t = parseOne(descriptor, pos);
            if (t == null) break;
            params.add(t);
        }
        if (pos[0] < descriptor.length() && descriptor.charAt(pos[0]) == ')') {
            pos[0]++;
        }
        TypeRef ret = parseOne(descriptor, pos);
        if (ret == null) ret = TypeRef.Primitive.VOID;
        return new MethodRefs(ret, List.copyOf(params));
    }

    private static TypeRef parseOne(String desc, int[] pos) {
        if (pos[0] >= desc.length()) return null;
        char c = desc.charAt(pos[0]);
        switch (c) {
            case 'V': pos[0]++; return TypeRef.Primitive.VOID;
            case 'Z': pos[0]++; return TypeRef.Primitive.BOOLEAN;
            case 'B': pos[0]++; return TypeRef.Primitive.BYTE;
            case 'C': pos[0]++; return TypeRef.Primitive.CHAR;
            case 'S': pos[0]++; return TypeRef.Primitive.SHORT;
            case 'I': pos[0]++; return TypeRef.Primitive.INT;
            case 'J': pos[0]++; return TypeRef.Primitive.LONG;
            case 'F': pos[0]++; return TypeRef.Primitive.FLOAT;
            case 'D': pos[0]++; return TypeRef.Primitive.DOUBLE;
            case '[': {
                pos[0]++;
                TypeRef elem = parseOne(desc, pos);
                if (elem == null) return null;
                return new TypeRef.Array(elem);
            }
            case 'L': {
                int semi = desc.indexOf(';', pos[0]);
                if (semi < 0) return null;
                String name = Interner.intern(desc.substring(pos[0] + 1, semi));
                pos[0] = semi + 1;
                return TypeRef.resolved(name);
            }
            default: return null;
        }
    }

    /**
     * Build a {@link TypeRef} from a JVM internal class name
     * (e.g. {@code java/lang/Object}).
     */
    public static TypeRef classRef(String jvmInternalName) {
        if (jvmInternalName == null || jvmInternalName.isEmpty()) return null;
        return TypeRef.resolved(jvmInternalName);
    }

    /** Return value of {@link #parseMethod(String)}. */
    public record MethodRefs(TypeRef returnType, List<TypeRef> paramTypes) {
        public MethodRefs {
            paramTypes = paramTypes == null ? List.of() : List.copyOf(paramTypes);
        }
    }
}
