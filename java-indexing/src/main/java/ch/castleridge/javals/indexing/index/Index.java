package ch.castleridge.javals.indexing.index;

import java.util.*;

import ch.castleridge.javals.indexing.bloom.IdentifierBloomFilter;
import ch.castleridge.javals.indexing.model.ClassFileEntry;
import ch.castleridge.javals.indexing.model.ModuleEntry;
import ch.castleridge.javals.indexing.model.ModuleFileEntry;
import ch.castleridge.javals.indexing.model.PrunedSourceEntry;
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
 * trino workload.
 *
 * <p>Thread-safety is provided by a single monitor ({@code synchronized}
 * on {@link #lock}) guarding plain {@link HashMap}s rather than
 * {@code ConcurrentHashMap}. Indexing happens in batches: a scanner builds
 * a per-source temporary index and merges it wholesale via
 * {@link #addAll(Index)}, so each merge takes the monitor exactly once and
 * applies the whole batch under it, avoiding the per-key CAS/bin-lock
 * overhead of {@code ConcurrentHashMap}. Critical sections are short (map
 * puts/gets only; the expensive parsing happens before entries reach the
 * index), so a single mutex is cheaper than a read/write lock's bookkeeping.
 */
public final class Index {

    private final Object lock = new Object();

    private final Map<String, Object> byJvmName = new HashMap<>();
    private final Map<String, Object> byPackage = new HashMap<>();
    private final Map<String, Object> classFileByJvmName = new HashMap<>();
    private final Map<String, Object> classFileByPackage = new HashMap<>();
    private final Map<String, Object> moduleFileByName = new HashMap<>();
    private final Map<String, Object> prunedByResourceUri = new HashMap<>();
    private final Map<String, Object> prunedByPackage = new HashMap<>();
    private final Map<String, Object> prunedByJvmName = new HashMap<>();
    // Modules are addressed by module name, not by JVM binary name, and
    // duplicates across classpath sources are resolved by classpath
    // ordering at lookup time (mirroring the TypeEntry bucket strategy).
    private final Map<String, Object> byModuleName = new HashMap<>();
    private final Map<String, IdentifierBloomFilter> bloomByResourceUri = new HashMap<>();
    // Observer list is touched once per merge and rarely mutated; guard it
    // with the same monitor and iterate a snapshot taken outside it.
    private final List<Runnable> changedListeners = new ArrayList<>();

    public void addChangedListener(Runnable listener) {
        if (listener == null) return;
        synchronized (lock) {
            changedListeners.add(listener);
        }
    }

    private void notifyChanged() {
        Runnable[] snapshot;
        synchronized (lock) {
            if (changedListeners.isEmpty()) return;
            snapshot = changedListeners.toArray(new Runnable[0]);
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
        synchronized (lock) {
            registerBloomLocked(resourceUri, filter);
        }
    }

    /** Caller must hold the monitor. */
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
        synchronized (lock) {
            return Map.copyOf(bloomByResourceUri);
        }
    }

    public void add(TypeEntry entry) {
        if (entry == null) return;
        boolean changed;
        synchronized (lock) {
            changed = addLocked(entry);
        }
        if (changed) notifyChanged();
    }

    /** Caller must hold the monitor. */
    private boolean addLocked(TypeEntry entry) {
        if (entry == null) return false;
        String jvm = entry.jvmOwnerName();
        if (isSkippedJvmName(jvm)) return false;

        byJvmName.put(jvm, appendBucket(byJvmName.get(jvm), entry));
        String pkg = entry.packageJvm();
        byPackage.put(pkg, appendBucket(byPackage.get(pkg), entry));
        return true;
    }

    /**
     * Merge every entry from {@code other} into this index and fire a
     * single change notification. The entire batch is applied under one
     * monitor acquisition. Bloom filters are merged silently.
     */
    public void addAll(Index other) {
        if (other == null || other.isEmpty()) return;
        // Snapshot the source outside our lock (each call takes the
        // source's own monitor); then apply the whole batch at once.
        Collection<TypeEntry> types = other.all();
        Collection<ModuleEntry> modules = other.allModules();
        Collection<ClassFileEntry> classFiles = other.allClassFiles();
        Collection<ModuleFileEntry> moduleFiles = other.allModuleFiles();
        Collection<PrunedSourceEntry> prunedSources = other.allPrunedSources();
        Map<String, IdentifierBloomFilter> blooms = other.bloomFilters();

        synchronized (lock) {
            for (TypeEntry e : types) addLocked(e);
            for (ModuleEntry m : modules) addModuleLocked(m);
            for (ClassFileEntry cf : classFiles) addClassFileLocked(cf);
            for (ModuleFileEntry mf : moduleFiles) addModuleFileLocked(mf);
            for (PrunedSourceEntry ps : prunedSources) addPrunedSourceLocked(ps);
            blooms.forEach(this::registerBloomLocked);
        }
        notifyChanged();
    }

    public boolean isEmpty() {
        synchronized (lock) {
            return byJvmName.isEmpty()
                    && byModuleName.isEmpty()
                    && classFileByJvmName.isEmpty()
                    && moduleFileByName.isEmpty()
                    && prunedByResourceUri.isEmpty()
                    && bloomByResourceUri.isEmpty();
        }
    }

    private static Object appendBucket(Object prior, TypeEntry entry) {
        if (prior == null) return entry;
        if (prior instanceof TypeEntry only) {
            return new TypeEntry[]{only, entry};
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
        synchronized (lock) {
            return toList(byJvmName.get(jvmName));
        }
    }

    /**
     * Return an arbitrary {@link TypeEntry} for {@code jvmName}, or
     * {@code null} if none exists. Which candidate is returned when
     * duplicates exist is unspecified; prefer {@link #getAll(String)} +
     * explicit classpath ordering for deterministic behaviour.
     */
    public TypeEntry get(String jvmName) {
        synchronized (lock) {
            Object bucket = byJvmName.get(jvmName);
            if (bucket == null) return null;
            if (bucket instanceof TypeEntry only) return only;
            TypeEntry[] arr = (TypeEntry[]) bucket;
            return arr.length == 0 ? null : arr[0];
        }
    }

    public boolean contains(String jvmName) {
        synchronized (lock) {
            Object bucket = byJvmName.get(jvmName);
            if (bucket == null) return false;
            if (bucket instanceof TypeEntry) return true;
            TypeEntry[] arr = (TypeEntry[]) bucket;
            return arr.length > 0;
        }
    }

    /**
     * Return every {@link TypeEntry} whose declaring package is
     * {@code packageJvm}. May contain duplicates (same JVM name from
     * multiple sources) - consumers apply their own deduplication.
     */
    public List<TypeEntry> listPackage(String packageJvm, boolean recurse) {
        synchronized (lock) {
            if (recurse) {
                List<TypeEntry> entries = new ArrayList<>();
                String prefix = packageJvm+'/';
                for (Map.Entry<String, Object> entry : byPackage.entrySet()) {
                    String packageName = entry.getKey();
                    if (packageName.startsWith(prefix)) {
                        addBucketTo(entry.getValue(), entries);
                    }
                }
                return entries;
            } else {
                return toList(byPackage.get(packageJvm == null ? "" : packageJvm));
            }
        }
    }

    /** Every {@link TypeEntry} currently stored, including duplicates. */
    public Collection<TypeEntry> all() {
        synchronized (lock) {
            List<TypeEntry> out = new ArrayList<>();
            for (Object bucket : byJvmName.values()) {
                addBucketTo(bucket, out);
            }
            return Collections.unmodifiableCollection(out);
        }
    }

    /** Number of distinct JVM binary names indexed (ignoring duplicates). */
    public int size() {
        synchronized (lock) {
            return byJvmName.size();
        }
    }

    /** Number of indexed entries in total, counting duplicates. */
    public int entryCount() {
        synchronized (lock) {
            int n = 0;
            for (Object bucket : byJvmName.values()) {
                if (bucket instanceof TypeEntry) n += 1;
                else if (bucket != null) n += ((TypeEntry[]) bucket).length;
            }
            for (Object bucket : classFileByJvmName.values()) {
                if (bucket instanceof ClassFileEntry) n += 1;
                else if (bucket != null) n += ((ClassFileEntry[]) bucket).length;
            }
            for (Object bucket : prunedByResourceUri.values()) {
                if (bucket instanceof PrunedSourceEntry) n += 1;
                else if (bucket != null) n += ((PrunedSourceEntry[]) bucket).length;
            }
            return n;
        }
    }

    public void addClassFile(ClassFileEntry entry) {
        if (entry == null) return;
        boolean changed;
        synchronized (lock) {
            changed = addClassFileLocked(entry);
        }
        if (changed) notifyChanged();
    }

    /** Caller must hold the monitor. */
    private boolean addClassFileLocked(ClassFileEntry entry) {
        if (entry == null) return false;
        String jvm = entry.jvmOwnerName();
        if (isSkippedJvmName(jvm)) return false;
        classFileByJvmName.put(jvm, appendClassFileBucket(classFileByJvmName.get(jvm), entry));
        String pkg = entry.packageJvm();
        classFileByPackage.put(pkg, appendClassFileBucket(classFileByPackage.get(pkg), entry));
        return true;
    }

    public List<ClassFileEntry> getAllClassFiles(String jvmName) {
        synchronized (lock) {
            return toClassFileList(classFileByJvmName.get(jvmName));
        }
    }

    public boolean containsClassFile(String jvmName) {
        synchronized (lock) {
            Object bucket = classFileByJvmName.get(jvmName);
            if (bucket == null) return false;
            if (bucket instanceof ClassFileEntry) return true;
            ClassFileEntry[] arr = (ClassFileEntry[]) bucket;
            return arr.length > 0;
        }
    }

    /**
     * Return every {@link ClassFileEntry} whose declaring package is
     * {@code packageJvm}. May contain duplicates from multiple sources.
     */
    public List<ClassFileEntry> listPackageClassFiles(String packageJvm, boolean recurse) {
        synchronized (lock) {
            if (recurse) {
                List<ClassFileEntry> entries = new ArrayList<>();
                String prefix = packageJvm + '/';
                for (Map.Entry<String, Object> entry : classFileByPackage.entrySet()) {
                    String packageName = entry.getKey();
                    if (packageName.startsWith(prefix)) {
                        addClassFileBucketTo(entry.getValue(), entries);
                    }
                }
                return entries;
            }
            return toClassFileList(classFileByPackage.get(packageJvm == null ? "" : packageJvm));
        }
    }

    /** Every {@link ClassFileEntry} currently stored, including duplicates. */
    public Collection<ClassFileEntry> allClassFiles() {
        synchronized (lock) {
            List<ClassFileEntry> out = new ArrayList<>();
            for (Object bucket : classFileByJvmName.values()) {
                addClassFileBucketTo(bucket, out);
            }
            return Collections.unmodifiableCollection(out);
        }
    }

    /** Number of distinct JVM binary names in the class-file registry. */
    public int classFileSize() {
        synchronized (lock) {
            return classFileByJvmName.size();
        }
    }

    public void addModuleFile(ModuleFileEntry module) {
        if (module == null) return;
        synchronized (lock) {
            addModuleFileLocked(module);
        }
        notifyChanged();
    }

    /** Caller must hold the monitor. */
    private boolean addModuleFileLocked(ModuleFileEntry module) {
        if (module == null) return false;
        String name = module.name();
        moduleFileByName.put(name, appendModuleFileBucket(moduleFileByName.get(name), module));
        return true;
    }

    public List<ModuleFileEntry> getAllModuleFiles(String moduleName) {
        synchronized (lock) {
            return toModuleFileList(moduleFileByName.get(moduleName));
        }
    }

    public ModuleFileEntry getModuleFile(String moduleName) {
        synchronized (lock) {
            Object bucket = moduleFileByName.get(moduleName);
            if (bucket == null) return null;
            if (bucket instanceof ModuleFileEntry only) return only;
            ModuleFileEntry[] arr = (ModuleFileEntry[]) bucket;
            return arr.length == 0 ? null : arr[0];
        }
    }

    /** Every {@link ModuleFileEntry} currently stored, including duplicates. */
    public Collection<ModuleFileEntry> allModuleFiles() {
        synchronized (lock) {
            List<ModuleFileEntry> out = new ArrayList<>();
            for (Object bucket : moduleFileByName.values()) {
                if (bucket instanceof ModuleFileEntry only) out.add(only);
                else if (bucket != null) {
                    for (ModuleFileEntry m : (ModuleFileEntry[]) bucket) out.add(m);
                }
            }
            return Collections.unmodifiableCollection(out);
        }
    }

    public int moduleFileCount() {
        synchronized (lock) {
            return moduleFileByName.size();
        }
    }

    private static Object appendClassFileBucket(Object prior, ClassFileEntry entry) {
        if (prior == null) return entry;
        if (prior instanceof ClassFileEntry only) {
            return new ClassFileEntry[]{only, entry};
        }
        ClassFileEntry[] arr = (ClassFileEntry[]) prior;
        ClassFileEntry[] grown = new ClassFileEntry[arr.length + 1];
        System.arraycopy(arr, 0, grown, 0, arr.length);
        grown[arr.length] = entry;
        return grown;
    }

    private static Object appendModuleFileBucket(Object prior, ModuleFileEntry entry) {
        if (prior == null) return entry;
        if (prior instanceof ModuleFileEntry only) {
            return new ModuleFileEntry[]{only, entry};
        }
        ModuleFileEntry[] arr = (ModuleFileEntry[]) prior;
        ModuleFileEntry[] grown = new ModuleFileEntry[arr.length + 1];
        System.arraycopy(arr, 0, grown, 0, arr.length);
        grown[arr.length] = entry;
        return grown;
    }

    private static List<ClassFileEntry> toClassFileList(Object bucket) {
        if (bucket == null) return List.of();
        if (bucket instanceof ClassFileEntry only) return List.of(only);
        ClassFileEntry[] arr = (ClassFileEntry[]) bucket;
        return arr.length == 0 ? List.of() : List.of(arr);
    }

    private static void addClassFileBucketTo(Object bucket, List<ClassFileEntry> out) {
        if (bucket == null) return;
        if (bucket instanceof ClassFileEntry only) {
            out.add(only);
            return;
        }
        for (ClassFileEntry e : (ClassFileEntry[]) bucket) out.add(e);
    }

    private static List<ModuleFileEntry> toModuleFileList(Object bucket) {
        if (bucket == null) return List.of();
        if (bucket instanceof ModuleFileEntry only) return List.of(only);
        ModuleFileEntry[] arr = (ModuleFileEntry[]) bucket;
        return arr.length == 0 ? List.of() : List.of(arr);
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
     * Add a {@link ModuleEntry} keyed by its module name. Multiple
     * entries for the same name (jrt + classpath jar with module-info,
     * shadowing module-path) are kept and disambiguated by classpath
     * order at lookup time, identical to the {@link TypeEntry} bucket
     * strategy.
     */
    public void addModule(ModuleEntry module) {
        if (module == null) return;
        synchronized (lock) {
            addModuleLocked(module);
        }
        notifyChanged();
    }

    /** Caller must hold the monitor. */
    private boolean addModuleLocked(ModuleEntry module) {
        if (module == null) return false;
        String name = module.name();
        byModuleName.put(name, appendModuleBucket(byModuleName.get(name), module));
        return true;
    }

    private static Object appendModuleBucket(Object prior, ModuleEntry entry) {
        if (prior == null) return entry;
        if (prior instanceof ModuleEntry only) {
            return new ModuleEntry[]{only, entry};
        }
        ModuleEntry[] arr = (ModuleEntry[]) prior;
        ModuleEntry[] grown = new ModuleEntry[arr.length + 1];
        System.arraycopy(arr, 0, grown, 0, arr.length);
        grown[arr.length] = entry;
        return grown;
    }

    /**
     * Return every {@link ModuleEntry} indexed under {@code moduleName}.
     * Order is observation-order; consumers impose deterministic
     * picking via {@link ModuleEntry#sourceUri()}.
     */
    public List<ModuleEntry> getAllModules(String moduleName) {
        synchronized (lock) {
            return toModuleList(byModuleName.get(moduleName));
        }
    }

    /** Convenience: pick an arbitrary {@link ModuleEntry} for the name, or null. */
    public ModuleEntry getModule(String moduleName) {
        synchronized (lock) {
            Object bucket = byModuleName.get(moduleName);
            if (bucket == null) return null;
            if (bucket instanceof ModuleEntry only) return only;
            ModuleEntry[] arr = (ModuleEntry[]) bucket;
            return arr.length == 0 ? null : arr[0];
        }
    }

    /** Every {@link ModuleEntry} currently stored, including duplicates. */
    public Collection<ModuleEntry> allModules() {
        synchronized (lock) {
            List<ModuleEntry> out = new ArrayList<>();
            for (Object bucket : byModuleName.values()) {
                if (bucket instanceof ModuleEntry only) out.add(only);
                else if (bucket != null) {
                    for (ModuleEntry m : (ModuleEntry[]) bucket) out.add(m);
                }
            }
            return Collections.unmodifiableCollection(out);
        }
    }

    /** Distinct number of module names indexed (ignoring duplicates). */
    public int moduleCount() {
        synchronized (lock) {
            return byModuleName.size();
        }
    }

    public void addPrunedSource(PrunedSourceEntry entry) {
        if (entry == null) return;
        boolean changed;
        synchronized (lock) {
            changed = addPrunedSourceLocked(entry);
        }
        if (changed) notifyChanged();
    }

    /** Caller must hold the monitor. */
    private boolean addPrunedSourceLocked(PrunedSourceEntry entry) {
        if (entry == null) return false;
        String resourceUri = entry.resourceUri();
        if (resourceUri == null) return false;
        prunedByResourceUri.put(resourceUri, appendPrunedBucket(prunedByResourceUri.get(resourceUri), entry));
        String pkg = entry.packageJvm() == null ? "" : entry.packageJvm();
        prunedByPackage.put(pkg, appendPrunedBucket(prunedByPackage.get(pkg), entry));
        for (String jvm : entry.topLevelBinaryNames()) {
            prunedByJvmName.put(jvm, appendPrunedBucket(prunedByJvmName.get(jvm), entry));
        }
        return true;
    }

    public List<PrunedSourceEntry> getAllPrunedSourcesByJvmName(String jvmName) {
        synchronized (lock) {
            return toPrunedList(prunedByJvmName.get(jvmName));
        }
    }

    public PrunedSourceEntry getPrunedSource(String resourceUri) {
        synchronized (lock) {
            Object bucket = prunedByResourceUri.get(resourceUri);
            if (bucket == null) return null;
            if (bucket instanceof PrunedSourceEntry only) return only;
            PrunedSourceEntry[] arr = (PrunedSourceEntry[]) bucket;
            return arr.length == 0 ? null : arr[0];
        }
    }

    public List<PrunedSourceEntry> getAllPrunedSources(String resourceUri) {
        synchronized (lock) {
            return toPrunedList(prunedByResourceUri.get(resourceUri));
        }
    }

    /**
     * Return every {@link PrunedSourceEntry} whose package is
     * {@code packageJvm}. May contain duplicates from multiple sources.
     */
    public List<PrunedSourceEntry> listPackagePrunedSources(String packageJvm, boolean recurse) {
        synchronized (lock) {
            if (recurse) {
                List<PrunedSourceEntry> entries = new ArrayList<>();
                String prefix = packageJvm + '/';
                for (Map.Entry<String, Object> entry : prunedByPackage.entrySet()) {
                    String packageName = entry.getKey();
                    if (packageName.startsWith(prefix)) {
                        addPrunedBucketTo(entry.getValue(), entries);
                    }
                }
                return entries;
            }
            return toPrunedList(prunedByPackage.get(packageJvm == null ? "" : packageJvm));
        }
    }

    /** Every {@link PrunedSourceEntry} currently stored, including duplicates. */
    public Collection<PrunedSourceEntry> allPrunedSources() {
        synchronized (lock) {
            List<PrunedSourceEntry> out = new ArrayList<>();
            for (Object bucket : prunedByResourceUri.values()) {
                addPrunedBucketTo(bucket, out);
            }
            return Collections.unmodifiableCollection(out);
        }
    }

    public int prunedSourceSize() {
        synchronized (lock) {
            return prunedByResourceUri.size();
        }
    }

    public boolean hasPrunedSources() {
        synchronized (lock) {
            return !prunedByResourceUri.isEmpty();
        }
    }

    private static Object appendPrunedBucket(Object prior, PrunedSourceEntry entry) {
        if (prior == null) return entry;
        if (prior instanceof PrunedSourceEntry only) {
            return new PrunedSourceEntry[]{only, entry};
        }
        PrunedSourceEntry[] arr = (PrunedSourceEntry[]) prior;
        PrunedSourceEntry[] grown = new PrunedSourceEntry[arr.length + 1];
        System.arraycopy(arr, 0, grown, 0, arr.length);
        grown[arr.length] = entry;
        return grown;
    }

    private static List<PrunedSourceEntry> toPrunedList(Object bucket) {
        if (bucket == null) return List.of();
        if (bucket instanceof PrunedSourceEntry only) return List.of(only);
        PrunedSourceEntry[] arr = (PrunedSourceEntry[]) bucket;
        return arr.length == 0 ? List.of() : List.of(arr);
    }

    private static void addPrunedBucketTo(Object bucket, List<PrunedSourceEntry> out) {
        if (bucket == null) return;
        if (bucket instanceof PrunedSourceEntry only) {
            out.add(only);
            return;
        }
        for (PrunedSourceEntry e : (PrunedSourceEntry[]) bucket) out.add(e);
    }

    private static List<ModuleEntry> toModuleList(Object bucket) {
        if (bucket == null) return List.of();
        if (bucket instanceof ModuleEntry only) return List.of(only);
        ModuleEntry[] arr = (ModuleEntry[]) bucket;
        return arr.length == 0 ? List.of() : List.of(arr);
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
