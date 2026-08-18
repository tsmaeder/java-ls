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
package ch.castleridge.javals.indexing.bytecode;

import ch.castleridge.javals.indexing.index.Index;

/**
 * Reads a single {@code .class} file and emits type/module entries into an {@link Index}.
 */
@FunctionalInterface
public interface BytecodeIndexer {

    void index(String resourcePath, String sourceUri, byte[] bytes, Index into);

    static BytecodeIndexer asm() {
        return ClassFileIndexer.INSTANCE;
    }

    static BytecodeIndexer turbine() {
        return TurbineClassFileIndexer.INSTANCE;
    }

    static BytecodeIndexer of(String name) {
        if (name == null || name.isBlank()) {
            return asm();
        }
        return switch (name.trim().toLowerCase()) {
            case "turbine" -> turbine();
            case "asm" -> asm();
            default -> asm();
        };
    }
}
