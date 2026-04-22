package ch.castleridge.javals.indexing.intern;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Thread-safe canonicalising table for high-cardinality strings emitted by
 * the indexer. The ASM-based bytecode indexer creates a fresh {@link String}
 * for every JVM binary name, member name and descriptor fragment it parses;
 * the same names recur across tens of thousands of classes. A shared
 * {@link ConcurrentHashMap} collapses those copies to a single canonical
 * instance.
 *
 * <p>Cheaper than {@link String#intern()}: the JDK's string intern table
 * lives in native code and contends with class loading. A pure-Java map
 * scales linearly with cores and can be pre-seeded with hot names.
 */
public final class Interner {

    private static final ConcurrentMap<String, String> TABLE = new ConcurrentHashMap<>(1 << 14);

    static {
        // Pre-seed common JVM names, method names and signatures so the
        // dominant first-write wave stays on the fast path.
        for (String s : new String[] {
                "java/lang/Object",
                "java/lang/String",
                "java/lang/Class",
                "java/lang/Throwable",
                "java/lang/Exception",
                "java/lang/RuntimeException",
                "java/lang/Error",
                "java/lang/Integer",
                "java/lang/Long",
                "java/lang/Short",
                "java/lang/Byte",
                "java/lang/Boolean",
                "java/lang/Character",
                "java/lang/Float",
                "java/lang/Double",
                "java/lang/Void",
                "java/lang/CharSequence",
                "java/lang/Number",
                "java/lang/Comparable",
                "java/lang/Enum",
                "java/lang/Iterable",
                "java/util/List",
                "java/util/Map",
                "java/util/Set",
                "java/util/Collection",
                "java/util/Optional",
                "java/util/function/Function",
                "java/util/function/Supplier",
                "java/util/function/Consumer",
                "java/util/function/Predicate",
                "<init>",
                "<clinit>",
                "equals",
                "hashCode",
                "toString",
                "clone",
                "finalize",
                "wait",
                "notify",
                "notifyAll",
                "getClass",
                "java/lang",
                "java/util",
                "java/io",
                ""
        }) {
            TABLE.put(s, s);
        }
    }

    private Interner() {}

    /**
     * Return a canonical, shared instance of {@code s}. {@code null} in →
     * {@code null} out.
     */
    public static String intern(String s) {
        if (s == null) return null;
        String existing = TABLE.get(s);
        if (existing != null) return existing;
        // putIfAbsent to win exactly one race; return the winner.
        String prior = TABLE.putIfAbsent(s, s);
        return prior == null ? s : prior;
    }
}
