package ch.castleridge.javals.javac;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import javax.tools.ForwardingJavaFileManager;
import javax.tools.JavaFileObject;
import javax.tools.JavaFileObject.Kind;
import javax.tools.StandardJavaFileManager;
import javax.tools.StandardLocation;

import com.sun.tools.javac.api.ClientCodeWrapper;

import ch.castleridge.javals.indexing.index.Index;
import ch.castleridge.javals.indexing.model.ModuleEntry;
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
                IndexModuleFileObject mf = moduleFile(iml.moduleName());
                return mf == null ? List.of() : List.of(mf);
            }
            ModuleEntry module = moduleEntry(iml.moduleName());
            if (module == null) return List.of();
            String pkgJvm = packageName.replace('.', '/');
            if (!moduleOwnsPackage(module, pkgJvm)) return List.of();
            return listIndexedTypes(pkgJvm, recurse);
        }

        if (!kinds.contains(Kind.CLASS)) {
            return super.list(location, packageName, kinds, recurse);
        }

        return listIndexedTypes(packageName.replace('.', '/'), recurse);
    }

    @Override
    public String inferBinaryName(Location location, JavaFileObject file) {
        if (file instanceof IndexClassFileObject icfo) {
            return icfo.binaryName();
        }
        if (file instanceof IndexModuleFileObject) {
            return "module-info";
        }
        return super.inferBinaryName(location, file);
    }


    @Override
    public JavaFileObject getJavaFileForInput(Location location, String className, Kind kind) throws IOException {
        if (location instanceof IndexedModuleLocation iml && kind == Kind.CLASS) {
            if ("module-info".equals(className)) {
                IndexModuleFileObject mf = moduleFile(iml.moduleName());
                if (mf != null) return mf;
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
        return super.hasLocation(location);
    }

    @Override
    public Location getLocationForModule(Location location, String moduleName) throws IOException {
        if (location == StandardLocation.MODULE_PATH && moduleName != null) {
            if (index.getModule(moduleName) != null) {
                return new IndexedModuleLocation(moduleName);
            }
        }
        return super.getLocationForModule(location, moduleName);
    }

    /** Convenience: cast and extract the backing entry, or null. */
    public static TypeEntry asEntry(JavaFileObject file) {
        return file instanceof IndexClassFileObject icfo ? icfo.entry() : null;
    }

    private List<JavaFileObject> listIndexedTypes(String pkgJvm, boolean recurse) {
        Map<String, TypeEntry> winners = new HashMap<>();
        for (TypeEntry e : index.listPackage(pkgJvm, recurse)) {
            if (!classpath.contains(e.sourceUri())) continue;
            String jvm = e.jvmOwnerName();
            TypeEntry existing = winners.get(jvm);
            if (existing == null || classpath.pick(List.of(e, existing), TypeEntry::sourceUri) == e) {
                winners.put(jvm, e);
            }
        }
        return winners.values().stream().map(IndexClassFileObject::new).collect(Collectors.toList());
    }

    private ModuleEntry moduleEntry(String moduleName) {
        IndexModuleFileObject mf = moduleFile(moduleName);
        return mf == null ? null : mf.entry();
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
