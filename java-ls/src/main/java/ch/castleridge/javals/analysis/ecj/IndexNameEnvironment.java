/**
 * Copyright 2026 by Castle Ridge Software GmbH
 *
 * Author: Thomas Mäder, Castle Ridge Software
 *
 */
package ch.castleridge.javals.analysis.ecj;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.eclipse.jdt.core.compiler.CharOperation;
import org.eclipse.jdt.internal.compiler.env.IBinaryType;
import org.eclipse.jdt.internal.compiler.env.IModule;
import org.eclipse.jdt.internal.compiler.env.IModuleAwareNameEnvironment;
import org.eclipse.jdt.internal.compiler.env.NameEnvironmentAnswer;
import org.eclipse.jdt.internal.compiler.lookup.ModuleBinding;

import ch.castleridge.javals.classpath.ClasspathOrder;
import ch.castleridge.javals.indexing.index.Index;
import ch.castleridge.javals.indexing.model.ModuleEntry;
import ch.castleridge.javals.indexing.model.ModuleOwnership;
import ch.castleridge.javals.indexing.model.TypeEntry;

/**
 * ECJ {@link IModuleAwareNameEnvironment} backed exclusively by the
 * declaration index under a per-namespace {@link ClasspathOrder}.
 *
 * <p>Indexed declarations are exposed as {@link IBinaryType} adapters over
 * {@link TypeEntry}. Both source- and classfile-derived entries are treated
 * as binary types for binding construction (mirroring javac's
 * {@code IndexClassReader}). Named modules come from {@link ModuleEntry}
 * via {@link IndexBinaryModule}.
 *
 * <p>Instances are reusable across compilations for one namespace; call
 * {@link #cleanup()} only when the backing index or classpath is replaced.
 */
final class IndexNameEnvironment implements IModuleAwareNameEnvironment {
    private static final char[][] NO_MODULES = new char[0][];
    private static final char[][] NO_PACKAGES = new char[0][];

    private final Index index;
    private final ClasspathOrder classpath;
    private final Map<String, NameEnvironmentAnswer> answers = new ConcurrentHashMap<>();
    private final Map<String, IModule> modules = new ConcurrentHashMap<>();

    IndexNameEnvironment(Index index, ClasspathOrder classpath) {
        this.index = index;
        this.classpath = classpath == null ? ClasspathOrder.UNRESTRICTED : classpath;
    }

    Index index() {
        return index;
    }

    ClasspathOrder classpath() {
        return classpath;
    }

    @Override
    public NameEnvironmentAnswer findType(char[][] compoundName, char[] moduleName) {
        if (compoundName == null || compoundName.length == 0) {
            return null;
        }
        return find(CharOperation.toString(compoundName).replace('.', '/'), moduleName);
    }

    @Override
    public NameEnvironmentAnswer findType(char[] typeName, char[][] packageName, char[] moduleName) {
        StringBuilder name = new StringBuilder();
        if (packageName != null) {
            for (char[] component : packageName) {
                if (!name.isEmpty()) {
                    name.append('/');
                }
                name.append(component);
            }
        }
        if (!name.isEmpty()) {
            name.append('/');
        }
        name.append(typeName);
        return find(name.toString(), moduleName);
    }

    private NameEnvironmentAnswer find(String jvmName, char[] moduleName) {
        LookupStrategy strategy = LookupStrategy.get(moduleName);
        String named = LookupStrategy.getStringName(moduleName);
        String cacheKey = named == null
                ? strategy.name() + ':' + jvmName
                : strategy.name() + ':' + named + ':' + jvmName;
        NameEnvironmentAnswer cached = answers.get(cacheKey);
        if (cached != null) {
            return cached;
        }
        TypeEntry winner = classpath.pick(index.getAll(jvmName), TypeEntry::sourceUri);
        if (winner == null) {
            return null;
        }
        ModuleEntry owning = IndexBinaryType.owningModule(winner, index, classpath);
        String packageJvm = ModuleOwnership.packageOf(jvmName);
        if (!matchesModule(strategy, named, owning, packageJvm)) {
            return null;
        }
        IBinaryType binary = IndexBinaryType.of(winner, index, classpath);
        // Named modules: tag the answer with the module name. Unnamed-classpath
        // types leave moduleName null so ECJ attaches them to the unnamed module
        // without rebinding java.base types during binary superclass resolution.
        NameEnvironmentAnswer made = owning == null
                ? new NameEnvironmentAnswer(binary, null, (char[]) null)
                : new NameEnvironmentAnswer(binary, null, owning.name().toCharArray());
        NameEnvironmentAnswer prior = answers.putIfAbsent(cacheKey, made);
        return prior == null ? made : prior;
    }

