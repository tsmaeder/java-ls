/**
 * Copyright 2026 by Castle Ridge Software GmbH
 *
 * Author: Thomas Mäder, Castle Ridge Software
 *
 */
package ch.castleridge.javals.analysis.ecj;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.eclipse.jdt.core.compiler.CharOperation;
import org.eclipse.jdt.internal.compiler.env.IModule;
import org.eclipse.jdt.internal.compiler.env.IModuleAwareNameEnvironment.LookupStrategy;
import org.eclipse.jdt.internal.compiler.lookup.ModuleBinding;

import ch.castleridge.javals.classpath.ClasspathOrder;
import ch.castleridge.javals.indexing.index.Index;
import ch.castleridge.javals.indexing.model.ModuleEntry;
import ch.castleridge.javals.indexing.model.ModuleOwnership;
import ch.castleridge.javals.indexing.model.TypeEntry;

/**
 * Long-lived per-namespace caches for module and package probes.
 *
 * <p>Kept across compilations for one mbt namespace; much smaller than the
 * per-type {@code answers} map on {@link IndexNameEnvironment}.
 */
final class NamespaceLookupCache {
    static final char[][] NO_MODULES = new char[0][];
    static final char[][] NO_PACKAGES = new char[0][];

    private final Index index;
    private final ClasspathOrder classpath;
    private final Map<String, IModule> modules = new ConcurrentHashMap<>();
    private final Map<String, Boolean> unnamedPackageCache = new ConcurrentHashMap<>();
    private final Map<String, char[][]> declaringModulesCache = new ConcurrentHashMap<>();

    private volatile List<ModuleEntry> cachedClasspathModules;
    private volatile Map<String, List<ModuleEntry>> exactPackageOwners;

    NamespaceLookupCache(Index index, ClasspathOrder classpath) {
        this.index = index;
        this.classpath = classpath == null ? ClasspathOrder.UNRESTRICTED : classpath;
    }

    Index index() {
        return index;
    }

    ClasspathOrder classpath() {
        return classpath;
    }

    int moduleCacheSize() {
        return modules.size();
    }

    void clear() {
        modules.clear();
        unnamedPackageCache.clear();
        declaringModulesCache.clear();
        cachedClasspathModules = null;
        exactPackageOwners = null;
    }

    boolean isPackage(char[][] parentPackageName, char[] packageName) {
        String packageJvm = flatPackageJvm(parentPackageName, packageName);
        if (hasExactPackageOwner(packageJvm)) {
            return true;
        }
        if (namedModuleDeclaresPrefix(packageJvm)) {
            return true;
        }
        return unnamedDeclaresPackage(packageJvm);
    }

    char[][] getModulesDeclaringPackage(char[][] packageName, char[] moduleName) {
        String packageJvm = flatPackageJvm(packageName);
        LookupStrategy strategy = LookupStrategy.get(moduleName);
        String named = LookupStrategy.getStringName(moduleName);
        String cacheKey = named == null
                ? strategy.name() + ':' + packageJvm
                : strategy.name() + ':' + named + ':' + packageJvm;
        char[][] cached = declaringModulesCache.get(cacheKey);
        if (cached != null) {
            return cached.length == 0 ? null : cached;
        }
        char[][] made = computeModulesDeclaringPackage(packageJvm, strategy, named);
        declaringModulesCache.putIfAbsent(cacheKey, made == null ? NO_MODULES : made);
        return made;
    }

    private char[][] computeModulesDeclaringPackage(
            String packageJvm, LookupStrategy strategy, String named) {
        List<char[]> names = new ArrayList<>();

        List<ModuleEntry> exact = exactPackageOwners().get(packageJvm);
        if (exact != null) {
            for (ModuleEntry me : exact) {
                if (matchesModule(strategy, named, me, packageJvm)) {
                    names.add(me.name().toCharArray());
                }
            }
        }
        if (names.isEmpty()) {
            for (ModuleEntry me : classpathModules()) {
                if (!ModuleOwnership.declaresPackageOrParent(me, packageJvm)) {
                    continue;
                }
                if (matchesModule(strategy, named, me, packageJvm)) {
                    names.add(me.name().toCharArray());
                }
            }
        }

        if (strategy == LookupStrategy.Any || strategy == LookupStrategy.Unnamed) {
            if (unnamedDeclaresPackage(packageJvm)
                    && (named == null || strategy == LookupStrategy.Unnamed)) {
                names.add(ModuleBinding.UNNAMED);
            }
        }

        if (names.isEmpty()) {
            return null;
        }
        return names.toArray(new char[names.size()][]);
    }

