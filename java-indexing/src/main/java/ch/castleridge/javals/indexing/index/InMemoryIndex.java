package ch.castleridge.javals.indexing.index;

import java.util.*;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Supplier;

import ch.castleridge.javals.indexing.bloom.IdentifierBloomFilter;
import ch.castleridge.javals.indexing.model.ModuleEntry;
import ch.castleridge.javals.indexing.model.TypeEntry;
import ch.castleridge.javals.indexing.model.TypeEntryCodec;

/**
 * Thread-safe in-memory {@link Index} implementation.
 *
 * <p>{@link TypeEntry}s are stored as compact {@code byte[]} blobs produced
 * by {@link TypeEntryCodec}, not as live object graphs. Each blob lives once
 * in an append-only store addressed by a dense artificial ID; secondary
 * indexes ({@code byJvmName}, {@code byPackage}) hold those IDs in compact
 * {@link IntList}s. Decoded records are reconstructed on demand through a
 * fixed-size {@link DecodedTypeCache} keyed by ID.
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
 * references and remap IDs.
 */
public final class InMemoryIndex implements Index {

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

    /** Canonical type-entry store; index into this array is the artificial ID. */
    private byte[][] typeBlobs = new byte[16][];
    private int typeCount = 0;

    /** JVM name → type IDs. */
    private final Map<String, IntList> byJvmName = new HashMap<>();
    /** Package → type IDs (leaf packages that directly own types only). */
    private final Map<String, IntList> byPackage = new HashMap<>();
    /**
     * Every package name that {@link #hasPackage} should accept, including
     * intermediate parents of leaf packages (e.g. {@code java} when only
     * {@code java/lang} has types). Populated at write time so lookups stay O(1).
     */
    private final Set<String> knownPackages = new HashSet<>();
    // Modules are addressed by module name, not by JVM binary name, and
    // duplicates across classpath sources are resolved by classpath
    // ordering at lookup time (mirroring the TypeEntry bucket strategy).
    private final Map<String, List<ModuleEntry>> byModuleName = new HashMap<>();
    private final Map<String, IdentifierBloomFilter> bloomByResourceUri = new HashMap<>();
    // Observer list is touched once per merge and rarely mutated; guard it
    // with the same lock and iterate a snapshot taken outside it.
    private final List<Runnable> changedListeners = new ArrayList<>();

    @Override
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

    @Override
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

    @Override
    public Map<String, IdentifierBloomFilter> bloomFilters() {
        return read(() -> Map.copyOf(bloomByResourceUri));
    }

    @Override
    public void add(TypeEntry entry) {
        if (entry == null) return;
        String jvm = entry.jvmOwnerName();
        if (Index.isSkippedJvmName(jvm)) return;
        // Encode outside the lock: parsing already finished; keep the
        // critical section to blob-pointer moves only.
        byte[] blob = TypeEntryCodec.encode(entry);
        String pkg = entry.packageJvm();
        rwLock.writeLock().lock();
        try {
            int id = appendTypeBlobLocked(blob);
            appendId(byJvmName, jvm, id);
            appendId(byPackage, pkg, id);
            registerPackageAncestorsLocked(pkg);
        } finally {
            rwLock.writeLock().unlock();
        }
        notifyChanged();
    }

    /**
     * Merge every entry from {@code other} into this index and fire a
     * single change notification. When {@code other} is also an
     * {@link InMemoryIndex}, TypeEntry blobs are moved by reference (no
     * decode/re-encode) under one write-lock acquisition. Other
     * implementations are merged via the public query API.
     */
    @Override
    public void addAll(Index other) {
        if (other == null || other.isEmpty()) return;
        if (other instanceof InMemoryIndex mem) {
            addAllInMemory(mem);
        } else {
            addAllGeneric(other);
        }
    }

    private void addAllInMemory(InMemoryIndex other) {
        // Snapshot the source outside our lock (each call takes the
        // source's own lock); then apply the whole batch at once.
        TypeStoreSnapshot snapshot = other.snapshotTypeStore();
        Collection<ModuleEntry> modules = other.allModules();
        Map<String, IdentifierBloomFilter> blooms = other.bloomFilters();

        rwLock.writeLock().lock();
        try {
            int baseOffset = typeCount;
            ensureTypeBlobCapacity(typeCount + snapshot.blobs.length);
            for (byte[] blob : snapshot.blobs) {
                typeBlobs[typeCount++] = blob;
            }
            mergeIdMap(byJvmName, snapshot.byJvmName, baseOffset);
            mergeIdMap(byPackage, snapshot.byPackage, baseOffset);
            knownPackages.addAll(snapshot.knownPackages);
            for (ModuleEntry m : modules) addModuleLocked(m);
            blooms.forEach(this::registerBloomLocked);
        } finally {
            rwLock.writeLock().unlock();
        }
        notifyChanged();
    }

