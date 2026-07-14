package ch.castleridge.javals.indexing.model;

import java.util.ArrayList;
import java.util.List;

import ch.castleridge.javals.indexing.intern.Interner;

/**
 * Helpers for translating JVM descriptor strings into {@link Type}
 * values. Classfile-sourced entries go through here exclusively; source
 * indexing builds {@link Type}s directly from the AST and never touches
 * descriptors.
 *
 * <p>Only descriptors (as used in the classfile's {@code Code} / method /
 * field attributes) are understood - generic signatures live elsewhere.
 */
public final class Descriptors {

    private Descriptors() {}

    /**
     * Parse a single field descriptor (e.g. {@code Ljava/util/List;},
     * {@code [I}, {@code Z}) into a {@link Type}.
     */
    public static Type parseField(String descriptor) {
        if (descriptor == null || descriptor.isEmpty()) {
            return TypeRef.resolved("java/lang/Object");
        }
        int[] pos = {0};
        Type t = parseOne(descriptor, pos);
        return t == null ? TypeRef.resolved("java/lang/Object") : t;
    }

    /**
     * Parse a method descriptor (e.g. {@code (ILjava/lang/String;)V}) into
     * its return type and parameter types.
     */
    public static MethodRefs parseMethod(String descriptor) {
        if (descriptor == null || descriptor.isEmpty() || descriptor.charAt(0) != '(') {
            return new MethodRefs(Type.Primitive.VOID, List.of());
        }
        int[] pos = {1};
        List<Type> params = new ArrayList<>();
        while (pos[0] < descriptor.length() && descriptor.charAt(pos[0]) != ')') {
            Type t = parseOne(descriptor, pos);
            if (t == null) break;
            params.add(t);
        }
        if (pos[0] < descriptor.length() && descriptor.charAt(pos[0]) == ')') {
            pos[0]++;
        }
        Type ret = parseOne(descriptor, pos);
        if (ret == null) ret = Type.Primitive.VOID;
        return new MethodRefs(ret, params);
    }

    private static Type parseOne(String desc, int[] pos) {
        if (pos[0] >= desc.length()) return null;
        char c = desc.charAt(pos[0]);
        switch (c) {
            case 'V': pos[0]++; return Type.Primitive.VOID;
            case 'Z': pos[0]++; return Type.Primitive.BOOLEAN;
            case 'B': pos[0]++; return Type.Primitive.BYTE;
            case 'C': pos[0]++; return Type.Primitive.CHAR;
            case 'S': pos[0]++; return Type.Primitive.SHORT;
            case 'I': pos[0]++; return Type.Primitive.INT;
            case 'J': pos[0]++; return Type.Primitive.LONG;
            case 'F': pos[0]++; return Type.Primitive.FLOAT;
            case 'D': pos[0]++; return Type.Primitive.DOUBLE;
            case '[': {
                pos[0]++;
                Type elem = parseOne(desc, pos);
                if (elem == null) return null;
                return new Type.Array(elem);
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
    public record MethodRefs(Type returnType, List<Type> paramTypes) {
        public MethodRefs {
            paramTypes = paramTypes == null ? List.of() : paramTypes;
        }
    }
}
