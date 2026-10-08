/**
 * Copyright 2026 by Castle Ridge Software GmbH
 *
 * Author: Thomas Mäder, Castle Ridge Software
 *
 */
package ch.castleridge.javals.analysis.ecj;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.eclipse.jdt.core.compiler.CharOperation;
import org.eclipse.jdt.internal.compiler.env.IBinaryType;
import org.eclipse.jdt.internal.compiler.env.IModule;
import org.eclipse.jdt.internal.compiler.env.IModuleAwareNameEnvironment;
import org.eclipse.jdt.internal.compiler.env.NameEnvironmentAnswer;

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
 * <p>Type-answer caches are per-compilation (or per-namespace batch during
 * references). Module/package probes share a long-lived
 * {@link NamespaceLookupCache}.
 */
final class IndexNameEnvironment implements IModuleAwareNameEnvironment {

    private final NamespaceLookupCache lookup;
    private final Map<String, NameEnvironmentAnswer> answers = new ConcurrentHashMap<>();

    IndexNameEnvironment(Index index, ClasspathOrder classpath) {
        this(new NamespaceLookupCache(index, classpath));
    }

    IndexNameEnvironment(NamespaceLookupCache lookup) {
        this.lookup = lookup;
    }

    Index index() {
        return lookup.index();
    }

    ClasspathOrder classpath() {
        return lookup.classpath();
    }

    NamespaceLookupCache lookup() {
        return lookup;
    }

    /** Package-visible for tests. */
    int answerCount() {
        return answers.size();
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
        TypeEntry winner = lookup.classpath().pick(lookup.index().getAll(jvmName), TypeEntry::sourceUri);
        if (winner == null) {
            return null;
        }
        ModuleEntry owning = lookup.owningModule(winner);
        String packageJvm = ModuleOwnership.packageOf(jvmName);
        if (!lookup.matchesModule(strategy, named, owning, packageJvm)) {
            return null;
        }
        IBinaryType binary = IndexBinaryType.of(winner, lookup.index(), lookup.classpath(), owning);
        // Named modules: tag the answer with the module name. Unnamed-classpath
        // types leave moduleName null so ECJ attaches them to the unnamed module
        // without rebinding java.base types during binary superclass resolution.
        NameEnvironmentAnswer made = owning == null
                ? new NameEnvironmentAnswer(binary, null, (char[]) null)
                : new NameEnvironmentAnswer(binary, null, owning.name().toCharArray());
        NameEnvironmentAnswer prior = answers.putIfAbsent(cacheKey, made);
        return prior == null ? made : prior;
    }

    @Override
    public boolean isPackage(char[][] parentPackageName, char[] packageName) {
        return lookup.isPackage(parentPackageName, packageName);
    }

    @Override
    public char[][] getModulesDeclaringPackage(char[][] packageName, char[] moduleName) {
        return lookup.getModulesDeclaringPackage(packageName, moduleName);
    }

    @Override
    public boolean hasCompilationUnit(char[][] qualifiedPackageName, char[] moduleName, boolean checkCUs) {
        String packageJvm = flatPackageJvm(qualifiedPackageName);
        LookupStrategy strategy = LookupStrategy.get(moduleName);
        String named = LookupStrategy.getStringName(moduleName);
        for (TypeEntry e : lookup.index().listPackage(packageJvm, false)) {
            if (!lookup.isVisible(e)) {
                continue;
            }
            ModuleEntry owning = lookup.owningModule(e);
            if (lookup.matchesModule(strategy, named, owning, packageJvm)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public IModule getModule(char[] moduleName) {
        return lookup.getModule(moduleName);
    }

    @Override
    public char[][] getAllAutomaticModules() {
        return NamespaceLookupCache.NO_MODULES;
    }

    @Override
    public char[][] listPackages(char[] moduleName) {
        return lookup.listPackages(moduleName);
    }

    @Override
    public void cleanup() {
        answers.clear();
    }

    ModuleEntry owningModule(TypeEntry entry) {
        return lookup.owningModule(entry);
    }

    private static String flatPackageJvm(char[][] packageName) {
        if (packageName == null || packageName.length == 0) {
            return "";
        }
        return CharOperation.toString(packageName).replace('.', '/');
    }
}
