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
package ch.castleridge.javals.indexing.source.javac;

import ch.castleridge.javals.indexing.source.javac.JavacSourceIndexer;

import org.junit.jupiter.api.Test;

import ch.castleridge.javals.indexing.bloom.BloomEntry;
import ch.castleridge.javals.indexing.bloom.IdentifierBloomFilter;
import ch.castleridge.javals.indexing.index.Index;
import ch.castleridge.javals.indexing.index.InMemoryIndex;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SourceIndexerBloomTest {

    private static final String RESOURCE_URI = "mem:///Refs.java";
    private static final String SOURCE_URI = "index:///source/";

    @Test
    void registersBloomFilterWithDeclaredAndReferencedIdentifiers() {
        Index index = new InMemoryIndex();
        JavacSourceIndexer.index(RESOURCE_URI, SOURCE_URI, """
                package p;

                import java.util.List;
                class Refs {
                    int count;
                    void use(List<String> items) {
                        count = items.size();
                    }
                }
                """, index);

        IdentifierBloomFilter bloom = findBloom(index, RESOURCE_URI);
        assertNotNull(bloom);
        assertTrue(bloom.mightContain("Refs"));
        assertTrue(bloom.mightContain("count"));
        assertTrue(bloom.mightContain("use"));
        assertTrue(bloom.mightContain("List"));
        assertTrue(bloom.mightContain("items"));
        assertTrue(bloom.mightContain("size"));
        assertFalse(bloom.mightContain("definitelyNotInThisFile"));
    }

    private static IdentifierBloomFilter findBloom(Index index, String resourceUri) {
        for (BloomEntry entry : index.bloomFilters()) {
            if (resourceUri.equals(entry.resourceUri()) || resourceUri.equals(entry.resourcePath())) {
                return entry.filter();
            }
        }
        return null;
    }
}
