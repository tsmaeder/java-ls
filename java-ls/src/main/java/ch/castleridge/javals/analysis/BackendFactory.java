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
package ch.castleridge.javals.analysis;

import java.util.Map;

import ch.castleridge.javals.analysis.ecj.EcjDeclarationLocator;
import ch.castleridge.javals.analysis.ecj.EcjWorkspaceCompiler;
import ch.castleridge.javals.analysis.javac.JavacWorkspaceCompiler;
import ch.castleridge.javals.analysis.javac.SourceCache;
import ch.castleridge.javals.analysis.javac.SymbolLocator;

/**
 * Selects indexer/compiler implementations from configuration names.
 */
public final class BackendFactory {

    private BackendFactory() {}

    public static WorkspaceCompiler workspaceCompiler(String name) {
        return workspaceCompiler(name, new SymbolLocator(new SourceCache()),
                new EcjDeclarationLocator(), Map.of());
    }

    /**
     * The locators are owned by the caller so their parse caches survive
     * rebinding, which happens on every index change.
     */
    public static WorkspaceCompiler workspaceCompiler(String name,
                                                      SymbolLocator symbolLocator,
                                                      EcjDeclarationLocator declarationLocator,
                                                      Map<String, String> sourceJarByBinaryJar) {
        if (name != null && name.trim().equalsIgnoreCase("ecj")) {
            return new EcjWorkspaceCompiler(declarationLocator, sourceJarByBinaryJar);
        }
        return new JavacWorkspaceCompiler(symbolLocator, sourceJarByBinaryJar);
    }
}
