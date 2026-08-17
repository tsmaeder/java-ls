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
 * and values are not supported because a null key marks an unused slot.
 */
public final class CompactConcurrentHashMap<K, V> {

    private static final int DEFAULT_CAPACITY = 16;
    private static final int MAXIMUM_CAPACITY = 1 << 30;
    private static final float LOAD_FACTOR = 0.65f;

    private final StampedLock lock = new StampedLock();

    private Object[] keys;
    private Object[] values;
    private int size;
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
            int slot = findSlot(key, keys);
            if (keys[slot] != null) {
                return valueAt(slot, values);
            }
            if (size == resizeThreshold) {
                resize();
                slot = findSlot(key, keys);
            }
            keys[slot] = key;
            values[slot] = value;
            size++;
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
            int slot = findSlot(key, keys);
            if (keys[slot] != null) {
                return valueAt(slot, values);
            }
            if (size == resizeThreshold) {
                resize();
                slot = findSlot(key, keys);
            }
            V value = Objects.requireNonNull(factory.apply(key), "factory result");
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
        Object[] oldValues = values;
        Object[] newKeys = new Object[oldCapacity << 1];
        Object[] newValues = new Object[newKeys.length];

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

    private static <K, V> V getFromTables(K key, Object[] keys, Object[] values) {
        int slot = findSlot(key, keys);
        return keys[slot] == null ? null : valueAt(slot, values);
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
