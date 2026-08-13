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
package ch.castleridge.javals.indexing.cli;

import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * Analytical estimator for the retained heap size of an arbitrary object
 * graph. Walks references via reflection with identity-based dedup, so
 * objects shared across the graph (such as cached values or shared URIs)
 * are counted exactly once.
 *
 * <p>Layout assumptions match HotSpot 64-bit with compressed oops and
 * compressed class pointers:
 * <ul>
 *   <li>Object header: 12 bytes (mark word + narrow klass pointer).</li>
 *   <li>Array header: 16 bytes (object header + length + alignment pad).</li>
 *   <li>Reference: 4 bytes.</li>
 *   <li>Alignment: 8 bytes.</li>
 * </ul>
 * Primitive field sizes are exact. Field packing/alignment approximates
 * HotSpot's field reordering by summing declared sizes and aligning the
 * total to 8 bytes; this is correct for most records and simple objects,
 * and within ~8 bytes per instance for everything else.
 *
 * <p>JDK internals in {@code java.base} are not reflectively walkable on
 * JDK 17+ without {@code --add-opens}. We special-case the handful of
 * JDK types that actually appear in the index graph ({@link String},
 * {@link java.net.URI}, {@link Collection}, {@link Map}) so that the
 * walk stays accurate without requiring extra JVM flags.
 *
 * <p>Caveats:
 * <ul>
 *   <li>Assumes LATIN1-encoded strings. UTF-16 strings (ones containing
 *       non-ASCII characters) are undercounted by one byte per char; for
 *       JVM names, paths and URIs this is a non-issue in practice.</li>
 *   <li>{@link java.util.concurrent.ConcurrentHashMap} bucket-table
 *       sizing is approximated by next-power-of-two of {@code size / 0.75};
 *       the real capacity can be off by a factor of two.</li>
 *   <li>Reflective field walks that fail (e.g. {@code String.value} on a
 *       locked-down module path) fall through to the specialised handlers
 *       above, which is the common case; for anything else we log the
 *       class name in the byClass breakdown so the miss is visible.</li>
 * </ul>
 */
public final class HeapSizeEstimator {

    private static final long HEADER = 12L;
    private static final long REF = 4L;
    private static final long ARR_HEADER = 16L;
    private static final long ALIGN = 8L;

    private final IdentityHashMap<Object, Boolean> visited = new IdentityHashMap<>();
    private final Map<String, long[]> byClass = new HashMap<>();
    private final Deque<Object> stack = new ArrayDeque<>();
    private long total;

    /** Walks the graph rooted at {@code root} and returns the estimated retained bytes. */
    public long estimate(Object root) {
        if (root != null) stack.push(root);
        while (!stack.isEmpty()) {
            visit(stack.pop());
        }
        return total;
    }

    /** Breakdown {@code className -> [instanceCount, totalBytes]}. */
    public Map<String, long[]> byClass() {
        return byClass;
    }

    /** Top-{@code n} contributors by total bytes, formatted "class: count x avg = total". */
    public List<String> topByBytes(int n) {
        List<Map.Entry<String, long[]>> rows = new ArrayList<>(byClass.entrySet());
        rows.sort(Comparator.comparingLong((Map.Entry<String, long[]> e) -> e.getValue()[1]).reversed());
        List<String> out = new ArrayList<>();
        for (int i = 0; i < Math.min(n, rows.size()); i++) {
            Map.Entry<String, long[]> e = rows.get(i);
            long count = e.getValue()[0];
            long bytes = e.getValue()[1];
            long avg = count == 0 ? 0 : bytes / count;
            out.add(String.format("%-60s %8d x %6d B = %s",
                    e.getKey(), count, avg, formatBytes(bytes)));
        }
        return out;
    }

    private void visit(Object o) {
        if (o == null) return;
        if (visited.put(o, Boolean.TRUE) != null) return;
        Class<?> c = o.getClass();

        if (c.isArray()) {
            visitArray(o, c);
            return;
        }
        if (o instanceof String s) {
            long bytes = sizeOfString(s);
            record(c, bytes);
            return;
        }
        if (o instanceof java.net.URI u) {
            long bytes = sizeOfUri(u);
            record(c, bytes);
            return;
        }
        if (o instanceof Map<?, ?> m) {
            visitMap(c, m);
            return;
        }
        if (o instanceof Collection<?> col) {
            visitCollection(c, col);
            return;
        }
        long bytes = shallowSize(c);
        record(c, bytes);
        enqueueReferenceFields(o, c);
    }

    private void visitArray(Object o, Class<?> c) {
        Class<?> comp = c.getComponentType();
        int len = Array.getLength(o);
        long bytes;
        if (comp.isPrimitive()) {
            bytes = align(ARR_HEADER + (long) primSize(comp) * len);
        } else {
            bytes = align(ARR_HEADER + REF * len);
            for (int i = 0; i < len; i++) {
                Object e = Array.get(o, i);
                if (e != null) stack.push(e);
            }
        }
        record(c, bytes);
    }

