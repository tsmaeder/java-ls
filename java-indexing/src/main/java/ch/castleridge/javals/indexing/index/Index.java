package ch.castleridge.javals.indexing.index;

import java.util.*;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Supplier;

import ch.castleridge.javals.indexing.bloom.IdentifierBloomFilter;
import ch.castleridge.javals.indexing.model.ModuleEntry;
import ch.castleridge.javals.indexing.model.TypeEntry;
import ch.castleridge.javals.indexing.model.TypeEntryCodec;

/**
 * Thread-safe in-memory store of indexed declarations.
 *
 * <p>Types are the only top-level keys: fields and methods travel with their
 * owning {@link TypeEntry}. The index is context-free and therefore cheap to
 * build concurrently across scanners.
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
 * <p>{@link TypeEntry}s are stored as compact {@code byte[]} blobs produced
 * by {@link TypeEntryCodec}, not as live object graphs. Each key maps to a
 * {@link List} of encoded blobs. Decoded records are reconstructed on demand
 * through a fixed-size {@link DecodedTypeCache}.
 *
 * <p>Thread-safety is provided by a single {@link ReentrantReadWriteLock}
 * guarding plain {@link HashMap}s rather than {@code ConcurrentHashMap}.
 * Lookups take the shared read lock, so the many concurrent readers on a
 * hot request path (e.g. a parallel find-references sweep, where each
 * candidate compile hammers {@link #getAll}/{@link #listPackage}) proceed
 * in parallel instead of serializing on one monitor. Indexing happens in
 * batches under the exclusive write lock: a scanner builds a per-source
 * temporary index and merges it wholesale via {@link #addAll(Index)}, so
 * each merge takes the lock exactly once and applies the whole batch under
 * it, avoiding the per-key CAS/bin-lock overhead of
 * {@code ConcurrentHashMap}. Encoding happens before the lock (or was
 * already done in the source index); critical sections only move blob
 * references.
 */
public final class Index {

    private final ReentrantReadWriteLock rwLock = new ReentrantReadWriteLock();
    private final DecodedTypeCache decodedCache = new DecodedTypeCache();

    /**
     * Run {@code body} under the shared read lock. Read-only lookups use
     * this so they can run concurrently with one another; only a mutation
     * (write lock) excludes them.
     */
    private <T> T read(Supplier<T> body) {
        rwLock.readLock().lock();
        try {
            return body.get();
        } finally {
            rwLock.readLock().unlock();
        }
    }

    /** JVM name → encoded TypeEntry blobs. */
    private final Map<String, List<byte[]>> byJvmName = new HashMap<>();
    /** Package → encoded TypeEntry blobs. */
    private final Map<String, List<byte[]>> byPackage = new HashMap<>();
    // Modules are addressed by module name, not by JVM binary name, and
    // duplicates across classpath sources are resolved by classpath
    // ordering at lookup time (mirroring the TypeEntry bucket strategy).
    private final Map<String, List<ModuleEntry>> byModuleName = new HashMap<>();
    private final Map<String, IdentifierBloomFilter> bloomByResourceUri = new HashMap<>();
    // Observer list is touched once per merge and rarely mutated; guard it
    // with the same lock and iterate a snapshot taken outside it.
    private final List<Runnable> changedListeners = new ArrayList<>();

    public void addChangedListener(Runnable listener) {
        if (listener == null) return;
        rwLock.writeLock().lock();
        try {
            changedListeners.add(listener);
        } finally {
            rwLock.writeLock().unlock();
        }
    }

    private void notifyChanged() {
        Runnable[] snapshot;
        rwLock.readLock().lock();
        try {
            if (changedListeners.isEmpty()) return;
            snapshot = changedListeners.toArray(new Runnable[0]);
        } finally {
            rwLock.readLock().unlock();
        }
        for (Runnable listener : snapshot) {
            try {
                listener.run();
            } catch (RuntimeException ignored) {
                // listener failures must not break indexing
            }
        }
    }

