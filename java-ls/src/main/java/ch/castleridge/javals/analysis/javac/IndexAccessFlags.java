/**
 * Copyright 2026 by Anysphere Inc.
 * 
 * Licensed under the MIT License.
 * 
 * SPDX-License-Identifier: MIT
 *
 * Author: Thomas Mäder, Castle Ridge Software
 *
 */
package ch.castleridge.javals.analysis.javac;

import org.objectweb.asm.Opcodes;

import com.sun.tools.javac.code.Flags;

import ch.castleridge.javals.indexing.model.AnnotationRef;
import ch.castleridge.javals.indexing.model.AnnotationValue;
import ch.castleridge.javals.indexing.model.ClassFileTypeEntry;
import ch.castleridge.javals.indexing.model.FieldEntry;
import ch.castleridge.javals.indexing.model.MethodEntry;
import ch.castleridge.javals.indexing.model.SourceTypeEntry;
import ch.castleridge.javals.indexing.model.TypeDeclKind;
import ch.castleridge.javals.indexing.model.TypeEntry;

/**
 * Synthesizes JVM classfile access flags from indexed declaration data when
 * filling symbols in {@link IndexClassReader}.
 */
final class IndexAccessFlags {

    private IndexAccessFlags() {}

    static long classFlags(TypeEntry entry) {
        int synthesized = switch (entry) {
            case SourceTypeEntry source -> switch (source.declKind()) {
                case INTERFACE -> source.modifiers() | Opcodes.ACC_INTERFACE | Opcodes.ACC_ABSTRACT;
                case ENUM -> source.modifiers() | Opcodes.ACC_ENUM | Opcodes.ACC_FINAL;
                case ANNOTATION ->
                        source.modifiers() | Opcodes.ACC_ANNOTATION | Opcodes.ACC_INTERFACE | Opcodes.ACC_ABSTRACT;
                case RECORD -> source.modifiers() | Opcodes.ACC_RECORD | Opcodes.ACC_FINAL;
                default -> source.modifiers();
            };
            case ClassFileTypeEntry classFile -> classFile.modifiers();
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

    /**
     * Compute the access flags for a nested type as read through
     * {@link IndexClassReader#readInnerClassesFromIndex}. A member type of
     * an interface (or annotation type) is implicitly {@code public} and
     * {@code static} (JLS 9.5), but {@code SourceIndexer} only records the
     * explicit source modifiers, so a bare {@code interface Action {}}
     * inside an interface would otherwise be synthesized package-private
     * and non-static. javac then rejects every use as "not public … cannot
     * be accessed from outside package", and the missing {@code static}
     * bit also makes the reader wire a bogus enclosing instance type.
     *
     * <p>The promotion is restricted to source entries: bytecode-indexed
     * inners already carry the right bits (merged from the InnerClasses
     * attribute in {@code ClassFileIndexer.visitInnerClass}), so re-OR-ing
     * them would be redundant. This mirrors the source-only interface-member
     * handling already done for fields and methods.
     */
    static long innerClassFlags(TypeEntry outer, TypeEntry inner) {
        long flags = classFlags(inner);
        if (!(inner instanceof SourceTypeEntry sourceInner)) {
            return flags;
        }
        if (isInterfaceOwner(outer)) {
            flags |= Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC;
        }
        // A member interface, enum, annotation or record is implicitly
        // static regardless of the enclosing type's kind (JLS 8.5/9.5).
        // SourceIndexer only records explicit modifiers, so e.g. an
        // `interface Listener<C> {}` nested in a generic `class
        // PoolWaiter<C>` would otherwise be synthesized as a non-static
        // inner of the generic outer. The reader then wires it as
        // PoolWaiter<C>.Listener, which no longer matches uses written as
        // the (correct) static form PoolWaiter.Listener<C> - surfacing as
        // "improperly formed type, type arguments given on a raw type" and
        // a cascade of bogus override/abstract-method errors.
        if (isImplicitlyStaticMember(sourceInner.declKind())) {
            flags |= Opcodes.ACC_STATIC;
        }
        return flags;
    }

    private static boolean isImplicitlyStaticMember(TypeDeclKind kind) {
        return kind == TypeDeclKind.INTERFACE
                || kind == TypeDeclKind.ANNOTATION
                || kind == TypeDeclKind.ENUM
                || kind == TypeDeclKind.RECORD;
    }

    static int fieldFlags(TypeEntry owner, FieldEntry field) {
        if (owner instanceof SourceTypeEntry sourceOwner) {
            int flags = field.modifiers();
            if (isInterfaceLike(sourceOwner.declKind())) {
                flags |= Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL;
            }
            return flags;
        }
        return field.modifiers();
    }

    static long methodFlags(TypeEntry owner, MethodEntry method) {
        long flags = Integer.toUnsignedLong(method.modifiers());
        // Classfile/ASM ACC_VARARGS (0x80) differs from javac's Flags.VARARGS bit.
        flags &= ~Opcodes.ACC_VARARGS;
        if (method.varargs()) {
            flags |= Flags.VARARGS;
        }
        if (owner instanceof SourceTypeEntry sourceOwner && isInterfaceLike(sourceOwner.declKind())) {
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

    /**
     * Mirror javac's deprecation handling: an indexed {@code @Deprecated}
     * declaration annotation sets {@link Flags#DEPRECATED} and
     * {@link Flags#DEPRECATED_ANNOTATION}, and {@code forRemoval = true}
     * additionally sets {@link Flags#DEPRECATED_REMOVAL}. These drive
     * {@code Symbol.isDeprecated()} / {@code isDeprecatedForRemoval()} and
     * the corresponding call-site diagnostics, which a freshly synthesized
     * symbol would otherwise miss (attaching the {@code Attribute.Compound}
     * alone is not enough).
     */
    static long withDeprecation(long flags, AnnotationRef[] annotations) {
        if (annotations == null) return flags;
        for (AnnotationRef ref : annotations) {
            if (!"java/lang/Deprecated".equals(ref.jvmName())) continue;
            flags |= Flags.DEPRECATED | Flags.DEPRECATED_ANNOTATION;
            AnnotationValue forRemoval = ref.values().get("forRemoval");
            if (forRemoval instanceof AnnotationValue.Primitive p
                    && Boolean.TRUE.equals(p.boxed())) {
                flags |= Flags.DEPRECATED_REMOVAL;
            }
        }
        return flags;
    }

    private static boolean isInterfaceOwner(TypeEntry owner) {
        return switch (owner) {
            case SourceTypeEntry source -> isInterfaceLike(source.declKind());
            case ClassFileTypeEntry classFile -> (classFile.modifiers() & Opcodes.ACC_INTERFACE) != 0;
        };
    }

    private static boolean isInterfaceLike(TypeDeclKind kind) {
        return kind == TypeDeclKind.INTERFACE || kind == TypeDeclKind.ANNOTATION;
    }
}
