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
import java.util.function.ToIntFunction;

/**
 * A compact concurrent map from object keys to primitive int values, backed by
 * parallel key and value tables.
 *
 * <p>The map uses open addressing: a key's hash determines its first search
 * position and collisions are resolved by scanning subsequent slots. Null keys
 * are not supported because a null key marks an unused slot.
 */
public final class CompactConcurrentIntHashMap<K> {

    private static final int DEFAULT_CAPACITY = 16;
    private static final int MAXIMUM_CAPACITY = 1 << 30;
    private static final float LOAD_FACTOR = 0.65f;

    private final StampedLock lock = new StampedLock();

    private Object[] keys;
    private int[] values;
    private int size;
    private int resizeThreshold;

    public CompactConcurrentIntHashMap() {
        this(DEFAULT_CAPACITY);
    }

    public CompactConcurrentIntHashMap(int initialCapacity) {
        int capacity = tableSizeFor(initialCapacity);
        keys = new Object[capacity];
        values = new int[capacity];
        resizeThreshold = resizeThreshold(capacity);
    }

    public int getOrDefault(K key, int defaultValue) {
        Objects.requireNonNull(key, "key");

        long stamp = lock.tryOptimisticRead();
        Object[] currentKeys = keys;
        int[] currentValues = values;
        int result = getFromTables(key, currentKeys, currentValues, defaultValue);
        if (lock.validate(stamp)) {
            return result;
        }

        stamp = lock.readLock();
        try {
            return getFromTables(key, keys, values, defaultValue);
        } finally {
            lock.unlockRead(stamp);
        }
    }

    /**
     * Associates {@code value} with {@code key} unless the key is already
     * present.
     *
     * @return {@code true} if the mapping was inserted, {@code false} if the
     *         key was already present
     */
    public boolean putIfAbsent(K key, int value) {
        Objects.requireNonNull(key, "key");

        long stamp = lock.writeLock();
        try {
            int slot = findSlot(key, keys);
            if (keys[slot] != null) {
                return false;
            }
            if (size == resizeThreshold) {
                resize();
                slot = findSlot(key, keys);
            }
            keys[slot] = key;
            values[slot] = value;
            size++;
            return true;
        } finally {
            lock.unlockWrite(stamp);
        }
    }

    /**
     * Returns the value for {@code key}, creating it while holding the map's
     * write lock if no value is present.
     */
    public int computeIfAbsent(K key, ToIntFunction<? super K> factory) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(factory, "factory");

        long stamp = lock.writeLock();
        try {
            int slot = findSlot(key, keys);
            if (keys[slot] != null) {
                return values[slot];
            }
            if (size == resizeThreshold) {
                resize();
                slot = findSlot(key, keys);
            }
            int value = factory.applyAsInt(key);
            keys[slot] = key;
            values[slot] = value;
            size++;
            return value;
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

    private void resize() {
        int oldCapacity = keys.length;
        if (oldCapacity == MAXIMUM_CAPACITY) {
            throw new IllegalStateException("Map capacity exhausted");
        }

        Object[] oldKeys = keys;
        int[] oldValues = values;
        Object[] newKeys = new Object[oldCapacity << 1];
        int[] newValues = new int[newKeys.length];

        for (int i = 0; i < oldCapacity; i++) {
            Object key = oldKeys[i];
            if (key != null) {
                int slot = findSlot(key, newKeys);
                newKeys[slot] = key;
                newValues[slot] = oldValues[i];
            }
        }

        keys = newKeys;
        values = newValues;
        resizeThreshold = resizeThreshold(newKeys.length);
    }

    private static int findSlot(Object key, Object[] table) {
        int mask = table.length - 1;
        int slot = spread(key.hashCode()) & mask;
        while (true) {
            Object candidate = table[slot];
            if (candidate == null || key.equals(candidate)) {
                return slot;
            }
            slot = (slot + 1) & mask;
        }
    }

    private static <K> int getFromTables(
            K key, Object[] keys, int[] values, int defaultValue) {
        int slot = findSlot(key, keys);
        return keys[slot] == null ? defaultValue : values[slot];
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
