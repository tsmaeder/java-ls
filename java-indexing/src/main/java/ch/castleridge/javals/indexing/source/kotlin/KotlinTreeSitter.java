/**
 * Copyright 2026 by Castle Ridge Software GmbH
 *
 * Author: Thomas Mäder, Castle Ridge Software
 *
 */
package ch.castleridge.javals.indexing.source.kotlin;

import java.nio.charset.StandardCharsets;
import java.util.Optional;

import ch.castleridge.javals.indexing.treesitter.TreeSitterJavalsClient;
import ch.castleridge.javals.indexing.treesitter.TsNode;

/**
 * Kotlin parse facade over the per-thread {@code tree_sitter_javals} process.
 */
final class KotlinTreeSitter {

    private KotlinTreeSitter() {}

    static Optional<TsNode> parse(String sourceUri, String source) {
        if (source == null) return Optional.empty();
        String uri = sourceUri == null ? "" : sourceUri;
        return TreeSitterJavalsClient.current().parse("kotlin", uri, source);
    }

    static Optional<TsNode> parse(String sourceUri, byte[] sourceUtf8) {
        if (sourceUtf8 == null) return Optional.empty();
        String uri = sourceUri == null ? "" : sourceUri;
        return TreeSitterJavalsClient.current().parse("kotlin", uri, sourceUtf8);
    }

    static Optional<TsNode> parse(String source) {
        return parse("", source == null ? null : source.getBytes(StandardCharsets.UTF_8));
    }
}
