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
package ch.castleridge.javals.indexing.collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

class CompactConcurrentIntHashMapTest {

    @Test
    void resolvesCollisionsAndResizes() {
        CompactConcurrentIntHashMap<CollidingKey> map =
                new CompactConcurrentIntHashMap<>(2);

        for (int i = 0; i < 1_000; i++) {
            assertTrue(map.putIfAbsent(new CollidingKey(i), i));
        }

        assertEquals(1_000, map.size());
        for (int i = 0; i < 1_000; i++) {
            assertEquals(i, map.getOrDefault(new CollidingKey(i), -1));
        }
    }

    @Test
    void putIfAbsentRetainsFirstValue() {
        CompactConcurrentIntHashMap<String> map =
                new CompactConcurrentIntHashMap<>();

        assertTrue(map.putIfAbsent("key", 1));
        assertFalse(map.putIfAbsent("key", 2));
        assertEquals(1, map.getOrDefault("key", -1));
        assertEquals(1, map.size());
    }

    @Test
    void computesEachKeyOnceUnderContention() throws Exception {
        CompactConcurrentIntHashMap<String> map =
                new CompactConcurrentIntHashMap<>();
        AtomicInteger calls = new AtomicInteger();
        ExecutorService executor = Executors.newFixedThreadPool(8);
        try {
            List<Callable<Integer>> tasks = new ArrayList<>();
            for (int i = 0; i < 100; i++) {
                tasks.add(() -> map.computeIfAbsent("shared", key -> calls.incrementAndGet()));
            }
            List<Future<Integer>> results = executor.invokeAll(tasks);

            for (Future<Integer> result : results) {
                assertEquals(1, result.get());
            }
            assertEquals(1, calls.get());
            assertEquals(1, map.size());
        } finally {
            executor.shutdownNow();
        }
    }

    private record CollidingKey(int id) {
        @Override
        public int hashCode() {
            return 7;
        }
    }
}
