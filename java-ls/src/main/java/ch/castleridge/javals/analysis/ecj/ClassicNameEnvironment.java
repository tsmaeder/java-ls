/**
 * Copyright 2026 by Castle Ridge Software GmbH
 *
 * Author: Thomas Mäder, Castle Ridge Software
 *
 */
package ch.castleridge.javals.analysis.ecj;

import org.eclipse.jdt.internal.compiler.env.INameEnvironment;
import org.eclipse.jdt.internal.compiler.env.NameEnvironmentAnswer;
import org.eclipse.jdt.internal.compiler.lookup.ModuleBinding;

/**
 * Plain {@link INameEnvironment} facade over {@link IndexNameEnvironment}.
 *
 * <p>Passing {@link IndexNameEnvironment} directly enables ECJ's module system
 * ({@code useModuleSystem}). Binary types from unnamed classpath jars then
 * corrupt {@code java.lang.Object} during superclass resolution. Until that
 * path is fully wired, the compiler uses this facade so lookups still go
 * through the module-aware env with {@link ModuleBinding#ANY}, while ECJ
 * stays in classic (non-module) mode.
 */
final class ClassicNameEnvironment implements INameEnvironment {
    private final IndexNameEnvironment delegate;

    ClassicNameEnvironment(IndexNameEnvironment delegate) {
        this.delegate = delegate;
    }

    IndexNameEnvironment delegate() {
        return delegate;
    }

    @Override
    public NameEnvironmentAnswer findType(char[][] compoundTypeName) {
        return delegate.findType(compoundTypeName, ModuleBinding.ANY);
    }

    @Override
    public NameEnvironmentAnswer findType(char[] typeName, char[][] packageName) {
        return delegate.findType(typeName, packageName, ModuleBinding.ANY);
    }

    @Override
    public boolean isPackage(char[][] parentPackageName, char[] packageName) {
        return delegate.isPackage(parentPackageName, packageName);
    }

    @Override
    public void cleanup() {
        // Per-namespace env outlives a single compile; do not clear here.
    }
}
