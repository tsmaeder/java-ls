package ch.castleridge.javals.indexing.index;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import ch.castleridge.javals.indexing.model.TypeEntry;

/**
 * Thread-safe in-memory store of indexed declarations.
 *
 * <p>Types are the only top-level keys: fields and methods travel with their
 * owning {@link TypeEntry} (see the record's fields/methods lists). The index
 * is context-free and therefore cheap to build concurrently across scanners.
 *
 * <p>The same JVM binary name is allowed to occur multiple times - a single
 * type is frequently present in several classpath entries (JDK + shaded
 * copies, multiple dependency versions, etc.). The index keeps <em>all</em>
 * candidates; it intentionally has no notion of classpath priority. It is
 * the responsibility of a consumer (typically the file manager of a compile
 * session) to pick a winner by its own classpath ordering, using the
 * {@link TypeEntry#sourceUri()} stamped on every entry.
 *
 * <p>Entries representing {@code module-info} or {@code package-info} are
 * filtered at {@link #add(TypeEntry)} time so callers don't have to special
 * case them.
 *
 * <p>Bucket storage is optimised for the overwhelmingly common single-entry
 * case: the map value is {@code Object}, carrying either a bare
 * {@link TypeEntry} (1 entry) or a {@code TypeEntry[]} (2+). This avoids
 * ~430k {@link java.util.concurrent.CopyOnWriteArrayList} shells for the
 * trino workload and is updated atomically via
 * {@link ConcurrentMap#compute}.
 */
public final class Index {

    private final ConcurrentMap<String, Object> byJvmName = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Object> byPackage = new ConcurrentHashMap<>();

    public void add(TypeEntry entry) {
        if (entry == null) return;
        String jvm = entry.jvmOwnerName();
        if (isSkippedJvmName(jvm)) return;

        byJvmName.compute(jvm, (k, prior) -> appendBucket(prior, entry));
        String pkg = entry.packageJvm();
        byPackage.compute(pkg, (k, prior) -> appendBucket(prior, entry));
    }

    private static Object appendBucket(Object prior, TypeEntry entry) {
        if (prior == null) return entry;
        if (prior instanceof TypeEntry only) {
            return new TypeEntry[] { only, entry };
        }
        TypeEntry[] arr = (TypeEntry[]) prior;
        TypeEntry[] grown = new TypeEntry[arr.length + 1];
        System.arraycopy(arr, 0, grown, 0, arr.length);
        grown[arr.length] = entry;
        return grown;
    }

    /**
     * Return every {@link TypeEntry} indexed under {@code jvmName}. Order
     * is observation-order (scanner thread ordering) and therefore
     * non-deterministic; consumers that care must impose their own
     * ordering via {@link TypeEntry#sourceUri()}.
     */
    public List<TypeEntry> getAll(String jvmName) {
        return toList(byJvmName.get(jvmName));
    }

    /**
     * Return an arbitrary {@link TypeEntry} for {@code jvmName}, or
     * {@code null} if none exists. Which candidate is returned when
     * duplicates exist is unspecified; prefer {@link #getAll(String)} +
     * explicit classpath ordering for deterministic behaviour.
     */
    public TypeEntry get(String jvmName) {
        Object bucket = byJvmName.get(jvmName);
        if (bucket == null) return null;
        if (bucket instanceof TypeEntry only) return only;
        TypeEntry[] arr = (TypeEntry[]) bucket;
        return arr.length == 0 ? null : arr[0];
    }

    public boolean contains(String jvmName) {
        Object bucket = byJvmName.get(jvmName);
        if (bucket == null) return false;
        if (bucket instanceof TypeEntry) return true;
        TypeEntry[] arr = (TypeEntry[]) bucket;
        return arr.length > 0;
    }

    /**
     * Return every {@link TypeEntry} whose declaring package is
     * {@code packageJvm}. May contain duplicates (same JVM name from
     * multiple sources) - consumers apply their own deduplication.
     */
    public List<TypeEntry> listPackage(String packageJvm) {
        return toList(byPackage.get(packageJvm == null ? "" : packageJvm));
    }

    /** Every {@link TypeEntry} currently stored, including duplicates. */
    public Collection<TypeEntry> all() {
        List<TypeEntry> out = new ArrayList<>();
        for (Object bucket : byJvmName.values()) {
            addBucketTo(bucket, out);
        }
        return Collections.unmodifiableCollection(out);
    }

    /** Number of distinct JVM binary names indexed (ignoring duplicates). */
    public int size() {
        return byJvmName.size();
    }

    /** Number of indexed entries in total, counting duplicates. */
    public int entryCount() {
        int n = 0;
        for (Object bucket : byJvmName.values()) {
            if (bucket instanceof TypeEntry) n += 1;
            else if (bucket != null) n += ((TypeEntry[]) bucket).length;
        }
        return n;
    }

    private static List<TypeEntry> toList(Object bucket) {
        if (bucket == null) return List.of();
        if (bucket instanceof TypeEntry only) return List.of(only);
        TypeEntry[] arr = (TypeEntry[]) bucket;
        return arr.length == 0 ? List.of() : List.of(arr);
    }

    private static void addBucketTo(Object bucket, List<TypeEntry> out) {
        if (bucket == null) return;
        if (bucket instanceof TypeEntry only) {
            out.add(only);
            return;
        }
        TypeEntry[] arr = (TypeEntry[]) bucket;
        for (TypeEntry e : arr) out.add(e);
    }

    /**
     * Returns true for simple names that should never be indexed:
     * {@code module-info} and {@code package-info}. Accepts either a plain
     * simple name or a full JVM binary name (slashes allowed).
     */
    public static boolean isSkippedJvmName(String jvmName) {
        if (jvmName == null) return true;
        String simple = jvmName;
        int slash = simple.lastIndexOf('/');
        if (slash >= 0) simple = simple.substring(slash + 1);
        return simple.equals("module-info") || simple.equals("package-info");
    }

    /**
     * Returns true for file names (e.g. {@code Foo.java}, {@code Bar.class})
     * that map onto skipped declarations.
     */
    public static boolean isSkippedFileName(String fileName) {
        if (fileName == null) return true;
        return fileName.equals("module-info.java")
                || fileName.equals("module-info.class")
                || fileName.equals("package-info.java")
                || fileName.equals("package-info.class");
    }
}
