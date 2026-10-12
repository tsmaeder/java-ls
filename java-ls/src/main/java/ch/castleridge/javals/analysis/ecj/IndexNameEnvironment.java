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
import java.util.Set;

import org.eclipse.jdt.core.compiler.CharOperation;
import org.eclipse.jdt.internal.compiler.env.IBinaryType;
import org.eclipse.jdt.internal.compiler.env.IModule;
import org.eclipse.jdt.internal.compiler.env.IModuleAwareNameEnvironment;
import org.eclipse.jdt.internal.compiler.env.IUpdatableModule;
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
 */
final class IndexNameEnvironment implements IModuleAwareNameEnvironment {
    private static final char[][] NO_PACKAGES = new char[0][];

    private final Index index;
    private final ClasspathOrder classpath;

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
    public NameEnvironmentAnswer findType(char[] typeName, char[][] packageName, char[] moduleName) {
        return findType(CharOperation.arrayConcat(packageName, typeName), moduleName);
    }

    @Override
    public NameEnvironmentAnswer findType(char[][] compoundName, char[] moduleName) {
        if (compoundName == null || compoundName.length == 0) {
            return null;
        }
        String jvmName = new String(CharOperation.concatWith(compoundName, '/'));
        LookupStrategy strategy = LookupStrategy.get(moduleName);
        String named = LookupStrategy.getStringName(moduleName);
        String packageJvm = ModuleOwnership.packageOf(jvmName);
        List<ModuleEntry> modules = this.classpath.filter(this.index.allModules(), ModuleEntry::sourceUri);

        List<TypeEntry> matching = new ArrayList<>();
        List<TypeEntry> namedOwned = null;
        boolean preferNamed = strategy == LookupStrategy.Any
                && hasExactNamedOwner(packageJvm, modules);
        for (TypeEntry entry : this.index.getAll(jvmName)) {
            if (!isVisible(entry)) {
                continue;
            }
            ModuleEntry owning = owningModule(entry, modules);
            if (!matchesModule(strategy, named, owning, packageJvm)) {
                continue;
            }
            matching.add(entry);
            if (preferNamed && owning != null) {
                if (namedOwned == null) {
                    namedOwned = new ArrayList<>();
                }
                namedOwned.add(entry);
            }
        }
        List<TypeEntry> pool = namedOwned != null && !namedOwned.isEmpty() ? namedOwned : matching;
        if (pool.isEmpty()) {
            return null;
        }
        TypeEntry winner = this.classpath.pick(pool, TypeEntry::sourceUri);
        if (winner == null) {
            winner = pool.get(0);
        }
        ModuleEntry owning = owningModule(winner, modules);
        return owning == null
                ? new NameEnvironmentAnswer(
                        IndexBinaryType.of(winner, this.index, this.classpath, null), null, null)
                : new NameEnvironmentAnswer(
                        IndexBinaryType.of(winner, this.index, this.classpath, owning),
                        null,
                        owning.name().toCharArray());
    }

