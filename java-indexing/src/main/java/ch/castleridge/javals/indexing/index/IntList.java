package ch.castleridge.javals.indexing.index;

import java.util.Arrays;

/**
 * Compact growable list of {@code int}s with doubling growth.
 *
 * <p>Used by {@link InMemoryIndex} secondary indexes so type IDs stay
 * unboxed. Most buckets are tiny (often a single entry), so the default
 * capacity is small; {@link #ensureCapacity(int)} is for bulk merges.
 */
final class IntList {

    private int[] data;
    private int size;

    IntList() {
        this(2);
    }

    IntList(int capacity) {
        this.data = new int[Math.max(1, capacity)];
    }

    int size() {
        return size;
    }

    boolean isEmpty() {
        return size == 0;
    }

    int get(int index) {
        if (index < 0 || index >= size) {
            throw new IndexOutOfBoundsException(index);
        }
        return data[index];
    }

    void add(int value) {
        ensureCapacity(size + 1);
        data[size++] = value;
    }

    /**
     * Append every value from {@code other} after adding {@code offset}
     * (used when remapping source-index IDs into a target index).
     */
    void addAllRemapped(IntList other, int offset) {
        if (other == null || other.size == 0) return;
        ensureCapacity(size + other.size);
        for (int i = 0; i < other.size; i++) {
            data[size++] = other.data[i] + offset;
        }
    }

    void ensureCapacity(int minCapacity) {
        if (minCapacity <= data.length) return;
        int newCap = data.length;
        while (newCap < minCapacity) {
            int doubled = newCap << 1;
            if (doubled < 0) {
                newCap = minCapacity;
                break;
            }
            newCap = Math.max(doubled, minCapacity);
        }
        data = Arrays.copyOf(data, newCap);
    }

    /** Independent copy; mutations to either list do not affect the other. */
    IntList copy() {
        IntList copy = new IntList(Math.max(1, size));
        System.arraycopy(data, 0, copy.data, 0, size);
        copy.size = size;
        return copy;
    }
}
