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

import java.util.Objects;
import java.util.concurrent.locks.StampedLock;
import java.util.function.Function;

/**
 * A compact concurrent map backed by parallel key and value tables.
 *
 * <p>The map uses open addressing: a key's hash determines its first search
 * position and collisions are resolved by scanning subsequent slots. Null keys
 * and values are not supported; a null key marks an unused slot and deleted
 * entries leave a tombstone so probe sequences stay intact.
 */
public final class CompactConcurrentHashMap<K, V> {

    private static final int DEFAULT_CAPACITY = 16;
    private static final int MAXIMUM_CAPACITY = 1 << 30;
    private static final float LOAD_FACTOR = 0.65f;
    private static final Object TOMBSTONE = new Object();

    private final StampedLock lock = new StampedLock();

    private Object[] keys;
    private Object[] values;
    /** Live mappings. */
    private int size;
    /** Live mappings plus tombstones; drives resize. */
    private int occupied;
    private int resizeThreshold;

    public CompactConcurrentHashMap() {
        this(DEFAULT_CAPACITY);
    }

    public CompactConcurrentHashMap(int initialCapacity) {
        int capacity = tableSizeFor(initialCapacity);
        keys = new Object[capacity];
        values = new Object[capacity];
        resizeThreshold = resizeThreshold(capacity);
    }

    public V get(K key) {
        Objects.requireNonNull(key, "key");

        long stamp = lock.tryOptimisticRead();
        Object[] currentKeys = keys;
        Object[] currentValues = values;
        V result = getFromTables(key, currentKeys, currentValues);
        if (lock.validate(stamp)) {
            return result;
        }

        stamp = lock.readLock();
        try {
            return getFromTables(key, keys, values);
        } finally {
            lock.unlockRead(stamp);
        }
    }

    /**
     * Associates {@code value} with {@code key} unless the key is already
     * present, returning the existing value or {@code null} after insertion.
     */
    public V putIfAbsent(K key, V value) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(value, "value");

        long stamp = lock.writeLock();
        try {
            int slot = findInsertSlot(key, keys);
            Object existing = keys[slot];
            if (isLive(existing)) {
                return valueAt(slot, values);
            }
            insertAt(slot, key, value, existing == TOMBSTONE);
            return null;
        } finally {
            lock.unlockWrite(stamp);
        }
    }

    /**
     * Returns the value for {@code key}, creating it while holding the map's
     * write lock if no value is present.
     */
    public V computeIfAbsent(K key, Function<? super K, ? extends V> factory) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(factory, "factory");

        long stamp = lock.writeLock();
        try {
            int existing = findExistingSlot(key, keys);
            if (existing >= 0) {
                return valueAt(existing, values);
            }
            V value = Objects.requireNonNull(factory.apply(key), "factory result");
            int slot = findInsertSlot(key, keys);
            insertAt(slot, key, value, keys[slot] == TOMBSTONE);
            return value;
        } finally {
            lock.unlockWrite(stamp);
        }
    }

    /**
     * Removes the mapping for {@code key}, returning the previous value or
     * {@code null} if absent.
     */
    public V remove(K key) {
        Objects.requireNonNull(key, "key");

        long stamp = lock.writeLock();
        try {
            int slot = findExistingSlot(key, keys);
            if (slot < 0) {
                return null;
            }
            V prior = valueAt(slot, values);
            keys[slot] = TOMBSTONE;
            values[slot] = null;
            size--;
            return prior;
        } finally {
            lock.unlockWrite(stamp);
        }
    }

    public int size() {
        long stamp = lock.tryOptimisticRead();
        int result = size;
        if (lock.validate(stamp)) {
            return result;
        }

        stamp = lock.readLock();
        try {
            return size;
        } finally {
            lock.unlockRead(stamp);
        }
    }

    private void insertAt(int slot, K key, V value, boolean reusingTombstone) {
        if (!reusingTombstone) {
            if (occupied == resizeThreshold) {
                resize();
                slot = findInsertSlot(key, keys);
                reusingTombstone = keys[slot] == TOMBSTONE;
            }
            if (!reusingTombstone) {
                occupied++;
            }
        }
        keys[slot] = key;
        values[slot] = value;
        size++;
    }

    private void resize() {
        int oldCapacity = keys.length;
        if (oldCapacity == MAXIMUM_CAPACITY) {
            throw new IllegalStateException("Map capacity exhausted");
        }

        Object[] oldKeys = keys;
        Object[] oldValues = values;
        Object[] newKeys = new Object[oldCapacity << 1];
        Object[] newValues = new Object[newKeys.length];

        int live = 0;
        for (int i = 0; i < oldCapacity; i++) {
            Object key = oldKeys[i];
            if (isLive(key)) {
                int slot = findInsertSlot(key, newKeys);
                newKeys[slot] = key;
                newValues[slot] = oldValues[i];
                live++;
            }
        }

        keys = newKeys;
        values = newValues;
        occupied = live;
        resizeThreshold = resizeThreshold(newKeys.length);
    }

    private static boolean isLive(Object key) {
        return key != null && key != TOMBSTONE;
    }

    /**
     * Returns the slot holding {@code key}, or {@code -1} if absent.
     */
    private static int findExistingSlot(Object key, Object[] table) {
        int mask = table.length - 1;
        int slot = spread(key.hashCode()) & mask;
        int start = slot;
        do {
            Object candidate = table[slot];
            if (candidate == null) {
                return -1;
            }
            if (candidate != TOMBSTONE && key.equals(candidate)) {
                return slot;
            }
            slot = (slot + 1) & mask;
        } while (slot != start);
        return -1;
    }

    /**
     * Returns the slot for an existing key, or the first reusable tombstone /
     * empty slot along the probe sequence.
     */
    private static int findInsertSlot(Object key, Object[] table) {
        int mask = table.length - 1;
        int slot = spread(key.hashCode()) & mask;
        int firstTombstone = -1;
        while (true) {
            Object candidate = table[slot];
            if (candidate == null) {
                return firstTombstone >= 0 ? firstTombstone : slot;
            }
            if (candidate == TOMBSTONE) {
                if (firstTombstone < 0) {
                    firstTombstone = slot;
                }
            } else if (key.equals(candidate)) {
                return slot;
            }
            slot = (slot + 1) & mask;
        }
    }

    private static <V> V getFromTables(Object key, Object[] keys, Object[] values) {
        int slot = findExistingSlot(key, keys);
        return slot < 0 ? null : valueAt(slot, values);
    }

    @SuppressWarnings("unchecked")
    private static <V> V valueAt(int slot, Object[] values) {
        return (V) values[slot];
    }

    private static int spread(int hashCode) {
        return hashCode ^ (hashCode >>> 16);
    }

    private static int resizeThreshold(int capacity) {
        return Math.max(1, (int) (capacity * LOAD_FACTOR));
    }

    private static int tableSizeFor(int requestedCapacity) {
        if (requestedCapacity < 0) {
            throw new IllegalArgumentException("initialCapacity must not be negative");
        }
        if (requestedCapacity >= MAXIMUM_CAPACITY) {
            return MAXIMUM_CAPACITY;
        }
        int capacity = 1;
        int required = Math.max(2, requestedCapacity);
        while (capacity < required) {
            capacity <<= 1;
        }
        return capacity;
    }
}
