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
package ch.castleridge.javals.indexing.model;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReferenceArray;

/**
 * Process-wide, thread-safe, append-only int↔String arena used by
 * {@link TypeEntryCodec}. Blobs store string fields as integer ids into this
 * table rather than encoding UTF-8 inline, so common names
 * ({@code java/lang/Object}, {@code <init>}, shared {@code sourceUri}s, …)
 * collapse to a single entry across the whole index.
 *
 * <p>Id {@code 0} is reserved for {@code null}.
 */
public final class StringTable {

    private static final int CHUNK_BITS = 12; // 4096 strings per chunk
    private static final int CHUNK_SIZE = 1 << CHUNK_BITS;
    private static final int CHUNK_MASK = CHUNK_SIZE - 1;
    private static final int MAX_CHUNKS = 1 << 14; // up to ~67M strings

    private static final ConcurrentMap<String, Integer> TO_ID = new ConcurrentHashMap<>(1 << 14);
    private static final AtomicReferenceArray<String[]> CHUNKS =
            new AtomicReferenceArray<>(MAX_CHUNKS);
    /** Next id to allocate; starts at 1 because 0 means null. */
    private static final AtomicInteger NEXT_ID = new AtomicInteger(1);

    static {
        // Pre-allocate the first chunk so the common first-write path
        // never races on chunk creation.
        CHUNKS.set(0, new String[CHUNK_SIZE]);
    }

    private StringTable() {}

    /**
     * Return a stable id for {@code s}. {@code null} maps to {@code 0}.
     * Concurrent callers racing on the same string all observe the same id.
     */
    public static int intern(String s) {
        if (s == null) return 0;
        Integer existing = TO_ID.get(s);
        if (existing != null) return existing;
        int id = NEXT_ID.getAndIncrement();
        Integer prior = TO_ID.putIfAbsent(s, id);
        if (prior != null) {
            // Lost the race; the allocated id is unused (append-only, so
            // we just leave a hole in the reverse table).
            return prior;
        }
        store(id, s);
        return id;
    }

    /** Reverse lookup; id {@code 0} (and any unused hole) returns {@code null}. */
    public static String get(int id) {
        if (id <= 0) return null;
        int chunkIndex = id >>> CHUNK_BITS;
        String[] chunk = CHUNKS.get(chunkIndex);
        if (chunk == null) return null;
        return chunk[id & CHUNK_MASK];
    }

    private static void store(int id, String value) {
        int chunkIndex = id >>> CHUNK_BITS;
        String[] chunk = CHUNKS.get(chunkIndex);
        if (chunk == null) {
            String[] created = new String[CHUNK_SIZE];
            if (!CHUNKS.compareAndSet(chunkIndex, null, created)) {
                chunk = CHUNKS.get(chunkIndex);
            } else {
                chunk = created;
            }
        }
        chunk[id & CHUNK_MASK] = value;
    }
}
