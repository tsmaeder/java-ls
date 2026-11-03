/**
 * Copyright 2026 by Castle Ridge Software GmbH
 *
 * Author: Thomas Mäder, Castle Ridge Software
 *
 */
package ch.castleridge.javals.indexing.source.kotlin;

import java.util.HashSet;
import java.util.Set;

import ch.castleridge.javals.indexing.bloom.IdentifierBloomFilter;
import ch.castleridge.javals.indexing.treesitter.TsNode;

/**
 * Collects simple identifier names from a Kotlin CST for bloom filters.
 */
final class KotlinIdentifierCollector {

    private KotlinIdentifierCollector() {}

    static IdentifierBloomFilter collectAndBuild(TsNode root) {
        Set<String> names = new HashSet<>();
        walk(root, names);
        return IdentifierBloomFilter.create(names);
    }

    private static void walk(TsNode node, Set<String> names) {
        if (node == null) return;
        String type = node.type();
        if ("simple_identifier".equals(type) || "type_identifier".equals(type)) {
            add(names, node.text());
        }
        for (TsNode child : node.namedChildren()) {
            walk(child, names);
        }
    }

    private static void add(Set<String> names, String name) {
        if (name == null || name.isEmpty() || "<no name provided>".equals(name)) return;
        if (name.equals("_")) return;
        names.add(name);
    }
}
