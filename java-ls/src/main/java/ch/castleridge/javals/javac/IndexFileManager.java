package ch.castleridge.javals.javac;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import javax.tools.ForwardingJavaFileManager;
import javax.tools.JavaFileObject;
import javax.tools.JavaFileObject.Kind;
import javax.tools.StandardJavaFileManager;
import javax.tools.StandardLocation;

import com.sun.tools.javac.api.ClientCodeWrapper;

import ch.castleridge.javals.indexing.index.Index;
import ch.castleridge.javals.indexing.index.PrunedSourceFileObject;
import ch.castleridge.javals.indexing.index.RealClassFileObject;
import ch.castleridge.javals.indexing.model.ClassFileEntry;
import ch.castleridge.javals.indexing.model.IndexedClassRef;
import ch.castleridge.javals.indexing.model.ModuleEntry;
import ch.castleridge.javals.indexing.model.ModuleFileEntry;
import ch.castleridge.javals.indexing.model.PrunedSourceEntry;
import ch.castleridge.javals.indexing.model.TypeEntry;

/**
 * A {@link javax.tools.JavaFileManager} that layers a context-free
 * declaration {@link Index} on top of a standard file manager.
 *
 * <p>The index may contain multiple {@link TypeEntry} instances for the
 * same JVM binary name (e.g. when several jars on the build's classpath
 * declare the same type). A {@link ClasspathOrder} supplied to this
 * manager picks the winner deterministically: earlier entries in the
 * classpath shadow later ones, and entries whose source is not on the
 * classpath are ignored entirely.
 *
 * <p>For {@code CLASS} lookups on the classpath location the manager
 * synthesizes {@link IndexClassFileObject} instances for the winning
 * entries, then chains through to the wrapped
 * {@link StandardJavaFileManager} so real files on the classpath still
 * resolve when not shadowed by the index.
 */
@ClientCodeWrapper.Trusted
public class IndexFileManager extends ForwardingJavaFileManager<StandardJavaFileManager> {

    private final Index index;
    private final ClasspathOrder classpath;

    public IndexFileManager(StandardJavaFileManager delegate, Index index) {
        this(delegate, index, ClasspathOrder.UNRESTRICTED);
    }

    public IndexFileManager(StandardJavaFileManager delegate, Index index, ClasspathOrder classpath) {
        super(delegate);
        this.index = index;
        this.classpath = classpath;
    }

    @Override
    public Iterable<JavaFileObject> list(Location location,
                                         String packageName,
                                         Set<Kind> kinds,
                                         boolean recurse) throws IOException {

        // Synthetic per-module location: module-info at the root, plus
        // indexed types for packages owned by the module. Types may live
        // on CLASS_PATH (e.g. workspace sources) while the module
        // descriptor is advertised on MODULE_PATH; javac binds those
        // packages to the named module and will not populate them from
        // the unnamed-module CLASS_PATH copy (split-package skip).
        if (location instanceof IndexedModuleLocation iml) {
            if (!kinds.contains(Kind.CLASS)) return List.of();
            if (packageName == null || packageName.isEmpty()) {
                JavaFileObject mf = moduleFileObject(iml.moduleName());
                return mf == null ? List.of() : List.of(mf);
            }
            if (!moduleOwnsPackage(iml.moduleName(), packageName.replace('.', '/'))) {
                return List.of();
            }
            return listIndexedTypes(packageName.replace('.', '/'), recurse);
        }

        String pkgJvm = packageName.replace('.', '/');
        boolean indexedLocation = location == StandardLocation.SOURCE_PATH
                || location == StandardLocation.CLASS_PATH;
        List<JavaFileObject> result = new ArrayList<>();
        if (kinds.contains(Kind.SOURCE) && index.hasPrunedSources() && indexedLocation) {
            result.addAll(listPrunedSources(pkgJvm, recurse));
        }
        if (kinds.contains(Kind.CLASS)) {
            result.addAll(listIndexedTypes(pkgJvm, recurse));
        }
        if (!result.isEmpty()) {
            return result;
        }
        return super.list(location, packageName, kinds, recurse);
    }

