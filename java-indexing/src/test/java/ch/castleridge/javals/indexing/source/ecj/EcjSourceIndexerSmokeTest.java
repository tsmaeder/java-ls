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
package ch.castleridge.javals.indexing.source.ecj;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.Opcodes;

import ch.castleridge.javals.indexing.IndexTestUtils;
import ch.castleridge.javals.indexing.bloom.BloomEntry;
import ch.castleridge.javals.indexing.bloom.IdentifierBloomFilter;
import ch.castleridge.javals.indexing.index.InMemoryIndex;
import ch.castleridge.javals.indexing.index.Index;
import ch.castleridge.javals.indexing.model.SourceTypeEntry;
import ch.castleridge.javals.indexing.model.TypeDeclKind;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EcjSourceIndexerSmokeTest {
    private static final String RESOURCE_URI = "mem:///Hello.java";
    private static final String SOURCE_URI = "index:///source/";

    @Test
    void indexesSimplePublicClass() {
        Index index = new InMemoryIndex();
        EcjSourceIndexer.index(
                RESOURCE_URI,
                SOURCE_URI,
                """
                        package p;

                        public class Hello {
                            public int value = 1;
                            public void greet() {
                                System.out.println(value);
                            }
                        }
                        """,
                index);

        SourceTypeEntry hello = (SourceTypeEntry) IndexTestUtils.get(index, "p/Hello");
        assertNotNull(hello);
        assertEquals(TypeDeclKind.CLASS, hello.declKind());
        assertTrue((hello.modifiers() & Opcodes.ACC_PUBLIC) != 0);
        assertTrue(java.util.Arrays.stream(hello.fields()).anyMatch(f -> f.name().equals("value")));
        assertTrue(java.util.Arrays.stream(hello.methods()).anyMatch(m -> m.name().equals("greet")));

        IdentifierBloomFilter bloom = null;
        for (BloomEntry entry : index.bloomFilters()) {
            if (RESOURCE_URI.equals(entry.resourceUri()) || RESOURCE_URI.equals(entry.resourcePath())) {
                bloom = entry.filter();
                break;
            }
        }
        assertNotNull(bloom);
        assertTrue(bloom.mightContain("println"));
        assertTrue(bloom.mightContain("value"));
        assertFalse(bloom.mightContain("definitelyNotInThisFile"));
    }
}