    /**
     * Register a per-source-file identifier bloom filter keyed by the
     * file's resource URI (e.g. {@code file:///.../Foo.java}).
     * Does not fire change listeners — bloom filters are consulted on demand.
     */
    public void registerBloom(String resourceUri, IdentifierBloomFilter filter) {
        if (resourceUri == null || filter == null) return;
        rwLock.writeLock().lock();
        try {
            registerBloomLocked(resourceUri, filter);
        } finally {
            rwLock.writeLock().unlock();
        }
    }

    /** Caller must hold the write lock. */
    private void registerBloomLocked(String resourceUri, IdentifierBloomFilter filter) {
        if (resourceUri == null || filter == null) return;
        bloomByResourceUri.put(resourceUri, filter);
    }

    /**
     * Snapshot of every registered identifier bloom filter, keyed by
     * resource URI. Returns an immutable copy so callers can iterate it
     * without holding the index lock.
     */
    public Map<String, IdentifierBloomFilter> bloomFilters() {
        return read(() -> Map.copyOf(bloomByResourceUri));
    }

    public void add(TypeEntry entry) {
        if (entry == null) return;
        String jvm = entry.jvmOwnerName();
        if (isSkippedJvmName(jvm)) return;
        // Encode outside the lock: parsing already finished; keep the
        // critical section to blob-pointer moves only.
        byte[] blob = TypeEntryCodec.encode(entry);
        String pkg = entry.packageJvm();
        rwLock.writeLock().lock();
        try {
            appendBucket(byJvmName, jvm, blob);
            appendBucket(byPackage, pkg, blob);
        } finally {
            rwLock.writeLock().unlock();
        }
        notifyChanged();
    }

    /**
     * Merge every entry from {@code other} into this index and fire a
     * single change notification. The entire batch is applied under one
     * write-lock acquisition. TypeEntry blobs are moved by reference (no
     * decode/re-encode). Bloom filters are merged silently.
     */
    public void addAll(Index other) {
        if (other == null || other.isEmpty()) return;
        // Snapshot the source outside our lock (each call takes the
        // source's own lock); then apply the whole batch at once.
        Map<String, List<byte[]>> typeBlobsByJvm = other.snapshotTypeBlobsByJvmName();
        Map<String, List<byte[]>> typeBlobsByPackage = other.snapshotTypeBlobsByPackage();
        Collection<ModuleEntry> modules = other.allModules();
        Map<String, IdentifierBloomFilter> blooms = other.bloomFilters();

        rwLock.writeLock().lock();
        try {
            mergeTypeBlobMap(byJvmName, typeBlobsByJvm);
            mergeTypeBlobMap(byPackage, typeBlobsByPackage);
            for (ModuleEntry m : modules) addModuleLocked(m);
            blooms.forEach(this::registerBloomLocked);
        } finally {
            rwLock.writeLock().unlock();
        }
        notifyChanged();
    }

    /** Shallow copy of encoded TypeEntry buckets keyed by JVM name. */
    private Map<String, List<byte[]>> snapshotTypeBlobsByJvmName() {
        return read(() -> new HashMap<>(byJvmName));
    }

    /** Shallow copy of encoded TypeEntry buckets keyed by package. */
    private Map<String, List<byte[]>> snapshotTypeBlobsByPackage() {
        return read(() -> new HashMap<>(byPackage));
    }

    /** Caller must hold the write lock. Appends each incoming blob under its key. */
    private static void mergeTypeBlobMap(Map<String, List<byte[]>> target, Map<String, List<byte[]>> incoming) {
        for (Map.Entry<String, List<byte[]>> e : incoming.entrySet()) {
            target.computeIfAbsent(e.getKey(), k -> new ArrayList<>()).addAll(e.getValue());
        }
    }

    public boolean isEmpty() {
        return read(() -> byJvmName.isEmpty()
                && byModuleName.isEmpty()
                && bloomByResourceUri.isEmpty());
    }

    /** Caller must hold the write lock. Appends {@code value} to the bucket for {@code key}. */
    private static <T> void appendBucket(Map<String, List<T>> map, String key, T value) {
        map.computeIfAbsent(key, k -> new ArrayList<>()).add(value);
    }