    @Override
    public String inferBinaryName(Location location, JavaFileObject file) {
        if (file instanceof IndexClassFileObject icfo) {
            return icfo.binaryName();
        }
        if (file instanceof RealClassFileObject rcfo) {
            return rcfo.binaryName();
        }
        if (file instanceof PrunedSourceFileObject psfo) {
            return psfo.binaryName();
        }
        if (file instanceof IndexModuleFileObject) {
            return "module-info";
        }
        return super.inferBinaryName(location, file);
    }


    @Override
    public JavaFileObject getJavaFileForInput(Location location, String className, Kind kind) throws IOException {
        if (kind == Kind.SOURCE && className != null && index.hasPrunedSources()
                && (location == StandardLocation.SOURCE_PATH || location == StandardLocation.CLASS_PATH)) {
            JavaFileObject pruned = indexedPrunedSource(className.replace('.', '/'));
            if (pruned != null) return pruned;
        }
        if (kind == Kind.CLASS && className != null) {
            if (location instanceof IndexedModuleLocation iml) {
                if ("module-info".equals(className)) {
                    JavaFileObject mf = moduleFileObject(iml.moduleName());
                    if (mf != null) return mf;
                }
                String jvm = className.replace('.', '/');
                int slash = jvm.lastIndexOf('/');
                String pkgJvm = slash < 0 ? "" : jvm.substring(0, slash);
                if (moduleOwnsPackage(iml.moduleName(), pkgJvm)) {
                    JavaFileObject indexed = indexedClassFile(jvm);
                    if (indexed != null) return indexed;
                }
            } else {
                JavaFileObject indexed = indexedClassFile(className.replace('.', '/'));
                if (indexed != null) return indexed;
            }
        }

        return super.getJavaFileForInput(location, className, kind);
    }

    @Override
    public Iterable<Set<Location>> listLocationsForModules(Location location) throws IOException {
        Iterable<Set<Location>> delegated = super.listLocationsForModules(location);
        // Expose indexed user modules on MODULE_PATH so a compilation
        // can `requires` them without writing module-info.class to
        // disk. SYSTEM_MODULES is intentionally left alone: jrt-fs
        // already exposes the JDK modules to the standard file manager.
        if (location != StandardLocation.MODULE_PATH) {
            return delegated;
        }
        Set<Set<Location>> result = new LinkedHashSet<>();
        if (delegated != null) {
            for (Set<Location> existing : delegated) {
                result.add(existing);
            }
        }
        for (ModuleEntry me : index.allModules()) {
            if (me.sourceUri() != null && !classpath.contains(me.sourceUri())) continue;
            result.add(Set.of(new IndexedModuleLocation(me.name())));
        }
        for (ModuleFileEntry mf : index.allModuleFiles()) {
            if (mf.sourceUri() != null && !classpath.contains(mf.sourceUri())) continue;
            result.add(Set.of(new IndexedModuleLocation(mf.name())));
        }
        return result;
    }

    @Override
    public String inferModuleName(Location location) throws IOException {
        if (location instanceof IndexedModuleLocation iml) {
            return iml.moduleName();
        }
        return super.inferModuleName(location);
    }

    @Override
    public boolean hasLocation(Location location) {
        if (location instanceof IndexedModuleLocation) return true;
        if (index.hasPrunedSources()
                && (location == StandardLocation.SOURCE_PATH || location == StandardLocation.CLASS_PATH)) {
            return true;
        }
        return super.hasLocation(location);
    }

    @Override
    public Location getLocationForModule(Location location, String moduleName) throws IOException {
        if (location == StandardLocation.MODULE_PATH && moduleName != null) {
            if (index.getModule(moduleName) != null || index.getModuleFile(moduleName) != null) {
                return new IndexedModuleLocation(moduleName);
            }
        }
        return super.getLocationForModule(location, moduleName);
    }

    /** Convenience: cast and extract the backing entry, or null. */
    public static TypeEntry asEntry(JavaFileObject file) {
        return file instanceof IndexClassFileObject icfo ? icfo.entry() : null;
    }

    /**
     * Locator metadata for an indexed class, whether backed by a full
     * {@link TypeEntry} or a minimal {@link ClassFileEntry}.
     */
    public static IndexedClassRef asClassRef(JavaFileObject file) {
        if (file instanceof IndexClassFileObject icfo) {
            return IndexedClassRef.from(icfo.entry());
        }
        if (file instanceof RealClassFileObject rcfo) {
            return new IndexedClassRef(
                    rcfo.toUri().toString(), rcfo.sourceUri(), rcfo.jvmOwnerName());
        }
        if (file instanceof PrunedSourceFileObject psfo) {
            return IndexedClassRef.from(psfo.entry(), psfo.jvmOwnerName());
        }
        return null;
    }