    private boolean hasExactNamedOwner(String packageJvm, List<ModuleEntry> modules) {
        for (ModuleEntry module : modules) {
            if (hasExactNamedTypeInPackage(module, packageJvm)
                    || ModuleOwnership.ownsPackage(module, packageJvm)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean isPackage(char[][] parentPackageName, char[] packageName) {
        return getModulesDeclaringPackage(
                CharOperation.arrayConcat(parentPackageName, packageName),
                ModuleBinding.ANY) != null;
    }

    @Override
    public char[][] getModulesDeclaringPackage(char[][] packageName, char[] moduleName) {
        String packageJvm = flatPackageJvm(packageName);
        LookupStrategy strategy = LookupStrategy.get(moduleName);
        String named = LookupStrategy.getStringName(moduleName);

        List<char[]> names = new ArrayList<>();
        List<ModuleEntry> candidates = classpathModules(strategy, named);
        boolean exactNamedOwner = false;
        for (ModuleEntry module : candidates) {
            if (!moduleDeclaresPackage(module, packageJvm)) {
                continue;
            }
            if (matchesNamedStrategy(strategy, named, module)) {
                names.add(module.name().toCharArray());
            }
            if (hasExactNamedTypeInPackage(module, packageJvm)) {
                exactNamedOwner = true;
            }
        }

        boolean wantUnnamed = strategy == LookupStrategy.Any || strategy == LookupStrategy.Unnamed;
        boolean suppressUnnamed = strategy == LookupStrategy.Any && exactNamedOwner;
        if (wantUnnamed && !suppressUnnamed && unnamedDeclaresPackage(packageJvm)) {
            names.add(ModuleBinding.UNNAMED);
        }

        if (names.isEmpty()) {
            return null;
        }
        return names.toArray(new char[names.size()][]);
    }

    @Override
    public boolean hasCompilationUnit(char[][] qualifiedPackageName, char[] moduleName, boolean checkCUs) {
        String packageJvm = flatPackageJvm(qualifiedPackageName);
        LookupStrategy strategy = LookupStrategy.get(moduleName);
        String named = LookupStrategy.getStringName(moduleName);
        List<ModuleEntry> modules = this.classpath.filter(this.index.allModules(), ModuleEntry::sourceUri);
        for (TypeEntry e : index.listPackage(packageJvm, false)) {
            if (!isVisible(e)) {
                continue;
            }
            ModuleEntry owning = owningModule(e, modules);
            if (matchesModule(strategy, named, owning, packageJvm)) {
                return true;
            }
        }
        return false;
    }

    private static ModuleEntry owningModule(TypeEntry entry, List<ModuleEntry> modules) {
        for (ModuleEntry module : modules) {
            if (module.contains(entry)) {
                return module;
            }
        }
        return null;
    }

    private static boolean matchesModule(
            LookupStrategy strategy, String named, ModuleEntry owning, String packageJvm) {
        return switch (strategy) {
            case Any -> true;
            case AnyNamed -> owning != null;
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
        ModuleEntry result = this.classpath.pick(
                this.index.getAllModules(new String(moduleName)), ModuleEntry::sourceUri);
        return result == null ? null : IndexBinaryModule.of(result);
    }

    @Override
    public char[][] getAllAutomaticModules() {
        return new char[0][];
    }

    @Override
    public void applyModuleUpdates(IUpdatableModule module, IUpdatableModule.UpdateKind kind) {
        /* default: do nothing */
    }

    /**
     * Lists all packages in the module identified by the given, real module name
     * (i.e., this method is implemented only for {@link LookupStrategy#Named}).
     * @return array of flat, dot-separated package names
     */
    @Override
    public char[][] listPackages(char[] moduleName) {
        String named = LookupStrategy.getStringName(moduleName);
        if (named == null) {
            return NO_PACKAGES;
        }
        ModuleEntry winner = this.classpath.pick(
                this.index.getAllModules(named), ModuleEntry::sourceUri);
        if (winner == null) {
            return NO_PACKAGES;
        }
        Set<String> packages = new LinkedHashSet<>();
        for (String p : index.packagesInPackageRoot(winner.packageRootUri())) {
            if (p != null && !p.isEmpty()) {
                packages.add(slashToDot(p));
            }
        }
        for (String p : winner.packages()) {
            if (p != null && !p.isEmpty()) {
                packages.add(slashToDot(p));
            }
        }
        for (ModuleEntry.Exports e : winner.exports()) {
            if (e.packageJvm() != null && !e.packageJvm().isEmpty()) {
                packages.add(slashToDot(e.packageJvm()));
            }
        }
        for (ModuleEntry.Opens o : winner.opens()) {
            if (o.packageJvm() != null && !o.packageJvm().isEmpty()) {
                packages.add(slashToDot(o.packageJvm()));
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
        // No per-environment caches yet.
    }

    private List<ModuleEntry> classpathModules(LookupStrategy strategy, String named) {
        if (strategy == LookupStrategy.Named) {
            return this.classpath.filter(this.index.getAllModules(named), ModuleEntry::sourceUri);
        }
        List<ModuleEntry> visible = this.classpath.filter(this.index.allModules(), ModuleEntry::sourceUri);
        List<ModuleEntry> winners = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (ModuleEntry me : visible) {
            if (!seen.add(me.name())) {
                continue;
            }
            ModuleEntry winner = this.classpath.pick(
                    this.index.getAllModules(me.name()), ModuleEntry::sourceUri);
            if (winner != null) {
                winners.add(winner);
            }
        }
        return winners;
    }

    private boolean moduleDeclaresPackage(ModuleEntry module, String packageJvm) {
        Set<String> inRoot = index.packagesInPackageRoot(module.packageRootUri());
        if (inRoot.contains(packageJvm)) {
            return true;
        }
        return ModuleOwnership.declaresPackageOrParent(module, packageJvm);
    }

    private static boolean matchesNamedStrategy(LookupStrategy strategy, String named, ModuleEntry module) {
        return switch (strategy) {
            case Any, AnyNamed -> true;
            case Named -> module.name().equals(named);
            case Unnamed -> false;
        };
    }

    private boolean hasExactNamedTypeInPackage(ModuleEntry module, String packageJvm) {
        for (TypeEntry e : index.listPackage(packageJvm, false)) {
            if (!isVisible(e)) {
                continue;
            }
            if (module.contains(e)) {
                return true;
            }
        }
        return false;
    }

    private boolean unnamedDeclaresPackage(String packageJvm) {
        if (unnamedHasVisibleType(packageJvm, false)) {
            return true;
        }
        if (!index.hasPackage(packageJvm)) {
            return false;
        }
        return unnamedHasVisibleType(packageJvm, true);
    }

    private boolean unnamedHasVisibleType(String packageJvm, boolean recurse) {
        List<ModuleEntry> modules = this.classpath.filter(this.index.allModules(), ModuleEntry::sourceUri);
        for (TypeEntry e : index.listPackage(packageJvm, recurse)) {
            if (!isVisible(e)) {
                continue;
            }
            if (owningModule(e, modules) == null) {
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
