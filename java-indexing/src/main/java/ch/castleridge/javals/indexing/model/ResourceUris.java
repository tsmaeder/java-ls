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
package ch.castleridge.javals.indexing.model;

import java.net.URI;
import java.nio.file.Path;

/**
 * Compacts resource URIs stored on index entries by separating the shared
 * classpath {@code sourceUri} prefix from the per-entry relative path.
 *
 * <p>Jar and jrt walkers emit one long resource URI per class that repeats
 * the same jar/JDK path thousands of times. Directory inputs similarly
 * repeat the source-root URI. Storing only the relative entry
 * (e.g. {@code com/example/Foo.class}) removes that duplicated prefix from
 * the retained heap;
 * {@link #resolve(String, String)} rebuilds the full URI on demand.
 *
 * <p>When a resource URI cannot be reconstructed losslessly from
 * {@code sourceUri} + relative path (common for synthetic {@code index:///}
 * test URIs), {@link #compact(String, String)} keeps the original string
 * unchanged.
 */
public final class ResourceUris {

    private ResourceUris() {}

    /**
     * Compact {@code resourceUri} for storage alongside {@code sourceUri}.
     * Returns a relative path when round-trippable; otherwise the original
     * {@code resourceUri}.
     */
    public static String compact(String resourceUri, String sourceUri) {
        if (resourceUri == null) return null;
        // Already-compact relative paths (or opaque non-URI tokens) need no work.
        if (!isAbsoluteResource(resourceUri)) {
            return resourceUri;
        }
        if (sourceUri == null || sourceUri.isEmpty()) return resourceUri;
        String relative = relativePath(resourceUri, sourceUri);
        if (relative == null || relative.isEmpty()) return resourceUri;
        String rebuilt = join(sourceUri, relative);
        if (!resourceUri.equals(rebuilt)) return resourceUri;
        return relative;
    }

    /**
     * Expand a compact relative path (or an absolute resource URI left
     * uncompacted) back to the full resource URI.
     */
    public static String resolve(String sourceUri, String stored) {
        if (stored == null) return null;
        if (isAbsoluteResource(stored)) return stored;
        if (sourceUri == null || sourceUri.isEmpty()) return stored;
        return join(sourceUri, stored);
    }

    /**
     * Entry path inside a jar/jrt ({@code !/}-split) or relative to a
     * directory {@code sourceUri}. Returns {@code null} when no relative
     * form can be derived.
     */
    public static String relativePath(String resourceUri, String sourceUri) {
        if (resourceUri == null || resourceUri.isEmpty()) return null;
        int bang = resourceUri.indexOf("!/");
        if (bang >= 0) {
            return resourceUri.substring(bang + 2);
        }
        if (sourceUri != null && !sourceUri.isEmpty()
                && sourceUri.endsWith("/")
                && resourceUri.startsWith(sourceUri)) {
            return resourceUri.substring(sourceUri.length());
        }
        if (sourceUri != null
                && resourceUri.startsWith("file:")
                && sourceUri.startsWith("file:")) {
            try {
                Path resource = Path.of(URI.create(resourceUri));
                Path source = Path.of(URI.create(sourceUri));
                if (resource.startsWith(source)) {
                    String rel = source.relativize(resource).toString().replace('\\', '/');
                    return rel.isEmpty() ? null : rel;
                }
            } catch (RuntimeException ignored) {
            }
        }
        return null;
    }

    private static boolean isAbsoluteResource(String stored) {
        return stored.indexOf("://") >= 0 || stored.startsWith("jar:");
    }

    private static String join(String sourceUri, String relative) {
        if (sourceUri.startsWith("jrt:")) {
            return sourceUri + "!/" + relative;
        }
        if (sourceUri.startsWith("file:")) {
            if (looksLikeArchiveFile(sourceUri)) {
                return "jar:" + sourceUri + "!/" + relative;
            }
            return joinDirectory(sourceUri, relative);
        }
        if (sourceUri.endsWith("/")) {
            return sourceUri + relative;
        }
        return sourceUri + "!/" + relative;
    }

    private static String joinDirectory(String sourceUri, String relative) {
        if (sourceUri.endsWith("/")) {
            return sourceUri + relative;
        }
        return sourceUri + "/" + relative;
    }

    private static boolean looksLikeArchiveFile(String sourceUri) {
        int query = sourceUri.indexOf('?');
        int frag = sourceUri.indexOf('#');
        int end = sourceUri.length();
        if (query >= 0) end = Math.min(end, query);
        if (frag >= 0) end = Math.min(end, frag);
        String path = sourceUri.substring(0, end).toLowerCase(java.util.Locale.ROOT);
        return path.endsWith(".jar") || path.endsWith(".jmod") || path.endsWith(".zip");
    }
}
