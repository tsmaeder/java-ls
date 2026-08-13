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
package ch.castleridge.javals.analysis.javac;

import ch.castleridge.javals.classpath.ClasspathOrder;

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
import ch.castleridge.javals.indexing.model.IndexedClassRef;
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
                JavaFileObject mf = moduleFileObject(iml.moduleName());
                return mf == null ? List.of() : List.of(mf);
            }
            if (!moduleOwnsPackage(iml.moduleName(), packageName.replace('.', '/'))) {
                return List.of();
            }
            return listIndexedTypes(packageName.replace('.', '/'), recurse);
        }

        String pkgJvm = packageName.replace('.', '/');
        if (kinds.contains(Kind.CLASS)) {
            List<JavaFileObject> indexed = listIndexedTypes(pkgJvm, recurse);
            if (!indexed.isEmpty()) {
                return indexed;
            }
        }
        return super.list(location, packageName, kinds, recurse);
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

    /** Locator metadata for an indexed class backed by a {@link TypeEntry}. */
    public static IndexedClassRef asClassRef(JavaFileObject file) {
        if (file instanceof IndexClassFileObject icfo) {
            return IndexedClassRef.from(icfo.entry());
        }
        return null;
    }

    private JavaFileObject indexedClassFile(String jvmName) {
        TypeEntry winner = classpath.pick(index.getAll(jvmName), TypeEntry::sourceUri);
        return winner == null ? null : new IndexClassFileObject(winner);
    }

    private List<JavaFileObject> listIndexedTypes(String pkgJvm, boolean recurse) {
        Map<String, JavaFileObject> winners = new HashMap<>();
        Map<String, Integer> winnerRank = new HashMap<>();
        for (TypeEntry e : index.listPackage(pkgJvm, recurse)) {
            considerWinner(e.jvmOwnerName(), e.sourceUri(), new IndexClassFileObject(e), winners, winnerRank);
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
        return module != null && moduleOwnsPackage(module, packageJvm);
    }

    private ModuleEntry moduleEntry(String moduleName) {
        IndexModuleFileObject mf = moduleFile(moduleName);
        return mf == null ? null : mf.entry();
    }

    /**
     * Materialise the indexed module {@code moduleName} as a file object
     * backed by synthesised {@code module-info.class} bytes.
     */
    public JavaFileObject moduleFileObject(String moduleName) {
        return moduleFile(moduleName);
    }

    private static boolean moduleOwnsPackage(ModuleEntry module, String packageJvm) {
        if (containsPackage(module.packages(), packageJvm)) return true;
        for (ModuleEntry.Exports e : module.exports()) {
            if (e.packageJvm().equals(packageJvm)) return true;
        }
        for (ModuleEntry.Opens o : module.opens()) {
            if (o.packageJvm().equals(packageJvm)) return true;
        }
        return false;
    }

    private static boolean containsPackage(String[] packages, String packageJvm) {
        for (String p : packages) {
            if (p.equals(packageJvm)) return true;
        }
        return false;
    }

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
