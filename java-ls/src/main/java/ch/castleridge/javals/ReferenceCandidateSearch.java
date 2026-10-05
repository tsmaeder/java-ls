/**
 * Copyright 2026 by Castle Ridge Software
 *
 * Author: Thomas Mäder, Castle Ridge Software
 *
 */
package ch.castleridge.javals;

import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

import ch.castleridge.javals.analysis.AttachedSource;
import ch.castleridge.javals.ast.SymbolKey;
import ch.castleridge.javals.classpath.ClasspathOrder;
import ch.castleridge.javals.indexing.bloom.BloomEntry;
import ch.castleridge.javals.indexing.index.Index;

/**
 * Bloom-filter candidate selection for cross-file reference and call-hierarchy
 * searches: identifier blooms, search scope, attached sources, and origin
 * visibility.
 */
final class ReferenceCandidateSearch {

    private ReferenceCandidateSearch() {}

    /**
     * @param queryUri URI of the buffer that resolved {@code key} (used for
     *        origin classpath visibility)
     * @param openDocumentUris always included regardless of bloom/scope
     * @param candidateCap max candidates after open docs + origin; {@code <= 0}
     *        means uncapped
     */
    static Result find(
            Index index,
            SymbolKey key,
            String queryUri,
            Map<String, String> sourceJarByBinaryJar,
            Function<String, ClasspathOrder> classPathFor,
            ReferenceSearchScope scope,
            Collection<String> openDocumentUris,
            int candidateCap) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(classPathFor, "classPathFor");
        ReferenceSearchScope searchScope = scope == null ? ReferenceSearchScope.DEFAULT : scope;
        Map<String, String> sourceJars = sourceJarByBinaryJar == null ? Map.of() : sourceJarByBinaryJar;

        Set<String> bloomCandidates = new LinkedHashSet<>();
        int visibilityFiltered = 0;
        if (index != null) {
            String simpleName = key.simpleName();
            String originContainer = ReferenceOriginVisibility.originContainer(
                    classPathFor.apply(queryUri), key.originResourceUri().orElse(null));
            Map<String, Boolean> visibleByContainer = new HashMap<>();
            for (BloomEntry entry : index.bloomFilters()) {
                String path = entry.resourcePath();
                if (path == null || !entry.filter().mightContain(simpleName)) continue;
                if (!searchScope.include(entry.sourceUri(), path)) continue;
                String candidateUri = null;
                if (path.endsWith(".java")) {
                    candidateUri = entry.resourceUri();
                } else if (path.endsWith(".class")) {
                    candidateUri = AttachedSource.javaUri(
                            entry.resourceUri(), entry.sourceUri(), sourceJars).orElse(null);
                }
                if (candidateUri == null) continue;
                if (originContainer != null) {
                    String probe = candidateUri;
                    boolean visible = visibleByContainer.computeIfAbsent(entry.sourceUri(), container ->
                            ReferenceOriginVisibility.candidateCanSeeOrigin(
                                    classPathFor.apply(probe), originContainer));
                    if (!visible) {
                        visibilityFiltered++;
                        continue;
                    }
                }
                bloomCandidates.add(candidateUri);
            }
        }

        int bloomHits = bloomCandidates.size();
        Set<String> candidates = new LinkedHashSet<>(bloomCandidates);
        if (openDocumentUris != null) {
            candidates.addAll(openDocumentUris);
        }
        key.originResourceUri().filter(u -> u.endsWith(".java")).ifPresent(candidates::add);

        int totalBeforeCap = candidates.size();
        boolean capped = false;
        if (candidateCap > 0 && candidates.size() > candidateCap) {
            Set<String> limited = new LinkedHashSet<>();
            if (openDocumentUris != null) {
                limited.addAll(openDocumentUris);
            }
            key.originResourceUri().filter(u -> u.endsWith(".java")).ifPresent(limited::add);
            for (String candidateUri : bloomCandidates) {
                if (limited.size() >= candidateCap) {
                    break;
                }
                limited.add(candidateUri);
            }
            candidates = limited;
            capped = true;
        }

        return new Result(
                Set.copyOf(candidates),
                bloomHits,
                visibilityFiltered,
                totalBeforeCap,
                capped,
                searchScope);
    }

    record Result(
            Set<String> candidates,
            int bloomHits,
            int visibilityFiltered,
            int totalBeforeCap,
            boolean capped,
            ReferenceSearchScope scope) {}
}
