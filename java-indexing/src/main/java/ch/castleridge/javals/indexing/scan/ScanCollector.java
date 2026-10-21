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
package ch.castleridge.javals.indexing.scan;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Mutable, thread-safe accumulator for scan statistics. Walkers record
 * per-resource counts/sizes when they already know them; {@link Scanner}
 * snapshots the result after {@link Scanner#scanAll}.
 */
public final class ScanCollector {

    private final ConcurrentHashMap<String, AtomicInteger> fileCounts = new ConcurrentHashMap<>();
    private final AtomicLong classFileBytes = new AtomicLong();

    /**
     * Records one walked file under {@code extension} (including the leading
     * dot, lower-cased). No-op when {@code extension} is null or blank.
     */
    public void addFile(String extension) {
        if (extension == null || extension.isBlank()) return;
        fileCounts.computeIfAbsent(extension, k -> new AtomicInteger()).incrementAndGet();
    }

    public void addClassFileBytes(long n) {
        if (n >= 0) {
            classFileBytes.addAndGet(n);
        }
    }

    public ScanStats snapshot() {
        Map<String, Integer> counts = new HashMap<>();
        for (Map.Entry<String, AtomicInteger> e : fileCounts.entrySet()) {
            counts.put(e.getKey(), e.getValue().get());
        }
        return new ScanStats(counts, classFileBytes.get());
    }
}