    IModule getModule(char[] moduleName) {
        if (moduleName == null || moduleName.length == 0) {
            return null;
        }
        if (moduleName == ModuleBinding.UNNAMED
                || moduleName == ModuleBinding.ANY
                || moduleName == ModuleBinding.ANY_NAMED) {
            return null;
        }
        String name = String.valueOf(moduleName);
        IModule cached = modules.get(name);
        if (cached != null) {
            return cached;
        }
        ModuleEntry winner = pickModule(name);
        if (winner == null) {
            return null;
        }
        IModule made = IndexBinaryModule.of(winner);
        IModule prior = modules.putIfAbsent(name, made);
        return prior == null ? made : prior;
    }

    char[][] listPackages(char[] moduleName) {
        String named = LookupStrategy.getStringName(moduleName);
        if (named == null) {
            return NO_PACKAGES;
        }
        ModuleEntry winner = pickModule(named);
        if (winner == null) {
            return NO_PACKAGES;
        }
        Set<String> packages = new LinkedHashSet<>();
        for (String p : winner.packages()) {
            packages.add(slashToDot(p));
        }
        for (ModuleEntry.Exports e : winner.exports()) {
            packages.add(slashToDot(e.packageJvm()));
        }
        for (ModuleEntry.Opens o : winner.opens()) {
            packages.add(slashToDot(o.packageJvm()));
        }
        if (winner.sourceUri() != null) {
            for (TypeEntry e : index.all()) {
                if (!winner.sourceUri().equals(e.sourceUri())) {
                    continue;
                }
                if (!isVisible(e)) {
                    continue;
                }
                String pkg = ModuleOwnership.packageOf(e.jvmOwnerName());
                if (!pkg.isEmpty()) {
                    packages.add(slashToDot(pkg));
                }
            }
        }
        if (packages.isEmpty()) {
            return NO_PACKAGES;
        }
        char[][] out = new char[packages.size()][];
        int i = 0;
        for (String p : packages) {
            out[i++] = p.toCharArray();
        }
        return out;
    }

    ModuleEntry owningModule(TypeEntry entry) {
        if (entry == null) {
            return null;
        }
        String packageJvm = ModuleOwnership.packageOf(entry.jvmOwnerName());
        List<ModuleEntry> owners = exactPackageOwners().get(packageJvm);
        if (owners == null || owners.isEmpty()) {
            return null;
        }
        String sourceUri = entry.sourceUri();
        ModuleEntry best = null;
        int bestRank = Integer.MAX_VALUE;
        for (ModuleEntry me : owners) {
            if (sourceUri != null && me.sourceUri() != null && !sourceUri.equals(me.sourceUri())) {
                continue;
            }
            int rank = classpath.rank(me.sourceUri());
            if (rank < 0) {
                rank = Integer.MAX_VALUE - 1;
            }
            if (rank < bestRank) {
                bestRank = rank;
                best = me;
            }
        }
        return best;
    }

    ModuleEntry pickModule(String moduleName) {
        List<ModuleEntry> candidates = index.getAllModules(moduleName);
        if (candidates.isEmpty()) {
            return null;
        }
        List<ModuleEntry> filtered = new ArrayList<>();
        for (ModuleEntry me : candidates) {
            if (me.sourceUri() == null || classpath.contains(me.sourceUri())) {
                filtered.add(me);
            }
        }
        if (filtered.isEmpty()) {
            return null;
        }
        ModuleEntry winner = classpath.pick(filtered, ModuleEntry::sourceUri);
        return winner == null ? filtered.get(0) : winner;
    }

    boolean matchesModule(LookupStrategy strategy, String named, ModuleEntry owning, String packageJvm) {
        return switch (strategy) {
            case Any -> true;
            case AnyNamed -> owning != null;
            case Unnamed -> owning == null || exportsUnqualified(owning, packageJvm);
            case Named -> owning != null && owning.name().equals(named);
        };
    }

    static boolean exportsUnqualified(ModuleEntry module, String packageJvm) {
        if (module == null || packageJvm == null) {
            return false;
        }
        for (ModuleEntry.Exports e : module.exports()) {
            if (!e.packageJvm().equals(packageJvm)) {
                continue;
            }
            return e.toModules() == null || e.toModules().length == 0;
        }
        return false;
    }

    boolean isVisible(TypeEntry e) {
        if (classpath == ClasspathOrder.UNRESTRICTED) {
            return true;
        }
        return e.sourceUri() != null && classpath.contains(e.sourceUri());
    }