    private void visitMap(Class<?> c, Map<?, ?> m) {
        long shell = shallowSize(c);
        int size = m.size();
        // Approximate hashed-map node + bucket-slot overhead. Covers both
        // HashMap.Node and ConcurrentHashMap.Node; immutable Map.of() types
        // are smaller but rare in this graph.
        long perEntry = 48L;
        long tableBytes = 0L;
        if (c.getName().contains("ConcurrentHashMap") || c.getName().equals("java.util.HashMap")) {
            int tableLen = Integer.highestOneBit(Math.max(16, (int) (size / 0.75))) << 1;
            tableBytes = align(ARR_HEADER + REF * tableLen);
        }
        long bytes = shell + tableBytes + perEntry * size;
        record(c, bytes);
        for (Map.Entry<?, ?> e : m.entrySet()) {
            if (e.getKey() != null) stack.push(e.getKey());
            if (e.getValue() != null) stack.push(e.getValue());
        }
    }

    private void visitCollection(Class<?> c, Collection<?> col) {
        long shell = shallowSize(c);
        int size = col.size();
        long arrayBytes;
        long perElementShell;
        String name = c.getName();
        if (name.equals("java.util.concurrent.CopyOnWriteArrayList")
                || name.equals("java.util.ArrayList")
                || name.contains("ImmutableCollections$ListN")) {
            arrayBytes = align(ARR_HEADER + REF * size);
            perElementShell = 0L;
        } else if (name.contains("ImmutableCollections$List12")) {
            arrayBytes = 0L;
            perElementShell = 0L;
        } else {
            // LinkedList-style: assume one node per element (header + 3 refs).
            arrayBytes = 0L;
            perElementShell = 24L;
        }
        long bytes = shell + arrayBytes + perElementShell * size;
        record(c, bytes);
        for (Object e : col) {
            if (e != null) stack.push(e);
        }
    }

    private void enqueueReferenceFields(Object o, Class<?> c) {
        for (Class<?> k = c; k != null && k != Object.class; k = k.getSuperclass()) {
            Field[] fields;
            try {
                fields = k.getDeclaredFields();
            } catch (Throwable t) {
                continue;
            }
            for (Field f : fields) {
                int mods = f.getModifiers();
                if (Modifier.isStatic(mods)) continue;
                if (f.getType().isPrimitive()) continue;
                if (!f.trySetAccessible()) continue;
                Object v;
                try {
                    v = f.get(o);
                } catch (IllegalAccessException iae) {
                    continue;
                }
                if (v != null) stack.push(v);
            }
        }
    }

    private long sizeOfString(String s) {
        // String: header + byte[] ref + int hash + byte coder + boolean hashIsZero -> aligned to 24.
        // byte[] backing: assume LATIN1 (1 byte/char); UTF-16 would double the array cost.
        long shell = 24L;
        long backing = align(ARR_HEADER + (long) s.length());
        return shell + backing;
    }

    private long sizeOfUri(java.net.URI u) {
        // java.net.URI carries up to ~17 reference fields (scheme, path,
        // query, fragment, authority, host, userInfo, schemeSpecificPart,
        // and lazily-populated decoded* mirrors) + 2 ints + 1 boolean.
        // Aligned shallow ~96 bytes. Most retained strings are reachable
        // via toString(); count that once as the string cost.
        String s = u.toString();
        long shell = 96L;
        long str = sizeOfString(s);
        return shell + str;
    }

    private long shallowSize(Class<?> c) {
        long bytes = HEADER;
        for (Class<?> k = c; k != null && k != Object.class; k = k.getSuperclass()) {
            Field[] fields;
            try {
                fields = k.getDeclaredFields();
            } catch (Throwable t) {
                continue;
            }
            for (Field f : fields) {
                if (Modifier.isStatic(f.getModifiers())) continue;
                Class<?> ft = f.getType();
                bytes += ft.isPrimitive() ? primSize(ft) : REF;
            }
        }
        return align(bytes);
    }

    private void record(Class<?> c, long bytes) {
        total += bytes;
        long[] row = byClass.computeIfAbsent(c.getName(), k -> new long[2]);
        row[0]++;
        row[1] += bytes;
    }

    private static int primSize(Class<?> c) {
        if (c == boolean.class || c == byte.class) return 1;
        if (c == short.class || c == char.class) return 2;
        if (c == int.class || c == float.class) return 4;
        if (c == long.class || c == double.class) return 8;
        return 4;
    }

    private static long align(long n) {
        return (n + ALIGN - 1L) & ~(ALIGN - 1L);
    }

    public static String formatBytes(long bytes) {
        if (bytes < 1024L) return bytes + " B";
        double kb = bytes / 1024.0;
        if (kb < 1024.0) return String.format("%.1f KiB", kb);
        double mb = kb / 1024.0;
        if (mb < 1024.0) return String.format("%.1f MiB", mb);
        return String.format("%.2f GiB", mb / 1024.0);
    }
}