    private boolean matchesModule(LookupStrategy strategy, String named, ModuleEntry owning, String packageJvm) {
        return switch (strategy) {
            case Any -> true;
            case AnyNamed -> owning != null;
            // Unnamed-module lookups must still see types in packages that
            // named modules export unqualified (java.lang → java.base). ECJ
            // asks findType(..., UNNAMED) when resolving superclasses of
            // binaries that live on the unnamed classpath.
            case Unnamed -> owning == null || exportsUnqualified(owning, packageJvm);
            case Named -> owning != null && owning.name().equals(named);
        };
    }

    private static boolean exportsUnqualified(ModuleEntry module, String packageJvm) {
        if (module == null || packageJvm == null) {
            return false;
        }
        for (ModuleEntry.Exports e : module.exports()) {
            if (!e.packageJvm().equals(packageJvm)) {
                continue;
            }
            return e.toModules() == null || e.toModules().length == 0;
        }
        // Packages listed on the module but not in exports are not readable
        // from the unnamed module; java.base still exports java.lang etc.
        return false;
    }

    @Override
    public char[][] getModulesDeclaringPackage(char[][] packageName, char[] moduleName) {
        String packageJvm = flatPackageJvm(packageName);
        LookupStrategy strategy = LookupStrategy.get(moduleName);
        String named = LookupStrategy.getStringName(moduleName);
        List<char[]> names = new ArrayList<>();
        boolean unnamedDeclares = false;

        for (ModuleEntry me : classpathModules()) {
            if (!ModuleOwnership.ownsPackage(me, packageJvm)) {
                continue;
            }
            if (matchesModule(strategy, named, me, packageJvm)) {
                names.add(me.name().toCharArray());
            }
        }
        // Intermediate parents (java when only java/lang is declared): fall back
        // to prefix matching only when no module owns the package exactly.
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
            unnamedDeclares = unnamedHasTypesInPackage(packageJvm);
            if (unnamedDeclares && (named == null || strategy == LookupStrategy.Unnamed)) {
                names.add(ModuleBinding.UNNAMED);
            }
        }

        if (names.isEmpty()) {
            // ANY with only unnamed types: still report unnamed when the package exists.
            if (strategy == LookupStrategy.Any && index.hasPackage(packageJvm)
                    && unnamedHasTypesInPackage(packageJvm)) {
                return new char[][] { ModuleBinding.UNNAMED };
            }
            return null;
        }
        return names.toArray(new char[names.size()][]);
    }

    @Override
    public boolean hasCompilationUnit(char[][] qualifiedPackageName, char[] moduleName, boolean checkCUs) {
        String packageJvm = flatPackageJvm(qualifiedPackageName);
        LookupStrategy strategy = LookupStrategy.get(moduleName);
        String named = LookupStrategy.getStringName(moduleName);
        for (TypeEntry e : index.listPackage(packageJvm, false)) {
            if (!isVisible(e)) {
                continue;
            }
            ModuleEntry owning = IndexBinaryType.owningModule(e, index, classpath);
            if (matchesModule(strategy, named, owning, packageJvm)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public IModule getModule(char[] moduleName) {
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

    @Override
    public char[][] getAllAutomaticModules() {
        return NO_MODULES;
    }

    @Override
    public char[][] listPackages(char[] moduleName) {
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

    @Override
    public void cleanup() {
        answers.clear();
        modules.clear();
    }

    private ModuleEntry pickModule(String moduleName) {
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

    private List<ModuleEntry> classpathModules() {
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
        return out;
    }

    private boolean unnamedHasTypesInPackage(String packageJvm) {
        for (TypeEntry e : index.listPackage(packageJvm, false)) {
            if (!isVisible(e)) {
                continue;
            }
            if (IndexBinaryType.owningModule(e, index, classpath) == null) {
                return true;
            }
        }
        return false;
    }

    private boolean isVisible(TypeEntry e) {
        if (classpath == ClasspathOrder.UNRESTRICTED) {
            return true;
        }
        return e.sourceUri() != null && classpath.contains(e.sourceUri());
    }

    private static String flatPackageJvm(char[][] packageName) {
        if (packageName == null || packageName.length == 0) {
            return "";
        }
        return CharOperation.toString(packageName).replace('.', '/');
    }

    private static String slashToDot(String jvm) {
        return jvm == null ? "" : jvm.replace('/', '.');
    }
}