    /**
     * Return every {@link TypeEntry} indexed under {@code jvmName}. Order
     * is observation-order (scanner thread ordering) and therefore
     * non-deterministic; consumers that care must impose their own
     * ordering via {@link TypeEntry#sourceUri()}.
     */
    public List<TypeEntry> getAll(String jvmName) {
        return read(() -> toList(byJvmName.get(jvmName)));
    }

    /**
     * Return an arbitrary {@link TypeEntry} for {@code jvmName}, or
     * {@code null} if none exists. Which candidate is returned when
     * duplicates exist is unspecified; prefer {@link #getAll(String)} +
     * explicit classpath ordering for deterministic behaviour.
     */
    public TypeEntry get(String jvmName) {
        return read(() -> {
            List<byte[]> bucket = byJvmName.get(jvmName);
            if (bucket == null || bucket.isEmpty()) return null;
            return decodedCache.get(bucket.get(0));
        });
    }

    public boolean contains(String jvmName) {
        return read(() -> {
            List<byte[]> bucket = byJvmName.get(jvmName);
            return bucket != null && !bucket.isEmpty();
        });
    }

    /**
     * Return every {@link TypeEntry} whose declaring package is
     * {@code packageJvm}. May contain duplicates (same JVM name from
     * multiple sources) - consumers apply their own deduplication.
     */
    public List<TypeEntry> listPackage(String packageJvm, boolean recurse) {
        return read(() -> {
            if (recurse) {
                List<TypeEntry> entries = new ArrayList<>();
                String prefix = packageJvm+'/';
                for (Map.Entry<String, List<byte[]>> entry : byPackage.entrySet()) {
                    String packageName = entry.getKey();
                    if (packageName.startsWith(prefix)) {
                        addBucketTo(entry.getValue(), entries);
                    }
                }
                return entries;
            } else {
                return toList(byPackage.get(packageJvm == null ? "" : packageJvm));
            }
        });
    }

    /**
     * Search every indexed top-level {@link TypeEntry} for a simple name
     * starting with {@code prefix}, stopping once {@code limit} matches
     * are found ({@code limit <= 0} means no cap). Nested/inner classes
     * (JVM names containing {@code $}) are skipped - callers that need
     * this (e.g. unimported-type completion) only handle importable
     * top-level types.
     *
     * <p>This is a linear scan over {@link #byJvmName}'s keys rather
     * than a dedicated sorted-by-simple-name structure: for realistic
     * index sizes the scan costs low-single-digit milliseconds, which is
     * dwarfed by the cost of the surrounding request (e.g. a javac
     * compile for completion). Matching keys are decoded on demand so
     * the rest of the index stays as blobs.
     */
    public List<TypeEntry> searchTypesBySimpleNamePrefix(String prefix, int limit) {
        if (prefix == null) return List.of();
        return read(() -> {
            List<TypeEntry> out = new ArrayList<>();
            for (Map.Entry<String, List<byte[]>> mapEntry : byJvmName.entrySet()) {
                String jvmOwnerName = mapEntry.getKey();
                if (!simpleNameMatchesPrefix(jvmOwnerName, prefix)) continue;
                for (byte[] blob : mapEntry.getValue()) {
                    out.add(decodedCache.get(blob));
                    if (limit > 0 && out.size() >= limit) return out;
                }
            }
            return out;
        });
    }

    /**
     * True when {@code jvmOwnerName} is a top-level type (no {@code $})
     * whose simple name starts with {@code prefix}.
     */
    private static boolean simpleNameMatchesPrefix(String jvmOwnerName, String prefix) {
        if (jvmOwnerName == null || jvmOwnerName.indexOf('$') >= 0) return false;
        int slash = jvmOwnerName.lastIndexOf('/');
        String simpleName = slash < 0 ? jvmOwnerName : jvmOwnerName.substring(slash + 1);
        return simpleName.startsWith(prefix);
    }