    /**
     * Contract-compatible merge for non-{@link InMemoryIndex} engines.
     * Entries are re-encoded on add; modules and blooms are copied under
     * one write lock so listeners still see a single notification.
     */
    private void addAllGeneric(Index other) {
        Collection<TypeEntry> types = other.all();
        Collection<ModuleEntry> modules = other.allModules();
        Map<String, IdentifierBloomFilter> blooms = other.bloomFilters();

        List<EncodedType> encoded = new ArrayList<>(types.size());
        for (TypeEntry entry : types) {
            if (entry == null) continue;
            String jvm = entry.jvmOwnerName();
            if (Index.isSkippedJvmName(jvm)) continue;
            encoded.add(new EncodedType(jvm, entry.packageJvm(), TypeEntryCodec.encode(entry)));
        }

        rwLock.writeLock().lock();
        try {
            ensureTypeBlobCapacity(typeCount + encoded.size());
            for (EncodedType e : encoded) {
                int id = appendTypeBlobLocked(e.blob);
                appendId(byJvmName, e.jvm, id);
                appendId(byPackage, e.pkg, id);
                registerPackageAncestorsLocked(e.pkg);
            }
            for (ModuleEntry m : modules) addModuleLocked(m);
            blooms.forEach(this::registerBloomLocked);
        } finally {
            rwLock.writeLock().unlock();
        }
        notifyChanged();
    }

    private record EncodedType(String jvm, String pkg, byte[] blob) {}

    /**
     * Consistent shallow snapshot of the type store, ID indexes, and
     * known package names (including parents). Blob array elements are
     * shared by reference (no re-encode); IntLists / sets are copied so
     * the source index can keep mutating.
     */
    TypeStoreSnapshot snapshotTypeStore() {
        return read(() -> {
            byte[][] blobs = Arrays.copyOf(typeBlobs, typeCount);
            return new TypeStoreSnapshot(
                    blobs,
                    copyIdMap(byJvmName),
                    copyIdMap(byPackage),
                    Set.copyOf(knownPackages));
        });
    }

    /**
     * Caller must hold the write lock. Registers {@code packageJvm} and
     * every non-empty slash-separated parent (e.g. {@code a/b/c} →
     * {@code a/b/c}, {@code a/b}, {@code a}).
     */
    private void registerPackageAncestorsLocked(String packageJvm) {
        String pkg = packageJvm;
        while (pkg != null && !pkg.isEmpty()) {
            knownPackages.add(pkg);
            int slash = pkg.lastIndexOf('/');
            pkg = slash < 0 ? "" : pkg.substring(0, slash);
        }
    }

    private static Map<String, IntList> copyIdMap(Map<String, IntList> source) {
        Map<String, IntList> copy = new HashMap<>(source.size());
        for (Map.Entry<String, IntList> e : source.entrySet()) {
            copy.put(e.getKey(), e.getValue().copy());
        }
        return copy;
    }

    /**
     * Caller must hold the write lock. Merges remapped source IDs
     * ({@code sourceId + baseOffset}) into {@code target}, pre-sizing each
     * bucket so a bulk merge does not thrash mid-bucket copies.
     */
    private static void mergeIdMap(Map<String, IntList> target, Map<String, IntList> incoming, int baseOffset) {
        for (Map.Entry<String, IntList> e : incoming.entrySet()) {
            IntList src = e.getValue();
            if (src == null || src.isEmpty()) continue;
            IntList dest = target.computeIfAbsent(e.getKey(), k -> new IntList(src.size()));
            dest.ensureCapacity(dest.size() + src.size());
            dest.addAllRemapped(src, baseOffset);
        }
    }

    @Override
    public boolean isEmpty() {
        return read(() -> typeCount == 0
                && byModuleName.isEmpty()
                && bloomByResourceUri.isEmpty());
    }

    /** Caller must hold the write lock. Appends {@code id} to the bucket for {@code key}. */
    private static void appendId(Map<String, IntList> map, String key, int id) {
        map.computeIfAbsent(key, k -> new IntList()).add(id);
    }

    /** Caller must hold the write lock. Appends {@code value} to the bucket for {@code key}. */
    private static <T> void appendBucket(Map<String, List<T>> map, String key, T value) {
        map.computeIfAbsent(key, k -> new ArrayList<>()).add(value);
    }

    /** Caller must hold the write lock. Returns the new artificial ID. */
    private int appendTypeBlobLocked(byte[] blob) {
        ensureTypeBlobCapacity(typeCount + 1);
        int id = typeCount;
        typeBlobs[typeCount++] = blob;
        return id;
    }

