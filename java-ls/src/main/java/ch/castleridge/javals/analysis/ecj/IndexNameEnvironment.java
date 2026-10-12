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
import ch.castleridge.javals.classpath.ClasspathEntry;
import ch.castleridge.javals.indexing.model.ResourceUris;
import java.util.Map;
import java.util.HashMap;
import java.util.HashSet;

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
        for (TypeEntry entry : this.index.getAll(jvmName)) {
            if (!isVisible(entry)) {
                continue;
            }
            ModuleEntry owning = owningModule(entry, modules);
            if (!matchesModule(strategy, named, owning, packageJvm)) {
                continue;
            }
            matching.add(entry);
        }
        if (matching.isEmpty()) {
            return null;
        }
        TypeEntry winner = this.classpath.pick(matching, TypeEntry::sourceUri);
        if (winner == null) {
            winner = matching.get(0);
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
        for (TypeEntry entry : index.listPackage(packageJvm, true)) {
            if (classpath.rank(entry.sourceUri()) >= 0) return true;
        }
        return false;
    }
    
    
    List<ModuleEntry> getClasspathModules() {
                List<ClasspathEntry> entries = this.classpath.getEntries();
        LinkedHashSet<ModuleEntry> modules = new LinkedHashSet<>();
        for (ClasspathEntry entry : entries) {
            for (ModuleEntry module : this.index.modulesForSourceUri(entry.sourceUri())) {
                modules.add(module);
            }
        }
        return new ArrayList<>(modules);
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

        List<ModuleEntry> classpathModules = this.getClasspathModules();

        LinkedHashSet<String> modules = new LinkedHashSet<>();
        LinkedHashSet<char[]> moduleNames = new LinkedHashSet<>();
        if (moduleName == ModuleBinding.ANY || moduleName == ModuleBinding.UNNAMED || moduleName == ModuleBinding.ANY_NAMED) {
            List<TypeEntry> entries = this.classpath.filter(index.listPackage(packageJvm, true), TypeEntry::sourceUri);
            for (TypeEntry entry : entries) {
                ModuleEntry module = owningModule(entry, classpathModules);
                if (module != null) {
                    modules.add(module.name());
                    moduleNames.add(module.name().toCharArray());
                } else {
                    if (moduleName == ModuleBinding.UNNAMED) {
                        return new char[][] { ModuleBinding.UNNAMED };
                    } else {
                        if (moduleName != ModuleBinding.ANY_NAMED) {
                            moduleNames.add(ModuleBinding.UNNAMED);
                            modules.add(new String(ModuleBinding.UNNAMED));
                        }
                    }   
                }
            }
            return (moduleName == ModuleBinding.UNNAMED ||  moduleNames.isEmpty()) ? null : moduleNames.toArray(new char[moduleNames.size()][]);
        } else {
            String named = new String(moduleName);
            List<ModuleEntry> moduleEntries = this.classpath.filter(this.index.getAllModules(named), ModuleEntry::sourceUri);
            for (ModuleEntry module : moduleEntries) {
                if (this.index.packagesInPackageRoot(module.packageRootUri()).contains(packageJvm)) {
                    return new char[][] { moduleName };
                }
            }
            return null;
        }
    }

    @Override
    public boolean hasCompilationUnit(char[][] qualifiedPackageName, char[] moduleName, boolean checkCUs) {
        // ECJ: "compilation unit" here means .java or .class types in the
        // exact package. Leaf packages under a module's package root are
        // the indexed equivalent.
        String packageJvm = flatPackageJvm(qualifiedPackageName);
        String named = LookupStrategy.getStringName(moduleName);

        if (moduleName == ModuleBinding.UNNAMED) {
            Set<String> checkedPackageRoots = new HashSet<>();
            Map<String, ModuleEntry> modulesByPackageRoot = new HashMap<>();
            for (ModuleEntry module : this.getClasspathModules()) {
                modulesByPackageRoot.put(module.packageRootUri(), module);
            }
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
        } else  {
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