    /** Every {@link TypeEntry} currently stored, including duplicates. */
    public Collection<TypeEntry> all() {
        return read(() -> {
            List<TypeEntry> out = new ArrayList<>();
            for (List<byte[]> bucket : byJvmName.values()) {
                addBucketTo(bucket, out);
            }
            return Collections.unmodifiableCollection(out);
        });
    }

    /** Number of distinct JVM binary names indexed (ignoring duplicates). */
    public int size() {
        return read(byJvmName::size);
    }

    /** Number of indexed entries in total, counting duplicates. */
    public int entryCount() {
        return read(() -> {
            int n = 0;
            for (List<byte[]> bucket : byJvmName.values()) n += bucket.size();
            return n;
        });
    }

    private static <T> List<T> toImmutableList(List<T> bucket) {
        if (bucket == null || bucket.isEmpty()) return List.of();
        return List.copyOf(bucket);
    }

    private static <T> void addAllTo(List<T> bucket, List<T> out) {
        if (bucket != null) out.addAll(bucket);
    }

    private List<TypeEntry> toList(List<byte[]> bucket) {
        if (bucket == null || bucket.isEmpty()) return List.of();
        TypeEntry[] decoded = new TypeEntry[bucket.size()];
        for (int i = 0; i < bucket.size(); i++) {
            decoded[i] = decodedCache.get(bucket.get(i));
        }
        return List.of(decoded);
    }

    private void addBucketTo(List<byte[]> bucket, List<TypeEntry> out) {
        if (bucket == null) return;
        for (byte[] blob : bucket) {
            out.add(decodedCache.get(blob));
        }
    }

    /**
     * Add a {@link ModuleEntry} keyed by its module name. Multiple
     * entries for the same name (jrt + classpath jar with module-info,
     * shadowing module-path) are kept and disambiguated by classpath
     * order at lookup time, identical to the {@link TypeEntry} bucket
     * strategy.
     */
    public void addModule(ModuleEntry module) {
        if (module == null) return;
        rwLock.writeLock().lock();
        try {
            addModuleLocked(module);
        } finally {
            rwLock.writeLock().unlock();
        }
        notifyChanged();
    }

    /** Caller must hold the write lock. */
    private boolean addModuleLocked(ModuleEntry module) {
        if (module == null) return false;
        String name = module.name();
        appendBucket(byModuleName, name, module);
        return true;
    }

    /**
     * Return every {@link ModuleEntry} indexed under {@code moduleName}.
     * Order is observation-order; consumers impose deterministic
     * picking via {@link ModuleEntry#sourceUri()}.
     */
    public List<ModuleEntry> getAllModules(String moduleName) {
        return read(() -> toImmutableList(byModuleName.get(moduleName)));
    }

    /** Convenience: pick an arbitrary {@link ModuleEntry} for the name, or null. */
    public ModuleEntry getModule(String moduleName) {
        return read(() -> {
            List<ModuleEntry> bucket = byModuleName.get(moduleName);
            if (bucket == null || bucket.isEmpty()) return null;
            return bucket.get(0);
        });
    }

    /** Every {@link ModuleEntry} currently stored, including duplicates. */
    public Collection<ModuleEntry> allModules() {
        return read(() -> {
            List<ModuleEntry> out = new ArrayList<>();
            for (List<ModuleEntry> bucket : byModuleName.values()) {
                addAllTo(bucket, out);
            }
            return Collections.unmodifiableCollection(out);
        });
    }

    /** Distinct number of module names indexed (ignoring duplicates). */
    public int moduleCount() {
        return read(byModuleName::size);
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
     * that walkers should silently drop entirely. {@code module-info}
     * files are <em>not</em> in this list: the bytecode indexer routes
     * them through a separate {@link ModuleEntry} path and they must
     * therefore reach it via the normal walker pipeline.
     */
    public static boolean isSkippedFileName(String fileName) {
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
    public static boolean isModuleInfoFileName(String fileName) {
        if (fileName == null) return false;
        return fileName.equals("module-info.java")
                || fileName.equals("module-info.class");
    }
}
