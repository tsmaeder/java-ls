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
package ch.castleridge.javals.indexing.source;

import ch.castleridge.javals.indexing.index.Index;
import ch.castleridge.javals.indexing.source.ecj.EcjSourceIndexer;
import ch.castleridge.javals.indexing.source.javac.JavacSourceIndexer;

/**
 * Parses a single Java source file and emits type entries into an {@link Index}.
 * Implementations must be classpath-free (parse-only).
 */
@FunctionalInterface
public interface SourceIndexer {

    void index(String resourcePath, String sourceUri, CharSequence content, Index into);

    static SourceIndexer javac() {
        return JavacSourceIndexer.INSTANCE;
    }

    static SourceIndexer ecj() {
        return EcjSourceIndexer.INSTANCE;
    }

    static SourceIndexer of(String name) {
        if (name == null || name.isBlank()) {
            return javac();
        }
        return switch (name.trim().toLowerCase()) {
            case "ecj" -> ecj();
            case "javac" -> javac();
            default -> javac();
        };
    }
}
