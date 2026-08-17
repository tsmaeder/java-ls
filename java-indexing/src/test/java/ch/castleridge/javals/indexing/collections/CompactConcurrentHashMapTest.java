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
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

class CompactConcurrentHashMapTest {

    @Test
    void resolvesCollisionsAndResizes() {
        CompactConcurrentHashMap<CollidingKey, Integer> map =
                new CompactConcurrentHashMap<>(2);

        for (int i = 0; i < 1_000; i++) {
            assertNull(map.putIfAbsent(new CollidingKey(i), i));
        }

        assertEquals(1_000, map.size());
        for (int i = 0; i < 1_000; i++) {
            assertEquals(i, map.get(new CollidingKey(i)));
        }
    }

    @Test
    void putIfAbsentRetainsFirstValue() {
        CompactConcurrentHashMap<String, Integer> map =
                new CompactConcurrentHashMap<>();

        assertNull(map.putIfAbsent("key", 1));
        assertEquals(1, map.putIfAbsent("key", 2));
        assertEquals(1, map.get("key"));
        assertEquals(1, map.size());
    }

    @Test
    void computesEachKeyOnceUnderContention() throws Exception {
        CompactConcurrentHashMap<String, Integer> map =
                new CompactConcurrentHashMap<>();
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

    @Test
    void removeDeletesMapping() {
        CompactConcurrentHashMap<String, Integer> map =
                new CompactConcurrentHashMap<>();

        map.putIfAbsent("a", 1);
        assertEquals(1, map.remove("a"));
        assertNull(map.get("a"));
        assertNull(map.remove("a"));
        assertEquals(0, map.size());
        assertNull(map.putIfAbsent("a", 2));
        assertEquals(2, map.get("a"));
    }

    @Test
    void removePreservesCollidingEntries() {
        CompactConcurrentHashMap<CollidingKey, Integer> map =
                new CompactConcurrentHashMap<>(4);

        for (int i = 0; i < 20; i++) {
            assertNull(map.putIfAbsent(new CollidingKey(i), i));
        }

        for (int i = 0; i < 20; i += 2) {
            assertEquals(i, map.remove(new CollidingKey(i)));
        }

        assertEquals(10, map.size());
        for (int i = 0; i < 20; i++) {
            if ((i & 1) == 0) {
                assertNull(map.get(new CollidingKey(i)));
            } else {
                assertEquals(i, map.get(new CollidingKey(i)));
            }
        }
    }

    @Test
    void removeThenInsertReusesTombstonesUnderCollision() {
        CompactConcurrentHashMap<CollidingKey, Integer> map =
                new CompactConcurrentHashMap<>(8);

        for (int i = 0; i < 5; i++) {
            assertNull(map.putIfAbsent(new CollidingKey(i), i));
        }
        assertEquals(0, map.remove(new CollidingKey(0)));
        assertEquals(2, map.remove(new CollidingKey(2)));
        assertNull(map.putIfAbsent(new CollidingKey(100), 100));
        assertNull(map.putIfAbsent(new CollidingKey(101), 101));

        assertEquals(5, map.size());
        assertEquals(1, map.get(new CollidingKey(1)));
        assertEquals(3, map.get(new CollidingKey(3)));
        assertEquals(4, map.get(new CollidingKey(4)));
        assertEquals(100, map.get(new CollidingKey(100)));
        assertEquals(101, map.get(new CollidingKey(101)));
        assertNull(map.get(new CollidingKey(0)));
        assertNull(map.get(new CollidingKey(2)));
    }

    private record CollidingKey(int id) {
        @Override
        public int hashCode() {
            return 7;
        }
    }
}
