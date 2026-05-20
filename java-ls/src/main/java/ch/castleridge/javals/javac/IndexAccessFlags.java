package ch.castleridge.javals.javac;

import org.objectweb.asm.Opcodes;

import com.sun.tools.javac.code.Flags;

import ch.castleridge.javals.indexing.model.FieldEntry;
import ch.castleridge.javals.indexing.model.MethodEntry;
import ch.castleridge.javals.indexing.model.TypeDeclKind;
import ch.castleridge.javals.indexing.model.TypeEntry;

/**
 * Synthesizes JVM classfile access flags from indexed declaration data when
 * filling symbols in {@link IndexClassReader}.
 */
final class IndexAccessFlags {

    private IndexAccessFlags() {}

    static int classFlags(TypeEntry entry) {
        if (!entry.isSourceEntry()) {
            return entry.modifiers();
        }
        int flags = entry.modifiers();
        return switch (entry.declKind()) {
            case INTERFACE -> flags | Opcodes.ACC_INTERFACE | Opcodes.ACC_ABSTRACT;
            case ENUM -> flags | Opcodes.ACC_ENUM | Opcodes.ACC_FINAL;
            case ANNOTATION -> flags | Opcodes.ACC_ANNOTATION | Opcodes.ACC_INTERFACE | Opcodes.ACC_ABSTRACT;
            case RECORD -> flags | Opcodes.ACC_RECORD | Opcodes.ACC_FINAL;
            default -> flags;
        };
    }

    static int fieldFlags(TypeEntry owner, FieldEntry field) {
        if (!owner.isSourceEntry()) {
            return field.modifiers();
        }
        int flags = field.modifiers();
        if (isInterfaceLike(owner.declKind())) {
            flags |= Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL;
        }
        return flags;
    }

    static long methodFlags(TypeEntry owner, MethodEntry method) {
        long flags = Integer.toUnsignedLong(method.modifiers());
        // Classfile/ASM ACC_VARARGS (0x80) differs from javac's Flags.VARARGS bit.
        flags &= ~Opcodes.ACC_VARARGS;
        if (method.varargs()) {
            flags |= Flags.VARARGS;
        }
        if (!owner.isSourceEntry()) {
            return flags;
        }
        if (isInterfaceLike(owner.declKind())) {
            if ((flags & (Opcodes.ACC_PUBLIC | Opcodes.ACC_PRIVATE | Opcodes.ACC_PROTECTED)) == 0) {
                flags |= Opcodes.ACC_PUBLIC;
            }
            if (!method.hasBody()
                    && (flags & (Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC)) == 0) {
                flags |= Opcodes.ACC_ABSTRACT;
            }
        }
        return flags;
    }

    private static boolean isInterfaceLike(TypeDeclKind kind) {
        return kind == TypeDeclKind.INTERFACE || kind == TypeDeclKind.ANNOTATION;
    }
}
