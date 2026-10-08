/**
 * Copyright 2026 by Castle Ridge Software GmbH
 *
 * Author: Thomas Mäder, Castle Ridge Software
 *
 */
package ch.castleridge.javals.analysis.ecj;

import java.net.URI;

import org.eclipse.jdt.internal.compiler.classfmt.ClassFileConstants;
import org.eclipse.jdt.internal.compiler.env.IBinaryAnnotation;
import org.eclipse.jdt.internal.compiler.env.IBinaryModule;
import org.eclipse.jdt.internal.compiler.env.IModule;

import ch.castleridge.javals.indexing.model.ModuleEntry;

/**
 * {@link IBinaryModule} adapter over an indexed {@link ModuleEntry}.
 *
 * <p>ECJ's {@code BinaryModuleBinding.create} requires {@link IBinaryModule}
 * for non-automatic modules; a plain {@link IModule} would ClassCast.
 */
final class IndexBinaryModule implements IBinaryModule {

    private static final IBinaryAnnotation[] NO_ANNOTATIONS = new IBinaryAnnotation[0];

    private final ModuleEntry entry;
    private final char[] name;
    private final IModuleReference[] requires;
    private final IPackageExport[] exports;
    private final IPackageExport[] opens;
    private final char[][] uses;
    private final IService[] provides;
    private final URI uri;

    static IndexBinaryModule of(ModuleEntry entry) {
        return new IndexBinaryModule(entry);
    }

    private IndexBinaryModule(ModuleEntry entry) {
        this.entry = entry;
        this.name = entry.name().toCharArray();
        this.requires = requiresOf(entry.requires());
        this.exports = exportsOf(entry.exports());
        this.opens = exportsOfOpens(entry.opens());
        this.uses = usesOf(entry.uses());
        this.provides = providesOf(entry.provides());
        this.uri = safeUri(entry.resourceUri());
    }

    ModuleEntry entry() {
        return entry;
    }

    @Override
    public char[] name() {
        return name;
    }

    @Override
    public IModuleReference[] requires() {
        return requires;
    }

    @Override
    public IPackageExport[] exports() {
        return exports;
    }

    @Override
    public IPackageExport[] opens() {
        return opens;
    }

    @Override
    public char[][] uses() {
        return uses;
    }

    @Override
    public IService[] provides() {
        return provides;
    }

    @Override
    public boolean isOpen() {
        return (entry.flags() & ClassFileConstants.ACC_OPEN) != 0;
    }

    @Override
    public IBinaryAnnotation[] getAnnotations() {
        return NO_ANNOTATIONS;
    }

    @Override
    public long getTagBits() {
        return 0L;
    }

    @Override
    public URI getURI() {
        return uri;
    }

    private static IModuleReference[] requiresOf(ModuleEntry.Requires[] requires) {
        if (requires.length == 0) {
            return IModule.NO_MODULE_REFS;
        }
        IModuleReference[] out = new IModuleReference[requires.length];
        for (int i = 0; i < requires.length; i++) {
            ModuleEntry.Requires r = requires[i];
            out[i] = new ModuleRef(r.moduleName().toCharArray(), r.flags());
        }
        return out;
    }

    private static IPackageExport[] exportsOf(ModuleEntry.Exports[] exports) {
        if (exports.length == 0) {
            return IModule.NO_EXPORTS;
        }
        IPackageExport[] out = new IPackageExport[exports.length];
        for (int i = 0; i < exports.length; i++) {
            ModuleEntry.Exports e = exports[i];
            out[i] = new PackageExport(slashToDot(e.packageJvm()).toCharArray(), targets(e.toModules()));
        }
        return out;
    }

    private static IPackageExport[] exportsOfOpens(ModuleEntry.Opens[] opens) {
        if (opens.length == 0) {
            return IModule.NO_OPENS;
        }
        IPackageExport[] out = new IPackageExport[opens.length];
        for (int i = 0; i < opens.length; i++) {
            ModuleEntry.Opens o = opens[i];
            out[i] = new PackageExport(slashToDot(o.packageJvm()).toCharArray(), targets(o.toModules()));
        }
        return out;
    }

    private static char[][] usesOf(String[] uses) {
        if (uses.length == 0) {
            return IModule.NO_USES;
        }
        char[][] out = new char[uses.length][];
        for (int i = 0; i < uses.length; i++) {
            out[i] = slashToDot(uses[i]).toCharArray();
        }
        return out;
    }

    private static IService[] providesOf(ModuleEntry.Provides[] provides) {
        if (provides.length == 0) {
            return IModule.NO_PROVIDES;
        }
        IService[] out = new IService[provides.length];
        for (int i = 0; i < provides.length; i++) {
            ModuleEntry.Provides p = provides[i];
            char[][] with = new char[p.implJvms().length][];
            for (int j = 0; j < p.implJvms().length; j++) {
                with[j] = slashToDot(p.implJvms()[j]).toCharArray();
            }
            out[i] = new Service(slashToDot(p.serviceJvm()).toCharArray(), with);
        }
        return out;
    }

    private static char[][] targets(String[] toModules) {
        if (toModules == null || toModules.length == 0) {
            return null;
        }
        char[][] out = new char[toModules.length][];
        for (int i = 0; i < toModules.length; i++) {
            out[i] = toModules[i].toCharArray();
        }
        return out;
    }

    private static String slashToDot(String jvm) {
        return jvm == null ? "" : jvm.replace('/', '.');
    }

    private static URI safeUri(String resourceUri) {
        if (resourceUri == null || resourceUri.isBlank()) {
            return null;
        }
        try {
            return URI.create(resourceUri);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private record ModuleRef(char[] name, int modifiers) implements IModuleReference {
        @Override
        public char[] name() {
            return name;
        }

        @Override
        public int getModifiers() {
            return modifiers;
        }
    }

    private record PackageExport(char[] name, char[][] targets) implements IPackageExport {
        @Override
        public char[] name() {
            return name;
        }

        @Override
        public char[][] targets() {
            return targets;
        }
    }

    private record Service(char[] name, char[][] with) implements IService {
        @Override
        public char[] name() {
            return name;
        }

        @Override
        public char[][] with() {
            return with;
        }
    }
}