    private JavaFileObject indexedClassFile(String jvmName) {
        TypeEntry typeWinner = classpath.pick(index.getAll(jvmName), TypeEntry::sourceUri);
        ClassFileEntry classWinner = classpath.pick(index.getAllClassFiles(jvmName), ClassFileEntry::sourceUri);
        if (typeWinner == null && classWinner == null) return null;
        if (typeWinner != null && classWinner == null) {
            return new IndexClassFileObject(typeWinner);
        }
        if (typeWinner == null) {
            return RealClassFileObject.from(classWinner);
        }
        int typeRank = classpath.rank(typeWinner.sourceUri());
        int classRank = classpath.rank(classWinner.sourceUri());
        if (typeRank >= 0 && (classRank < 0 || typeRank <= classRank)) {
            return new IndexClassFileObject(typeWinner);
        }
        if (classRank >= 0) {
            return RealClassFileObject.from(classWinner);
        }
        return new IndexClassFileObject(typeWinner);
    }

    private JavaFileObject indexedPrunedSource(String jvmName) {
        PrunedSourceEntry winner = classpath.pick(index.getAllPrunedSourcesByJvmName(jvmName), PrunedSourceEntry::sourceUri);
        return winner == null ? null : PrunedSourceFileObject.from(winner);
    }

    private List<JavaFileObject> listPrunedSources(String pkgJvm, boolean recurse) {
        Map<String, JavaFileObject> winners = new HashMap<>();
        Map<String, Integer> winnerRank = new HashMap<>();
        for (PrunedSourceEntry e : index.listPackagePrunedSources(pkgJvm, recurse)) {
            considerPrunedWinner(e, winners, winnerRank);
        }
        return List.copyOf(winners.values());
    }

    private void considerPrunedWinner(PrunedSourceEntry entry,
                                    Map<String, JavaFileObject> winners,
                                    Map<String, Integer> winnerRank) {
        int rank = classpath.rank(entry.sourceUri());
        if (rank < 0) return;
        String key = entry.resourceUri();
        Integer best = winnerRank.get(key);
        if (best == null || rank < best) {
            winners.put(key, PrunedSourceFileObject.from(entry));
            winnerRank.put(key, rank);
        }
    }

    private List<JavaFileObject> listIndexedTypes(String pkgJvm, boolean recurse) {
        Map<String, JavaFileObject> winners = new HashMap<>();
        Map<String, Integer> winnerRank = new HashMap<>();
        for (TypeEntry e : index.listPackage(pkgJvm, recurse)) {
            considerWinner(e.jvmOwnerName(), e.sourceUri(), new IndexClassFileObject(e), winners, winnerRank);
        }
        for (ClassFileEntry e : index.listPackageClassFiles(pkgJvm, recurse)) {
            considerWinner(e.jvmOwnerName(), e.sourceUri(), RealClassFileObject.from(e), winners, winnerRank);
        }
        return List.copyOf(winners.values());
    }

    private void considerWinner(String jvm,
                                String sourceUri,
                                JavaFileObject fileObject,
                                Map<String, JavaFileObject> winners,
                                Map<String, Integer> winnerRank) {
        int rank = classpath.rank(sourceUri);
        if (rank < 0) return;
        Integer best = winnerRank.get(jvm);
        if (best == null || rank < best) {
            winners.put(jvm, fileObject);
            winnerRank.put(jvm, rank);
        }
    }

    private boolean moduleOwnsPackage(String moduleName, String packageJvm) {
        ModuleEntry module = moduleEntry(moduleName);
        if (module != null && moduleOwnsPackage(module, packageJvm)) {
            return true;
        }
        ModuleFileEntry moduleFile = moduleFileEntry(moduleName);
        return moduleFile != null && moduleFile.packages().contains(packageJvm);
    }

    private ModuleEntry moduleEntry(String moduleName) {
        IndexModuleFileObject mf = moduleFile(moduleName);
        return mf == null ? null : mf.entry();
    }