    /** Caller must hold the write lock. Grows the store by doubling. */
    private void ensureTypeBlobCapacity(int minCapacity) {
        if (minCapacity <= typeBlobs.length) return;
        int newCap = typeBlobs.length;
        while (newCap < minCapacity) {
            int doubled = newCap << 1;
            if (doubled < 0) {
                newCap = minCapacity;
                break;
            }
            newCap = Math.max(doubled, minCapacity);
        }
        typeBlobs = Arrays.copyOf(typeBlobs, newCap);
    }

    @Override
    public List<TypeEntry> getAll(String jvmName) {
        return read(() -> toList(byJvmName.get(jvmName)));
    }

    @Override
    public boolean contains(String jvmName) {
        return read(() -> {
            IntList bucket = byJvmName.get(jvmName);
            return bucket != null && !bucket.isEmpty();
        });
    }

    @Override
    public boolean hasPackage(String packageJvm) {
        return read(() -> knownPackages.contains(packageJvm));
    }

    @Override
    public List<TypeEntry> listPackage(String packageJvm, boolean recurse) {
        return read(() -> {
            if (recurse) {
                List<TypeEntry> entries = new ArrayList<>();
                String prefix = packageJvm + '/';
                IntList ids = byPackage.get(packageJvm);
                if (ids != null) {
                    addBucketTo(ids, entries);
                }
                for (Map.Entry<String, IntList> entry : byPackage.entrySet()) {
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

    @Override
    public List<TypeEntry> searchTypesBySimpleNamePrefix(String prefix, int limit) {
        if (prefix == null) return List.of();
        return read(() -> {
            List<TypeEntry> out = new ArrayList<>();
            for (Map.Entry<String, IntList> mapEntry : byJvmName.entrySet()) {
                String jvmOwnerName = mapEntry.getKey();
                if (!simpleNameMatchesPrefix(jvmOwnerName, prefix)) continue;
                IntList ids = mapEntry.getValue();
                for (int i = 0; i < ids.size(); i++) {
                    out.add(decode(ids.get(i)));
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

    @Override
    public Collection<TypeEntry> all() {
        return read(() -> {
            List<TypeEntry> out = new ArrayList<>(typeCount);
            for (int id = 0; id < typeCount; id++) {
                out.add(decode(id));
            }
            return Collections.unmodifiableCollection(out);
        });
    }

    @Override
    public int size() {
        return read(byJvmName::size);
    }

    @Override
    public int entryCount() {
        return read(() -> typeCount);
    }

    private static <T> List<T> toImmutableList(List<T> bucket) {
        if (bucket == null || bucket.isEmpty()) return List.of();
        return List.copyOf(bucket);
    }

    private static <T> void addAllTo(List<T> bucket, List<T> out) {
        if (bucket != null) out.addAll(bucket);
    }

    /** Caller must hold a lock. */
    private TypeEntry decode(int id) {
        return decodedCache.get(id, typeBlobs[id]);
    }

    private List<TypeEntry> toList(IntList bucket) {
        if (bucket == null || bucket.isEmpty()) return List.of();
        TypeEntry[] decoded = new TypeEntry[bucket.size()];
        for (int i = 0; i < bucket.size(); i++) {
            decoded[i] = decode(bucket.get(i));
        }
        return List.of(decoded);
    }

    private void addBucketTo(IntList bucket, List<TypeEntry> out) {
        if (bucket == null) return;
        for (int i = 0; i < bucket.size(); i++) {
            out.add(decode(bucket.get(i)));
        }
    }

    @Override
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

    @Override
    public List<ModuleEntry> getAllModules(String moduleName) {
        return read(() -> toImmutableList(byModuleName.get(moduleName)));
    }

    @Override
    public ModuleEntry getModule(String moduleName) {
        return read(() -> {
            List<ModuleEntry> bucket = byModuleName.get(moduleName);
            if (bucket == null || bucket.isEmpty()) return null;
            return bucket.get(0);
        });
    }

    @Override
    public Collection<ModuleEntry> allModules() {
        return read(() -> {
            List<ModuleEntry> out = new ArrayList<>();
            for (List<ModuleEntry> bucket : byModuleName.values()) {
                addAllTo(bucket, out);
            }
            return Collections.unmodifiableCollection(out);
        });
    }

    @Override
    public int moduleCount() {
        return read(byModuleName::size);
    }

    /** Snapshot of canonical blobs, secondary ID indexes, and known packages. */
    record TypeStoreSnapshot(
            byte[][] blobs,
            Map<String, IntList> byJvmName,
            Map<String, IntList> byPackage,
            Set<String> knownPackages) {}
}
