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
 * Rules for which declarations belong in the structural index: only
 * members that might be visible from another compilation unit.
 *
 * <p>Private fields and methods are omitted; package-private, protected,
 * and public stay. Private constructors ({@code <init>}) are kept so a
 * type with only private constructors is not misread as having a public
 * default constructor. Private nested types are omitted entirely.
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
     * Whether a nested type (or its classfile) should produce a
     * {@link TypeEntry}. Private nested types are skipped.
     */
    public static boolean shouldIndexType(int modifiers) {
        return !isPrivate(modifiers);
    }
}