    private List<ModuleEntry> classpathModules() {
        List<ModuleEntry> cached = cachedClasspathModules;
        if (cached != null) {
            return cached;
        }
        synchronized (this) {
            if (cachedClasspathModules != null) {
                return cachedClasspathModules;
            }
            List<ModuleEntry> out = new ArrayList<>();
            Set<String> seen = new LinkedHashSet<>();
            for (ModuleEntry me : index.allModules()) {
                if (me.sourceUri() != null && !classpath.contains(me.sourceUri())) {
                    continue;
                }
                if (!seen.add(me.name())) {
                    continue;
                }
                ModuleEntry winner = pickModule(me.name());
                if (winner != null) {
                    out.add(winner);
                }
            }
            cachedClasspathModules = List.copyOf(out);
            return cachedClasspathModules;
        }
    }

    private Map<String, List<ModuleEntry>> exactPackageOwners() {
        Map<String, List<ModuleEntry>> cached = exactPackageOwners;
        if (cached != null) {
            return cached;
        }
        synchronized (this) {
            if (exactPackageOwners != null) {
                return exactPackageOwners;
            }
            Map<String, List<ModuleEntry>> map = new HashMap<>();
            for (ModuleEntry me : classpathModules()) {
                addOwnedPackages(map, me);
            }
            Map<String, List<ModuleEntry>> frozen = new HashMap<>(map.size());
            for (Map.Entry<String, List<ModuleEntry>> e : map.entrySet()) {
                frozen.put(e.getKey(), List.copyOf(e.getValue()));
            }
            exactPackageOwners = Map.copyOf(frozen);
            return exactPackageOwners;
        }
    }

    private static void addOwnedPackages(Map<String, List<ModuleEntry>> map, ModuleEntry me) {
        for (String p : me.packages()) {
            addOwner(map, p, me);
        }
        for (ModuleEntry.Exports e : me.exports()) {
            addOwner(map, e.packageJvm(), me);
        }
        for (ModuleEntry.Opens o : me.opens()) {
            addOwner(map, o.packageJvm(), me);
        }
    }

    private static void addOwner(Map<String, List<ModuleEntry>> map, String packageJvm, ModuleEntry me) {
        List<ModuleEntry> owners = map.computeIfAbsent(packageJvm, k -> new ArrayList<>(1));
        if (!owners.contains(me)) {
            owners.add(me);
        }
    }

    private boolean hasExactPackageOwner(String packageJvm) {
        List<ModuleEntry> owners = exactPackageOwners().get(packageJvm);
        return owners != null && !owners.isEmpty();
    }

    private boolean namedModuleDeclaresPrefix(String packageJvm) {
        for (ModuleEntry me : classpathModules()) {
            if (ModuleOwnership.declaresPackageOrParent(me, packageJvm)) {
                return true;
            }
        }
        return false;
    }

    private boolean unnamedDeclaresPackage(String packageJvm) {
        Boolean cached = unnamedPackageCache.get(packageJvm);
        if (cached != null) {
            return cached;
        }
        boolean result = computeUnnamedDeclaresPackage(packageJvm);
        unnamedPackageCache.putIfAbsent(packageJvm, result);
        return result;
    }

    private boolean computeUnnamedDeclaresPackage(String packageJvm) {
        if (unnamedHasVisibleType(packageJvm, false)) {
            return true;
        }
        if (!index.hasPackage(packageJvm)) {
            return false;
        }
        return unnamedHasVisibleType(packageJvm, true);
    }

    private boolean unnamedHasVisibleType(String packageJvm, boolean recurse) {
        for (TypeEntry e : index.listPackage(packageJvm, recurse)) {
            if (!isVisible(e)) {
                continue;
            }
            if (owningModule(e) == null) {
                return true;
            }
        }
        return false;
    }

    private static String flatPackageJvm(char[][] packageName) {
        if (packageName == null || packageName.length == 0) {
            return "";
        }
        return CharOperation.toString(packageName).replace('.', '/');
    }

    private static String flatPackageJvm(char[][] parentPackageName, char[] packageName) {
        StringBuilder name = new StringBuilder();
        if (parentPackageName != null) {
            for (char[] component : parentPackageName) {
                if (!name.isEmpty()) {
                    name.append('/');
                }
                name.append(component);
            }
        }
        if (packageName != null && packageName.length > 0) {
            if (!name.isEmpty()) {
                name.append('/');
            }
            name.append(packageName);
        }
        return name.toString();
    }

    private static String slashToDot(String jvm) {
        return jvm == null ? "" : jvm.replace('/', '.');
    }
}
