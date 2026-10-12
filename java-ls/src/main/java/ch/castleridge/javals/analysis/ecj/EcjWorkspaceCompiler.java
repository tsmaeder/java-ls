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
 * ECJ-backed workspace compiler. Type-answer {@link IndexNameEnvironment}s are
 * per-compilation (or reused across reference candidates until the namespace
 * changes).
 */
public final class EcjWorkspaceCompiler implements WorkspaceCompiler {

    private static final String UNRESTRICTED_KEY = "";

    private final AstDeclarationLocator locator;
    private volatile Map<String, String> sourceJarByBinaryJar;
    private volatile MbtService mbtService;
    /** Remember last index/classpath per namespace for sticky-env reuse. */
    private final ConcurrentHashMap<String, ResolvedNamespace> namespaces = new ConcurrentHashMap<>();

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

    /** Update attached-source mapping without discarding lookup caches. */
    public void setSourceJarByBinaryJar(Map<String, String> sourceJarByBinaryJar) {
        this.sourceJarByBinaryJar = sourceJarByBinaryJar == null ? Map.of() : sourceJarByBinaryJar;
    }

    public void setMbtService(MbtService mbtService) {
        this.mbtService = mbtService;
    }

    /**
     * Drop cached namespace bindings (e.g. after mbt reload).
     */
    public void clearEnvironments() {
        namespaces.clear();
    }

    /** Clear per-namespace bindings after an index mutation. */
    public void invalidateLookupCaches() {
        namespaces.clear();
    }

    /**
     * Sticky name environment for reference workers: reuse while the candidate
     * stays in the same namespace, otherwise drop answers and start fresh.
     */
    public record StickyEnvironment(String namespaceId, IndexNameEnvironment environment) {
        public void release() {
            if (environment != null) {
                environment.cleanup();
            }
        }
    }

    /**
     * Resolve or replace a sticky environment for {@code uri}. When
     * {@code previous} is for a different namespace (or different index/
     * classpath), its answers are cleared and a new environment is created.
     */
    public StickyEnvironment environmentForReferences(String uri,
                                                      Index index,
                                                      ClasspathOrder classpath,
                                                      StickyEnvironment previous) {
        ResolvedNamespace resolved = resolveNamespace(uri, index, classpath);
        if (previous != null
                && previous.namespaceId().equals(resolved.namespaceId())
                && previous.environment().index() == resolved.index()
                && previous.environment().classpath() == resolved.classpath()) {
            return previous;
        }
        if (previous != null) {
            previous.release();
        }
        return new StickyEnvironment(
                resolved.namespaceId(),
                new IndexNameEnvironment(resolved.index(), resolved.classpath()));
    }

    /** Package-visible for tests: fresh env for a namespace id. */
    IndexNameEnvironment newEnvironment(String namespaceId, Index index, ClasspathOrder classpath) {
        ResolvedNamespace resolved = namespaceFor(namespaceId, index, classpath);
        return new IndexNameEnvironment(resolved.index(), resolved.classpath());
    }

    @Override
    public AnalysisSession analyze(String uri, CharSequence text, Index index, ClasspathOrder classpath) {
        ResolvedNamespace resolved = resolveNamespace(uri, index, classpath);
        IndexNameEnvironment environment = new IndexNameEnvironment(resolved.index(), resolved.classpath());
        return EcjAnalysisEngine.analyze(
                uri, text, index, classpath, locator, sourceJarByBinaryJar, environment, true);
    }

    /**
     * Analyze using a caller-owned environment (answers retained across calls
     * until the caller releases it). Used by reference search workers.
     */
    public AnalysisSession analyze(String uri,
                                   CharSequence text,
                                   Index index,
                                   ClasspathOrder classpath,
                                   IndexNameEnvironment environment) {
        return EcjAnalysisEngine.analyze(
                uri, text, index, classpath, locator, sourceJarByBinaryJar, environment, false);
    }

    private ResolvedNamespace resolveNamespace(String uri, Index index, ClasspathOrder classpath) {
        MbtService mbt = mbtService;
        if (mbt != null) {
            Optional<MbtService.Target> target = mbt.targetFor(uri);
            if (target.isPresent()) {
                MbtService.Target t = target.get();
                return namespaceFor(t.id(), index, t.classpath());
            }
        }
        ClasspathOrder order = classpath == null ? ClasspathOrder.UNRESTRICTED : classpath;
        return namespaceFor(UNRESTRICTED_KEY, index, order);
    }

    private ResolvedNamespace namespaceFor(String namespaceId, Index index, ClasspathOrder classpath) {
        String key = namespaceId == null ? UNRESTRICTED_KEY : namespaceId;
        ClasspathOrder order = classpath == null ? ClasspathOrder.UNRESTRICTED : classpath;
        return namespaces.compute(key, (k, existing) -> {
            if (existing != null && existing.index() == index && existing.classpath() == order) {
                return existing;
            }
            return new ResolvedNamespace(key, index, order);
        });
    }

    private record ResolvedNamespace(String namespaceId, Index index, ClasspathOrder classpath) {}
}
