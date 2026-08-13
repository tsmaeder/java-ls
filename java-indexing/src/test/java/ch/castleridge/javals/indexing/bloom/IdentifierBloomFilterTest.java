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
package ch.castleridge.javals.indexing.bloom;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IdentifierBloomFilterTest {

    @Test
    void neverFalseNegativeOnAddedNames() {
        List<String> names = List.of("foo", "bar", "Baz", "longIdentifierName");
        IdentifierBloomFilter filter = IdentifierBloomFilter.create(names);
        for (String name : names) {
            assertTrue(filter.mightContain(name), "must contain added name: " + name);
        }
    }

    @Test
    void absentNameUsuallyNotPresent() {
        Set<String> names = Set.of(
                "alpha", "beta", "gamma", "delta", "epsilon", "zeta", "eta", "theta");
        IdentifierBloomFilter filter = IdentifierBloomFilter.create(names);
        int falsePositives = 0;
        List<String> probes = List.of(
                "absent1", "absent2", "absent3", "notInSet", "missing", "zzz", "qqq");
        for (String probe : probes) {
            if (filter.mightContain(probe)) falsePositives++;
        }
        assertTrue(falsePositives <= 2, "expected low false-positive rate, got " + falsePositives);
    }

    @Test
    void emptyInputProducesEmptyFilter() {
        IdentifierBloomFilter filter = IdentifierBloomFilter.create(List.of());
        assertFalse(filter.mightContain("anything"));
    }
}
