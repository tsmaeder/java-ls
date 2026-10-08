/**
 * Copyright 2026 by Castle Ridge Software GmbH
 *
 * Author: Thomas Mäder, Castle Ridge Software
 *
 */
package ch.castleridge.javals.analysis.ecj;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import ch.castleridge.javals.MbtService;
import ch.castleridge.javals.analysis.AnalysisSession;
import ch.castleridge.javals.analysis.AstDeclarationLocator;
import ch.castleridge.javals.analysis.WorkspaceCompiler;
import ch.castleridge.javals.classpath.ClasspathOrder;
import ch.castleridge.javals.indexing.index.Index;

/**
 * ECJ-backed workspace compiler using a reusable
 * {@link IndexNameEnvironment} per mbt namespace.
 */
public final class EcjWorkspaceCompiler implements WorkspaceCompiler {

    private static final String UNRESTRICTED_KEY = "";

    private final AstDeclarationLocator locator;
    private volatile Map<String, String> sourceJarByBinaryJar;
    private volatile MbtService mbtService;
    private final ConcurrentHashMap<String, IndexNameEnvironment> environments = new ConcurrentHashMap<>();

    public EcjWorkspaceCompiler() {
        this(new AstDeclarationLocator(EcjDietSources::lower), Map.of(), null);
    }

    /**
     * @param sourceJarByBinaryJar sources archive per binary classpath
     *        container, keyed as the indexer stamps container URIs on its
     *        entries; without it, declarations in jars and the JDK have no
     *        navigable source
     */
    public EcjWorkspaceCompiler(AstDeclarationLocator locator,
                                Map<String, String> sourceJarByBinaryJar) {
        this(locator, sourceJarByBinaryJar, null);
    }

    public EcjWorkspaceCompiler(AstDeclarationLocator locator,
                                Map<String, String> sourceJarByBinaryJar,
                                MbtService mbtService) {
        this.locator = locator == null ? new AstDeclarationLocator(EcjDietSources::lower) : locator;
        this.sourceJarByBinaryJar = sourceJarByBinaryJar == null ? Map.of() : sourceJarByBinaryJar;
        this.mbtService = mbtService;
    }

    /** Update attached-source mapping without discarding cached environments. */
    public void setSourceJarByBinaryJar(Map<String, String> sourceJarByBinaryJar) {
        this.sourceJarByBinaryJar = sourceJarByBinaryJar == null ? Map.of() : sourceJarByBinaryJar;
    }

    public void setMbtService(MbtService mbtService) {
        this.mbtService = mbtService;
    }

    /**
     * Drop cached environments (e.g. after mbt reload). Answer caches are also
     * cleared when the index mutates via {@link #invalidateAnswerCaches()}.
     */
    public void clearEnvironments() {
        for (IndexNameEnvironment env : environments.values()) {
            env.cleanup();
        }
        environments.clear();
    }

    /** Clear per-type/module answer caches after an index mutation. */
    public void invalidateAnswerCaches() {
        for (IndexNameEnvironment env : environments.values()) {
            env.cleanup();
        }
    }

    /** Package-visible for tests: env reused for a namespace id (empty = unrestricted). */
    IndexNameEnvironment environmentFor(String namespaceId, Index index, ClasspathOrder classpath) {
        String key = namespaceId == null ? UNRESTRICTED_KEY : namespaceId;
        return environments.compute(key, (k, existing) -> {
            if (existing != null && existing.index() == index && existing.classpath() == classpath) {
                return existing;
            }
            if (existing != null) {
                existing.cleanup();
            }
            return new IndexNameEnvironment(index, classpath);
        });
    }

    @Override
    public AnalysisSession analyze(String uri, CharSequence text, Index index, ClasspathOrder classpath) {
        IndexNameEnvironment environment = resolveEnvironment(uri, index, classpath);
        return EcjAnalysisEngine.analyze(uri, text, index, classpath, locator, sourceJarByBinaryJar, environment);
    }

    private IndexNameEnvironment resolveEnvironment(String uri, Index index, ClasspathOrder classpath) {
        MbtService mbt = mbtService;
        if (mbt != null) {
            Optional<MbtService.Target> target = mbt.targetFor(uri);
            if (target.isPresent()) {
                MbtService.Target t = target.get();
                return environmentFor(t.id(), index, t.classpath());
            }
        }
        ClasspathOrder order = classpath == null ? ClasspathOrder.UNRESTRICTED : classpath;
        return environmentFor(UNRESTRICTED_KEY, index, order);
    }
}
