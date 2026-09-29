/**
 * Copyright 2026 by Castle Ridge Software GmbH
 *
 * Author: Thomas Mäder, Castle Ridge Software
 *
 */
package ch.castleridge.javals;

import ch.castleridge.javals.classpath.ClasspathOrder;
import ch.castleridge.javals.indexing.model.ResourceUris;

/**
 * Whether a find-references candidate's compile classpath can see the
 * resolved symbol's declaration origin.
 */
final class ReferenceOriginVisibility {

    private ReferenceOriginVisibility() {}

    /**
     * Classpath container for {@code originResourceUri} as claimed by
     * {@code order}, or a jar/jrt/file probe when no entry matches. Returns
     * {@code null} when the origin is unknown (no filter).
     */
    static String originContainer(ClasspathOrder order, String originResourceUri) {
        if (originResourceUri == null || originResourceUri.isEmpty()) return null;
        String probe = ResourceUris.classpathContainer(originResourceUri);
        if (order != null) {
            String owned = order.owningSourceUri(probe);
            if (owned == null) {
                owned = order.owningSourceUri(originResourceUri);
            }
            if (owned != null) return owned;
        }
        return probe;
    }

    /**
     * {@code true} when {@code candidateClasspath} claims
     * {@code originContainer}, or when the container is unknown (no filter).
     */
    static boolean candidateCanSeeOrigin(ClasspathOrder candidateClasspath, String originContainer) {
        if (originContainer == null) return true;
        if (candidateClasspath == null) return false;
        return candidateClasspath.contains(originContainer);
    }
}
