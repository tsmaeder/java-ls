/**
 * Copyright 2026 by Castle Ridge Software GmbH
 *
 * Author: Thomas Mäder, Castle Ridge Software
 *
 */
package ch.castleridge.javals.analysis.ecj;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.eclipse.jdt.core.compiler.CharOperation;
import org.eclipse.jdt.internal.compiler.env.IModule;
import org.eclipse.jdt.internal.compiler.env.IModuleAwareNameEnvironment;
import org.eclipse.jdt.internal.compiler.env.IUpdatableModule;
import org.eclipse.jdt.internal.compiler.env.NameEnvironmentAnswer;
import org.eclipse.jdt.internal.compiler.lookup.ModuleBinding;

import ch.castleridge.javals.classpath.ClasspathOrder;
import ch.castleridge.javals.indexing.index.Index;
import ch.castleridge.javals.indexing.model.ModuleEntry;
import ch.castleridge.javals.indexing.model.ResourceUris;
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
    private static final String UNNAMED_MODULE_NAME = new String(ModuleBinding.UNNAMED);

    private final Index index;
    private final ClasspathOrder classpath;

    /** Lazily filled; cleared in {@link #cleanup()}. */
    private List<ModuleEntry> cachedClasspathModules;
    /** Lazily filled from {@link #classpathModules()}; cleared in {@link #cleanup()}. */
    private Map<String, ModuleEntry> cachedModulesByPackageRoot;

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
        List<ModuleEntry> modules = classpathModules();

        for (TypeEntry entry : this.classpath.sort(this.index.getAll(jvmName), TypeEntry::sourceUri)) {
            ModuleEntry owning = owningModule(entry, modules);
            if (matchesModule(strategy, named, owning)) {
                return owning == null
                        ? new NameEnvironmentAnswer(
                                IndexBinaryType.of(entry, this.index, this.classpath, null), null, null)
                        : new NameEnvironmentAnswer(
                                IndexBinaryType.of(entry, this.index, this.classpath, owning),
                                null,
                                owning.name().toCharArray());
            }
        }
        return null;
    }

    @Override
    public boolean isPackage(char[][] parentPackageName, char[] packageName) {
        StringBuilder name = new StringBuilder();
        if (parentPackageName != null) {
            for (char[] component : parentPackageName) {
                if (!name.isEmpty()) name.append('/');
                name.append(component);
            }
        }
        if (!name.isEmpty()) name.append('/');
        name.append(packageName);
        String packageJvm = name.toString();
        if (!index.hasPackage(packageJvm)) {
            return false;
        }
        for (String root : index.packageRootsDeclaring(packageJvm)) {
            if (classpath.contains(root)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Classpath-visible modules using the same membership rule as type
     * lookup ({@link ClasspathOrder#filter} / prefix {@code contains}),
     * not exact {@code modulesForSourceUri} keys. Duplicate names are
     * collapsed to the classpath winner so package attribution matches
     * {@link #findType}.
     */
    List<ModuleEntry> classpathModules() {
        List<ModuleEntry> cached = this.cachedClasspathModules;
        if (cached == null) {
            cached = this.classpath.filter(this.index.allModules(), ModuleEntry::sourceUri);
            this.cachedClasspathModules = cached;
        }
        return cached;
    }

    private Map<String, ModuleEntry> modulesByPackageRoot() {
        Map<String, ModuleEntry> cached = this.cachedModulesByPackageRoot;
        if (cached == null) {
            cached = new HashMap<>();
            for (ModuleEntry module : classpathModules()) {
                cached.put(module.packageRootUri(), module);
            }
            this.cachedModulesByPackageRoot = cached;
        }
        return cached;
    }

    /**
     * Answer the relevant modules that declare the given package.
     * If moduleName is ModuleBinding.ANY then all packages are relevant,
     * if moduleName is ModuleBinding.UNNAMED, then only packages in the unnamed module are relevant,
     * otherwise consider only packages in the module identified by moduleName.
     */
    @Override
    public char[][] getModulesDeclaringPackage(char[][] packageName, char[] moduleName) {
        String packageJvm = flatPackageJvm(packageName);

        if (moduleName == ModuleBinding.ANY
                || moduleName == ModuleBinding.UNNAMED
                || moduleName == ModuleBinding.ANY_NAMED) {
            Map<String, ModuleEntry> modulesByRoot = modulesByPackageRoot();
            LinkedHashSet<String> moduleNames = new LinkedHashSet<>();
            for (String root : index.packageRootsDeclaring(packageJvm)) {
                if (!classpath.contains(root)) {
                    continue;
                }
                ModuleEntry module = modulesByRoot.get(root);
                if (module != null) {
                    if (moduleName != ModuleBinding.UNNAMED) {
                        moduleNames.add(module.name());
                    }
                } else if (moduleName == ModuleBinding.UNNAMED) {
                    return new char[][] { ModuleBinding.UNNAMED };
                } else if (moduleName != ModuleBinding.ANY_NAMED) {
                    moduleNames.add(UNNAMED_MODULE_NAME);
                }
            }
            return (moduleName == ModuleBinding.UNNAMED || moduleNames.isEmpty())
                    ? null
                    : toCharArrays(moduleNames);
        }

        String named = new String(moduleName);
        List<ModuleEntry> moduleEntries =
                this.classpath.filter(this.index.getAllModules(named), ModuleEntry::sourceUri);
        for (ModuleEntry module : moduleEntries) {
            if (this.index.packageRootDeclares(module.packageRootUri(), packageJvm)) {
                return new char[][] { moduleName };
            }
        }
        return null;
    }

    @Override
    public boolean hasCompilationUnit(char[][] qualifiedPackageName, char[] moduleName, boolean checkCUs) {
        // ECJ: "compilation unit" here means .java or .class types in the
        // exact package. Leaf packages under a module's package root are
        // the indexed equivalent.
        String packageJvm = flatPackageJvm(qualifiedPackageName);
        String named = LookupStrategy.getStringName(moduleName);

        if (moduleName == ModuleBinding.UNNAMED) {
            Map<String, ModuleEntry> modulesByPackageRoot = modulesByPackageRoot();
            Set<String> checkedPackageRoots = new HashSet<>();
            List<TypeEntry> entries = this.classpath.filter(index.listPackage(packageJvm, false), TypeEntry::sourceUri);
            for (TypeEntry entry : entries) {
                String packageRootUri = ResourceUris.packageRootUri(entry.resourceUri(), packageJvm);
                if (!checkedPackageRoots.contains(packageRootUri)) {
                    checkedPackageRoots.add(packageRootUri);
                    ModuleEntry module = modulesByPackageRoot.get(packageRootUri);
                    if (module == null) {
                        return true;
                    }
                }
            }
            return false;
        } else {
            List<TypeEntry> entries = this.index.listPackage(packageJvm, false);
            for (ModuleEntry module : this.index.getAllModules(named)) {
                if (this.classpath.contains(module.sourceUri())) {
                    for (TypeEntry e : entries) {
                        if (module.contains(e)) {
                            return true;
                        }
                    }
                }
            }
            return false;
        }
    }

    private static ModuleEntry owningModule(TypeEntry entry, List<ModuleEntry> modules) {
        for (ModuleEntry module : modules) {
            if (module.contains(entry)) {
                return module;
            }
        }
        return null;
    }

    private static boolean matchesModule(LookupStrategy strategy, String named, ModuleEntry owning) {
        return switch (strategy) {
            case Any -> true;
            case AnyNamed -> owning != null;
            case Unnamed -> owning == null;
            case Named -> owning != null && owning.name().equals(named);
        };
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
        this.cachedClasspathModules = null;
        this.cachedModulesByPackageRoot = null;
    }

    private static char[][] toCharArrays(Set<String> names) {
        char[][] out = new char[names.size()][];
        int i = 0;
        for (String name : names) {
            out[i++] = UNNAMED_MODULE_NAME.equals(name)
                    ? ModuleBinding.UNNAMED
                    : name.toCharArray();
        }
        return out;
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
