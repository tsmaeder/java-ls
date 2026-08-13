package ch.castleridge.javals.indexing.index;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.function.BiPredicate;

import ch.castleridge.javals.indexing.bloom.IdentifierBloomFilter;
import ch.castleridge.javals.indexing.model.ModuleEntry;
import ch.castleridge.javals.indexing.model.TypeEntry;

/**
 * Storage contract for indexed declarations.
 *
 * <p>Types are the only top-level keys: fields and methods travel with their
 * owning {@link TypeEntry}. The index is context-free and therefore cheap to
 * build concurrently across scanners.
 *
 * <p>The same JVM binary name is allowed to occur multiple times - a single
 * type is frequently present in several classpath entries (JDK + shaded
 * copies, multiple dependency versions, etc.). Implementations keep
 * <em>all</em> candidates; they intentionally have no notion of classpath
 * priority. It is the responsibility of a consumer (typically the file
 * manager of a compile session) to pick a winner by its own classpath
 * ordering, using the {@link TypeEntry#sourceUri()} stamped on every entry.
 *
 * <p>Entries representing {@code module-info} or {@code package-info} are
 * filtered at {@link #add(TypeEntry)} time so callers don't have to special
 * case them.
 *
 * <p>Concrete engines (e.g. {@link InMemoryIndex}) own locking, encoding,
 * and persistence details. Callers depend only on this interface so storage
 * backends can be swapped without changing indexers or language-server
 * consumers.
 */
public interface Index {

    void addChangedListener(Runnable listener);

    /**
     * Register a per-source-file identifier bloom filter keyed by the
     * file's resource URI (e.g. {@code file:///.../Foo.java}).
     * Does not fire change listeners — bloom filters are consulted on demand.
     */
    void registerBloom(String resourceUri, IdentifierBloomFilter filter);

    /**
     * Snapshot of every registered identifier bloom filter, keyed by
     * resource URI. Returns an immutable copy so callers can iterate it
     * without holding implementation locks.
     */
    Map<String, IdentifierBloomFilter> bloomFilters();

    void add(TypeEntry entry);

    /**
     * Merge every entry from {@code other} into this index and fire a
     * single change notification. Bloom filters are merged silently.
     */
    void addAll(Index other);

    boolean isEmpty();

    /**
     * Return every {@link TypeEntry} indexed under {@code jvmName}. Order
     * is observation-order (scanner thread ordering) and therefore
     * non-deterministic; consumers that care must impose their own
     * ordering via {@link TypeEntry#sourceUri()}.
     */
    List<TypeEntry> getAll(String jvmName);

    boolean contains(String jvmName);

    /**
     * True when {@code packageJvm} is a known package in this index,
     * including intermediate parents of packages that own types
     * (e.g. {@code java} when only {@code java/lang} has entries).
     * Does not imply the package directly contains a type.
     */
    boolean hasPackage(String packageJvm);

    /**
     * Return every {@link TypeEntry} whose declaring package is
     * {@code packageJvm}. May contain duplicates (same JVM name from
     * multiple sources) - consumers apply their own deduplication.
     */
    List<TypeEntry> listPackage(String packageJvm, boolean recurse);

    /**
     * Search every indexed top-level {@link TypeEntry} for a simple name
     * starting with {@code prefix}, stopping once {@code limit} matches
     * are found ({@code limit <= 0} means no cap). Nested/inner classes
     * (JVM names containing {@code $}) are skipped - callers that need
     * this (e.g. unimported-type completion) only handle importable
     * top-level types.
     */
    List<TypeEntry> searchTypesBySimpleNamePrefix(String prefix, int limit);

    /** Every {@link TypeEntry} currently stored, including duplicates. */
    Collection<TypeEntry> all();

    /**
     * Decode and return entries whose peeked {@code (sourceUri, resourcePath)}
     * satisfy {@code filter}. The predicate sees the blob-prefix strings only —
     * no full {@link TypeEntry} is built for blobs that fail the test.
     * {@code null} filter is treated as match-all (same as {@link #all()}).
     */
    Collection<TypeEntry> all(BiPredicate<String, String> filter);

    /** Number of distinct JVM binary names indexed (ignoring duplicates). */
    int size();

    /** Number of indexed entries in total, counting duplicates. */
    int entryCount();

    /**
     * Add a {@link ModuleEntry} keyed by its module name. Multiple
     * entries for the same name (jrt + classpath jar with module-info,
     * shadowing module-path) are kept and disambiguated by classpath
     * order at lookup time, identical to the {@link TypeEntry} bucket
     * strategy.
     */
    void addModule(ModuleEntry module);

    /**
     * Return every {@link ModuleEntry} indexed under {@code moduleName}.
     * Order is observation-order; consumers impose deterministic
     * picking via {@link ModuleEntry#sourceUri()}.
     */
    List<ModuleEntry> getAllModules(String moduleName);

    /** Convenience: pick an arbitrary {@link ModuleEntry} for the name, or null. */
    ModuleEntry getModule(String moduleName);

    /** Every {@link ModuleEntry} currently stored, including duplicates. */
    Collection<ModuleEntry> allModules();

    /** Distinct number of module names indexed (ignoring duplicates). */
    int moduleCount();

    /**
     * Returns true for simple names that should never be indexed:
     * {@code module-info} and {@code package-info}. Accepts either a plain
     * simple name or a full JVM binary name (slashes allowed).
     */
    static boolean isSkippedJvmName(String jvmName) {
        if (jvmName == null) return true;
        String simple = jvmName;
        int slash = simple.lastIndexOf('/');
        if (slash >= 0) simple = simple.substring(slash + 1);
        return simple.equals("module-info") || simple.equals("package-info");
    }

    /**
     * Returns true for file names (e.g. {@code Foo.java}, {@code Bar.class})
     * that walkers should silently drop entirely. {@code module-info}
     * files are <em>not</em> in this list: the bytecode indexer routes
     * them through a separate {@link ModuleEntry} path and they must
     * therefore reach it via the normal walker pipeline.
     */
    static boolean isSkippedFileName(String fileName) {
        if (fileName == null) return true;
        return fileName.equals("package-info.java")
                || fileName.equals("package-info.class");
    }

    /**
     * Returns true if {@code fileName} is a JVMS module descriptor file
     * (source or compiled). Walkers still pass these files through to
     * the indexer; the indexer routes them to {@link #addModule} rather
     * than treating them as a {@link TypeEntry}.
     */
    static boolean isModuleInfoFileName(String fileName) {
        if (fileName == null) return false;
        return fileName.equals("module-info.java")
                || fileName.equals("module-info.class");
    }
}
