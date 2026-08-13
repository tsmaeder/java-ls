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
package ch.castleridge.javals.analysis.ecj;

import org.eclipse.jdt.internal.compiler.classfmt.ClassFileConstants;
import org.eclipse.jdt.internal.compiler.lookup.ExtraCompilerModifiers;
import org.eclipse.jdt.internal.compiler.lookup.TagBits;

import ch.castleridge.javals.indexing.model.AnnotationRef;
import ch.castleridge.javals.indexing.model.ClassFileTypeEntry;
import ch.castleridge.javals.indexing.model.FieldEntry;
import ch.castleridge.javals.indexing.model.MethodEntry;
import ch.castleridge.javals.indexing.model.SourceTypeEntry;
import ch.castleridge.javals.indexing.model.TypeDeclKind;
import ch.castleridge.javals.indexing.model.TypeEntry;

/**
 * Maps indexed modifiers onto ECJ {@link ClassFileConstants} /
 * {@link ExtraCompilerModifiers} bits. ASM's {@code ACC_RECORD} (0x10000) is
 * remapped to {@link ExtraCompilerModifiers#AccRecord}.
 */
final class IndexBinaryAccessFlags {
    /** ASM {@code ACC_RECORD}; not an ECJ ClassFileConstants bit. */
    static final int ACC_RECORD = 0x10000;
    static final int ACC_ANNOTATION = ClassFileConstants.AccAnnotation;

    private IndexBinaryAccessFlags() {}

    static int rawModifiers(TypeEntry entry) {
        return switch (entry) {
            case SourceTypeEntry source -> source.modifiers();
            case ClassFileTypeEntry classFile -> classFile.modifiers();
        };
    }

    static int classModifiers(TypeEntry entry) {
        int access = rawModifiers(entry);
        if (entry instanceof SourceTypeEntry source) {
            access |= switch (source.declKind()) {
                case INTERFACE -> ClassFileConstants.AccInterface | ClassFileConstants.AccAbstract;
                case ANNOTATION -> ClassFileConstants.AccAnnotation
                        | ClassFileConstants.AccInterface
                        | ClassFileConstants.AccAbstract;
                case ENUM -> ClassFileConstants.AccEnum | ClassFileConstants.AccFinal;
                case RECORD -> ACC_RECORD | ClassFileConstants.AccFinal;
                default -> 0;
            };
        }
        boolean record = (access & ACC_RECORD) != 0
                || (entry instanceof SourceTypeEntry source && source.declKind() == TypeDeclKind.RECORD);
        access &= ~ACC_RECORD;
        if (record) access |= ExtraCompilerModifiers.AccRecord;
        if ((access & ClassFileConstants.AccInterface) == 0) {
            access |= ClassFileConstants.AccSuper;
        }
        if (entry.permittedSubclasses() != null && entry.permittedSubclasses().length > 0) {
            access |= ExtraCompilerModifiers.AccSealed;
        }
        if (hasDeprecated(annotationsOf(entry))) {
            access |= ClassFileConstants.AccDeprecated;
        }
        return access;
    }

    static int innerClassModifiers(TypeEntry outer, TypeEntry inner) {
        int flags = classModifiers(inner);
        if (!(inner instanceof SourceTypeEntry sourceInner)) return flags;
        if (isInterfaceOwner(outer)) {
            flags |= ClassFileConstants.AccPublic | ClassFileConstants.AccStatic;
        }
        if (isImplicitlyStaticMember(sourceInner.declKind())) {
            flags |= ClassFileConstants.AccStatic;
        }
        return flags;
    }

    static int fieldModifiers(TypeEntry owner, FieldEntry field) {
        int access = field.modifiers();
        if (owner instanceof SourceTypeEntry source && isInterfaceLike(source.declKind())) {
            access |= ClassFileConstants.AccPublic
                    | ClassFileConstants.AccStatic
                    | ClassFileConstants.AccFinal;
        }
        if (hasDeprecated(field.annotations())) {
            access |= ClassFileConstants.AccDeprecated;
        }
        return access;
    }

    static int methodModifiers(TypeEntry owner, MethodEntry method) {
        int access = method.modifiers();
        if (method.varargs()) access |= ClassFileConstants.AccVarargs;
        if (owner instanceof SourceTypeEntry source && isInterfaceLike(source.declKind())) {
            if ((access & (ClassFileConstants.AccPublic
                    | ClassFileConstants.AccPrivate
                    | ClassFileConstants.AccProtected)) == 0) {
                access |= ClassFileConstants.AccPublic;
            }
            if (!method.hasBody()
                    && (access & (ClassFileConstants.AccPrivate | ClassFileConstants.AccStatic)) == 0) {
                access |= ClassFileConstants.AccAbstract;
            }
        }
        if (method.annotationDefault() != null) {
            access |= ClassFileConstants.AccAnnotationDefault;
        }
        if (hasDeprecated(method.annotations())) {
            access |= ClassFileConstants.AccDeprecated;
        }
        return access;
    }

    static long annotationTagBits(AnnotationRef[] annotations) {
        if (annotations == null || annotations.length == 0) return 0L;
        long bits = 0L;
        for (AnnotationRef ref : annotations) {
            if ("java/lang/Deprecated".equals(ref.jvmName())) {
                bits |= TagBits.AnnotationDeprecated;
            }
        }
        return bits;
    }

    static boolean isRecord(TypeEntry entry) {
        if (entry instanceof SourceTypeEntry source && source.declKind() == TypeDeclKind.RECORD) {
            return true;
        }
        return (rawModifiers(entry) & ACC_RECORD) != 0;
    }

    static boolean isInterfaceOwner(TypeEntry owner) {
        return switch (owner) {
            case SourceTypeEntry source -> isInterfaceLike(source.declKind());
            case ClassFileTypeEntry classFile ->
                    (classFile.modifiers() & ClassFileConstants.AccInterface) != 0;
        };
    }

    private static boolean isImplicitlyStaticMember(TypeDeclKind kind) {
        return kind == TypeDeclKind.INTERFACE
                || kind == TypeDeclKind.ANNOTATION
                || kind == TypeDeclKind.ENUM
                || kind == TypeDeclKind.RECORD;
    }

    private static boolean isInterfaceLike(TypeDeclKind kind) {
        return kind == TypeDeclKind.INTERFACE || kind == TypeDeclKind.ANNOTATION;
    }

    private static AnnotationRef[] annotationsOf(TypeEntry entry) {
        return switch (entry) {
            case SourceTypeEntry source -> source.annotations();
            case ClassFileTypeEntry classFile -> classFile.annotations();
        };
    }

    private static boolean hasDeprecated(AnnotationRef[] annotations) {
        if (annotations == null) return false;
        for (AnnotationRef ref : annotations) {
            if ("java/lang/Deprecated".equals(ref.jvmName())) return true;
        }
        return false;
    }
}
