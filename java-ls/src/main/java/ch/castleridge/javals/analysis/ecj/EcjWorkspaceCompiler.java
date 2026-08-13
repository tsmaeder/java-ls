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

import java.net.URI;
import java.util.Map;

import ch.castleridge.javals.analysis.AnalysisSession;
import ch.castleridge.javals.analysis.WorkspaceCompiler;
import ch.castleridge.javals.classpath.ClasspathOrder;
import ch.castleridge.javals.indexing.index.Index;

/**
 * ECJ-backed workspace compiler using {@link IndexNameEnvironment} instead
 * of javac's IndexFileManager / IndexClassReader.
 */
public final class EcjWorkspaceCompiler implements WorkspaceCompiler {

    private final EcjDeclarationLocator declarationLocator;
    private final Map<String, String> sourceJarByBinaryJar;

    public EcjWorkspaceCompiler() {
        this(new EcjDeclarationLocator(), Map.of());
    }

    /**
     * @param sourceJarByBinaryJar sources archive per binary classpath
     *        container, keyed as the indexer stamps container URIs on its
     *        entries; without it, declarations in jars and the JDK have no
     *        navigable source
     */
    public EcjWorkspaceCompiler(EcjDeclarationLocator declarationLocator,
                                Map<String, String> sourceJarByBinaryJar) {
        this.declarationLocator = declarationLocator == null ? new EcjDeclarationLocator() : declarationLocator;
        this.sourceJarByBinaryJar = sourceJarByBinaryJar == null ? Map.of() : sourceJarByBinaryJar;
    }

    @Override
    public AnalysisSession analyze(URI uri, CharSequence text, Index index, ClasspathOrder classpath) {
        return EcjAnalysisEngine.analyze(uri, text, index, classpath, declarationLocator, sourceJarByBinaryJar);
    }
}
