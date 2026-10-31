/**
 * Copyright 2026 by Castle Ridge Software GmbH
 *
 * Author: Thomas Mäder, Castle Ridge Software
 *
 */
package ch.castleridge.javals.indexing.source.kotlin;

import java.util.HashSet;
import java.util.Set;

import io.github.treesitter.jtreesitter.Node;

import ch.castleridge.javals.indexing.bloom.IdentifierBloomFilter;

/**
 * Collects simple identifier names from a Kotlin CST for bloom filters.
 */
final class KotlinIdentifierCollector {

    private KotlinIdentifierCollector() {}

    static IdentifierBloomFilter collectAndBuild(Node root) {
        Set<String> names = new HashSet<>();
        walk(root, names);
        return IdentifierBloomFilter.create(names);
    }

    private static void walk(Node node, Set<String> names) {
        if (node == null) return;
        String type = node.getType();
        if ("simple_identifier".equals(type) || "type_identifier".equals(type)) {
            add(names, node.getText());
        }
        for (Node child : node.getNamedChildren()) {
            walk(child, names);
        }
    }

    private static void add(Set<String> names, String name) {
        if (name == null || name.isEmpty() || "<no name provided>".equals(name)) return;
        if (name.equals("_")) return;
        names.add(name);
    }
}
