package ch.castleridge.javals.indexing.classfile;

import org.objectweb.asm.Type;

final class AsmTypeStrings {

    private AsmTypeStrings() {}

    static String jvmForm(Type type) {
        if (type == null) {
            return "";
        }
        return switch (type.getSort()) {
            case Type.ARRAY -> type.getDescriptor();
            case Type.OBJECT -> type.getInternalName();
            default -> type.getDescriptor();
        };
    }

    static String argTypesJoined(String methodDescriptor) {
        Type[] args = Type.getArgumentTypes(methodDescriptor);
        if (args.length == 0) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < args.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(jvmForm(args[i]));
        }
        return sb.toString();
    }

    static String returnTypeJvm(String methodDescriptor) {
        return jvmForm(Type.getReturnType(methodDescriptor));
    }

    /** FQN / Java class name for reference indexing. */
    static String classNameFromInsnDesc(String desc) {
        if (desc == null || desc.isEmpty()) {
            return "";
        }
        Type t = desc.charAt(0) == '[' ? Type.getType(desc) : Type.getObjectType(desc);
        return t.getClassName();
    }
}