    private ModuleFileEntry moduleFileEntry(String moduleName) {
        if (moduleName == null) return null;
        List<ModuleFileEntry> candidates = index.getAllModuleFiles(moduleName);
        if (candidates.isEmpty()) return null;
        List<ModuleFileEntry> filtered = new ArrayList<>();
        for (ModuleFileEntry mf : candidates) {
            if (mf.sourceUri() == null || classpath.contains(mf.sourceUri())) {
                filtered.add(mf);
            }
        }
        if (filtered.isEmpty()) return null;
        ModuleFileEntry winner = classpath.pick(filtered, ModuleFileEntry::sourceUri);
        return winner == null ? filtered.get(0) : winner;
    }

    /**
     * Materialise the indexed module {@code moduleName} as a file object
     * backed by real or synthesised {@code module-info.class} bytes.
     */
    public JavaFileObject moduleFileObject(String moduleName) {
        IndexModuleFileObject synthesized = moduleFile(moduleName);
        if (synthesized != null) return synthesized;
        ModuleFileEntry minimal = moduleFileEntry(moduleName);
        return minimal == null ? null : RealClassFileObject.moduleInfo(minimal);
    }

    private static boolean moduleOwnsPackage(ModuleEntry module, String packageJvm) {
        if (module.packages().contains(packageJvm)) return true;
        for (ModuleEntry.Exports e : module.exports()) {
            if (e.packageJvm().equals(packageJvm)) return true;
        }
        for (ModuleEntry.Opens o : module.opens()) {
            if (o.packageJvm().equals(packageJvm)) return true;
        }
        return false;
    }

    /**
     * Materialise the indexed module {@code moduleName} as a synthetic
     * {@link IndexModuleFileObject} backed by ASM-generated
     * {@code module-info.class} bytes, or {@code null} if no indexed
     * {@link ModuleEntry} matches (or none of its candidates pass the
     * classpath filter).
     *
     * <p>Returned independently of {@link #getJavaFileForInput} so
     * consumers that drive their own module discovery (LSP module
     * symbol search, completion proposers) can fetch synthesised
     * module-info files without going through the full file-manager
     * dance.
     */
    /**
     * Synthetic {@link Location} that pins a single indexed module
     * inside an enclosing module-oriented location.
     *
     * <p>{@link #getName()} follows javac's convention of
     * {@code <enclosing>[<moduleName>]} so the strings show up
     * readable in diagnostics. Equality is structural so the file
     * manager can recreate locations on demand without having to
     * memoise them.
     */
    static final class IndexedModuleLocation implements Location {
        private final String moduleName;

        IndexedModuleLocation(String moduleName) {
            this.moduleName = Objects.requireNonNull(moduleName);
        }

        String moduleName() {
            return moduleName;
        }

        @Override
        public String getName() {
            return "MODULE_PATH[" + moduleName + "]";
        }

        @Override
        public boolean isOutputLocation() {
            return false;
        }

        @Override
        public boolean isModuleOrientedLocation() {
            return false;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof IndexedModuleLocation other
                    && other.moduleName.equals(moduleName);
        }

        @Override
        public int hashCode() {
            return moduleName.hashCode();
        }

        @Override
        public String toString() {
            return getName();
        }
    }

    public IndexModuleFileObject moduleFile(String moduleName) {
        if (moduleName == null) return null;
        List<ModuleEntry> candidates = index.getAllModules(moduleName);
        if (candidates.isEmpty()) return null;
        // Apply the same classpath filter we apply to TypeEntry buckets
        // so that build-system module shadowing is honoured. Modules
        // whose backing module-info isn't on the active classpath are
        // skipped. If the classpath is UNRESTRICTED, every module
        // qualifies and ClasspathOrder.pick will return null - fall back
        // to the first observation in that case (matching the
        // "first one encountered wins" docstring).
        List<ModuleEntry> filtered = new ArrayList<>();
        for (ModuleEntry me : candidates) {
            if (me.sourceUri() == null || classpath.contains(me.sourceUri())) {
                filtered.add(me);
            }
        }
        if (filtered.isEmpty()) return null;
        ModuleEntry winner = classpath.pick(filtered, ModuleEntry::sourceUri);
        if (winner == null) winner = filtered.get(0);
        return new IndexModuleFileObject(winner);
    }
}
