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

import java.util.HashSet;
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
    void similarNamesAreIndependentMembers() {
        List<String> names = List.of("getFoo", "getBar", "setFoo");
        IdentifierBloomFilter filter = IdentifierBloomFilter.create(names);
        for (String name : names) {
            assertTrue(filter.mightContain(name), "must contain added name: " + name);
        }
        assertFalse(filter.mightContain("getBaz"));
        assertFalse(filter.mightContain("setBar"));
    }

    @Test
    void empiricalFalsePositiveRateStaysLow() {
        Set<String> memberSet = new HashSet<>();
        for (int i = 0; i < 200; i++) {
            memberSet.add("member" + i);
        }
        IdentifierBloomFilter filter = IdentifierBloomFilter.create(memberSet);

        int probes = 0;
        int falsePositives = 0;
        for (int i = 0; i < 2000; i++) {
            String probe = "absent" + i;
            probes++;
            if (filter.mightContain(probe)) falsePositives++;
        }
        // Names that share length / prefix / suffix patterns with members.
        for (int i = 0; i < 200; i++) {
            String probe = "membex" + i; // same length as "memberN" for small i
            if (memberSet.contains(probe)) continue;
            probes++;
            if (filter.mightContain(probe)) falsePositives++;
        }
        for (int i = 0; i < 200; i++) {
            String probe = "getValue" + i;
            probes++;
            if (filter.mightContain(probe)) falsePositives++;
        }

        double rate = falsePositives / (double) probes;
        assertTrue(rate < 0.005,
                "empirical FPR " + rate + " (" + falsePositives + "/" + probes
                        + ") should be well under 0.5%");
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
