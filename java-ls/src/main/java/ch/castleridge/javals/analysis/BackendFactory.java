/**
 * Copyright 2026 by Castle Ridge Software GmbH
 *
 * Author: Thomas Mäder, Castle Ridge Software
 *
 */
package ch.castleridge.javals.analysis;

import java.util.Map;

import ch.castleridge.javals.MbtService;
import ch.castleridge.javals.analysis.ecj.EcjDietSources;
import ch.castleridge.javals.analysis.ecj.EcjWorkspaceCompiler;
import ch.castleridge.javals.analysis.javac.JavacDietSources;
import ch.castleridge.javals.analysis.javac.JavacWorkspaceCompiler;

/**
 * Selects indexer/compiler implementations from configuration names.
 */
public final class BackendFactory {

    private BackendFactory() {}

    public static WorkspaceCompiler workspaceCompiler(String name) {
        return workspaceCompiler(name, new AstDeclarationLocator(JavacDietSources::lower),
                new AstDeclarationLocator(EcjDietSources::lower), Map.of(), null);
    }

    /**
     * The locators are owned by the caller so their parse caches survive
     * rebinding, which happens on every index change.
     */
    public static WorkspaceCompiler workspaceCompiler(String name,
                                                      AstDeclarationLocator javacLocator,
                                                      AstDeclarationLocator ecjLocator,
                                                      Map<String, String> sourceJarByBinaryJar) {
        return workspaceCompiler(name, javacLocator, ecjLocator, sourceJarByBinaryJar, null);
    }

    public static WorkspaceCompiler workspaceCompiler(String name,
                                                      AstDeclarationLocator javacLocator,
                                                      AstDeclarationLocator ecjLocator,
                                                      Map<String, String> sourceJarByBinaryJar,
                                                      MbtService mbtService) {
        if (name != null && name.trim().equalsIgnoreCase("ecj")) {
            return new EcjWorkspaceCompiler(ecjLocator, sourceJarByBinaryJar, mbtService);
        }
        return new JavacWorkspaceCompiler(javacLocator, sourceJarByBinaryJar);
    }

    /**
     * Update an existing compiler in place when possible (preserves ECJ
     * per-namespace lookup caches), otherwise allocate a fresh one.
     */
    public static WorkspaceCompiler rebind(WorkspaceCompiler current,
                                           String name,
                                           AstDeclarationLocator javacLocator,
                                           AstDeclarationLocator ecjLocator,
                                           Map<String, String> sourceJarByBinaryJar,
                                           MbtService mbtService) {
        boolean wantEcj = name != null && name.trim().equalsIgnoreCase("ecj");
        if (wantEcj && current instanceof EcjWorkspaceCompiler ecj) {
            ecj.setSourceJarByBinaryJar(sourceJarByBinaryJar);
            ecj.setMbtService(mbtService);
            ecj.invalidateLookupCaches();
            return ecj;
        }
        if (!wantEcj && current instanceof JavacWorkspaceCompiler) {
            // Javac compiler is stateless w.r.t. source jars beyond construction;
            // recreate so the new mapping is used.
            return new JavacWorkspaceCompiler(javacLocator, sourceJarByBinaryJar);
        }
        return workspaceCompiler(name, javacLocator, ecjLocator, sourceJarByBinaryJar, mbtService);
    }
}
