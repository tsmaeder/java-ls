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
package ch.castleridge.javals.analysis.ecj;

import java.util.Map;

import ch.castleridge.javals.analysis.AnalysisSession;
import ch.castleridge.javals.analysis.AstDeclarationLocator;
import ch.castleridge.javals.analysis.WorkspaceCompiler;
import ch.castleridge.javals.classpath.ClasspathOrder;
import ch.castleridge.javals.indexing.index.Index;

/**
 * ECJ-backed workspace compiler using {@link IndexNameEnvironment} instead
 * of javac's IndexFileManager / IndexClassReader.
 */
public final class EcjWorkspaceCompiler implements WorkspaceCompiler {

    private final AstDeclarationLocator locator;
    private final Map<String, String> sourceJarByBinaryJar;

    public EcjWorkspaceCompiler() {
        this(new AstDeclarationLocator(EcjDietSources::lower), Map.of());
    }

    /**
     * @param sourceJarByBinaryJar sources archive per binary classpath
     *        container, keyed as the indexer stamps container URIs on its
     *        entries; without it, declarations in jars and the JDK have no
     *        navigable source
     */
    public EcjWorkspaceCompiler(AstDeclarationLocator locator,
                                Map<String, String> sourceJarByBinaryJar) {
        this.locator = locator == null ? new AstDeclarationLocator(EcjDietSources::lower) : locator;
        this.sourceJarByBinaryJar = sourceJarByBinaryJar == null ? Map.of() : sourceJarByBinaryJar;
    }

    @Override
    public AnalysisSession analyze(String uri, CharSequence text, Index index, ClasspathOrder classpath) {
        return EcjAnalysisEngine.analyze(uri, text, index, classpath, locator, sourceJarByBinaryJar);
    }
}
