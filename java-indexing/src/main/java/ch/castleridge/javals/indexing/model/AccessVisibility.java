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
package ch.castleridge.javals.indexing.model;

import org.objectweb.asm.Opcodes;

/**
 * Rules for which declarations belong in the structural index.
 *
 * <p>Private fields and methods are omitted; package-private, protected,
 * and public stay. Private constructors ({@code <init>}) are kept so a
 * type with only private constructors is not misread as having a public
 * default constructor.
 *
 * <p>Private nested types are omitted from <em>source</em> indexing (not
 * visible across compilation units). They <em>are</em> indexed from
 * classfiles so compilers can resolve indirect references such as
 * {@code LinkedList$Node}; those binary entries may be stubs (identity
 * only). User-facing completion/search should hide them via
 * {@link #isPrivate(int)}.
 */
public final class AccessVisibility {

    private AccessVisibility() {}

    public static boolean isPrivate(int modifiers) {
        return (modifiers & Opcodes.ACC_PRIVATE) != 0;
    }

    /**
     * Whether a field or method should be stored on a {@link TypeEntry}.
     * Private members are skipped except constructors ({@code <init>}).
     */
    public static boolean shouldIndexMember(int modifiers, String name) {
        if (!isPrivate(modifiers)) return true;
        return "<init>".equals(name);
    }

    /**
     * Whether a nested type from <em>source</em> should produce a
     * {@link TypeEntry}. Private nested types are skipped.
     */
    public static boolean shouldIndexSourceType(int modifiers) {
        return !isPrivate(modifiers);
    }

    /**
     * Whether a nested type from a <em>classfile</em> should produce a
     * {@link TypeEntry}. Always true: private nesteds are required for
     * binary binding when they appear in signatures of the enclosing type.
     */
    public static boolean shouldIndexBinaryType(int modifiers) {
        return true;
    }

    /**
     * Whether a classfile type entry should be stored as a stub (identity /
     * hierarchy only, no members). Private nested types use stubs to limit
     * index footprint while remaining resolvable.
     */
    public static boolean shouldStubBinaryType(int modifiers, String jvmOwnerName) {
        return isPrivate(modifiers) && jvmOwnerName != null && jvmOwnerName.indexOf('$') >= 0;
    }
}
