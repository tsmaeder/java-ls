package ch.castleridge.javals.indexing.index;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import ch.castleridge.javals.indexing.model.ModuleEntry;
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
    // Modules are addressed by module name, not by JVM binary name, and
    // duplicates across classpath sources are resolved by classpath
    // ordering at lookup time (mirroring the TypeEntry bucket strategy).
    private final ConcurrentMap<String, Object> byModuleName = new ConcurrentHashMap<>();

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
    public List<TypeEntry> listPackage(String packageJvm, boolean recurse) {
        if (recurse) {
            List<TypeEntry> entries = new ArrayList<>();
            for (Map.Entry<String, Object> entry : byPackage.entrySet()) {
                String packageName = entry.getKey();
                if (packageJvm.startsWith(packageName)) {
                    addBucketTo(entry.getValue(), entries);
                }
            }
            return entries;
        } else {
            return toList(byPackage.get(packageJvm == null ? "" : packageJvm));
        }
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
     * Add a {@link ModuleEntry} keyed by its module name. Multiple
     * entries for the same name (jrt + classpath jar with module-info,
     * shadowing module-path) are kept and disambiguated by classpath
     * order at lookup time, identical to the {@link TypeEntry} bucket
     * strategy.
     */
    public void addModule(ModuleEntry module) {
        if (module == null) return;
        byModuleName.compute(module.name(), (k, prior) -> appendModuleBucket(prior, module));
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
        return toModuleList(byModuleName.get(moduleName));
    }

    /** Convenience: pick an arbitrary {@link ModuleEntry} for the name, or null. */
    public ModuleEntry getModule(String moduleName) {
        Object bucket = byModuleName.get(moduleName);
        if (bucket == null) return null;
        if (bucket instanceof ModuleEntry only) return only;
        ModuleEntry[] arr = (ModuleEntry[]) bucket;
        return arr.length == 0 ? null : arr[0];
    }

    /** Every {@link ModuleEntry} currently stored, including duplicates. */
    public Collection<ModuleEntry> allModules() {
        List<ModuleEntry> out = new ArrayList<>();
        for (Object bucket : byModuleName.values()) {
            if (bucket instanceof ModuleEntry only) out.add(only);
            else if (bucket != null) {
                for (ModuleEntry m : (ModuleEntry[]) bucket) out.add(m);
            }
        }
        return Collections.unmodifiableCollection(out);
    }

    /** Distinct number of module names indexed (ignoring duplicates). */
    public int moduleCount() {
        return byModuleName.size();
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
