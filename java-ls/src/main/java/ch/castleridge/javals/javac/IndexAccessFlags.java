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

    static long classFlags(TypeEntry entry) {
        int raw = entry.modifiers();
        if (!entry.isSourceEntry()) {
            return adjustClassFlags(raw);
        }
        int flags = raw;
        int synthesized = switch (entry.declKind()) {
            case INTERFACE -> flags | Opcodes.ACC_INTERFACE | Opcodes.ACC_ABSTRACT;
            case ENUM -> flags | Opcodes.ACC_ENUM | Opcodes.ACC_FINAL;
            case ANNOTATION -> flags | Opcodes.ACC_ANNOTATION | Opcodes.ACC_INTERFACE | Opcodes.ACC_ABSTRACT;
            case RECORD -> flags | Opcodes.ACC_RECORD | Opcodes.ACC_FINAL;
            default -> flags;
        };
        return adjustClassFlags(synthesized);
    }

    /**
     * Mirror javac's {@code ClassReader.adjustClassFlags}: strip the
     * {@code ACC_SUPER} bit (which overlaps with {@code Flags.SYNCHRONIZED}
     * in javac's flag space) and promote ASM's {@code ACC_MODULE} bit to
     * javac's wider {@code Flags.MODULE} so module-info symbols are
     * recognised correctly downstream.
     */
    private static long adjustClassFlags(int raw) {
        long flags = Integer.toUnsignedLong(raw);
        if ((flags & Opcodes.ACC_MODULE) != 0) {
            flags &= ~Opcodes.ACC_MODULE;
            flags |= Flags.MODULE;
        }
        return flags & ~Opcodes.ACC_SUPER;
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
        if (owner.isSourceEntry() && isInterfaceLike(owner.declKind())) {
            if ((flags & (Opcodes.ACC_PUBLIC | Opcodes.ACC_PRIVATE | Opcodes.ACC_PROTECTED)) == 0) {
                flags |= Opcodes.ACC_PUBLIC;
            }
            if (!method.hasBody()
                    && (flags & (Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC)) == 0) {
                flags |= Opcodes.ACC_ABSTRACT;
            }
        }
        // Mirror javac's readMethod: a concrete, non-static, non-private
        // method on an interface is a default method. The DEFAULT flag is
        // what Symbol.isDefault() consults, and it must also be propagated
        // to the owner (done at the call site, since methodFlags only has
        // local information about this single method).
        if (isInterfaceOwner(owner)
                && method.hasBody()
                && (flags & (Opcodes.ACC_STATIC | Opcodes.ACC_PRIVATE | Opcodes.ACC_ABSTRACT)) == 0) {
            flags |= Flags.DEFAULT;
        }
        return flags;
    }

    private static boolean isInterfaceOwner(TypeEntry owner) {
        if (owner.isSourceEntry()) {
            return isInterfaceLike(owner.declKind());
        }
        return (owner.modifiers() & Opcodes.ACC_INTERFACE) != 0;
    }

    private static boolean isInterfaceLike(TypeDeclKind kind) {
        return kind == TypeDeclKind.INTERFACE || kind == TypeDeclKind.ANNOTATION;
    }
}
