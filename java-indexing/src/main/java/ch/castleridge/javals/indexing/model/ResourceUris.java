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

/**
 * Joins classpath {@code sourceUri} prefixes with per-entry relative paths.
 *
 * <p>Emitters stamp {@code sourceUri} as the resource-join prefix:
 * {@code jar:file:///lib.jar!/}, {@code jdk:///<home>!/}, or a directory URI
 * ending in {@code /}. Full resource URIs are then
 * {@code sourceUri + relativePath}.
 */
public final class ResourceUris {

    private ResourceUris() {}

    /**
     * Expand a relative path (or an absolute resource URI left as-is)
     * back to the full resource URI.
     */
    public static String resolve(String sourceUri, String stored) {
        if (stored == null) return null;
        if (isAbsoluteResource(stored)) return stored;
        if (sourceUri == null || sourceUri.isEmpty()) return stored;
        return sourceUri + stored;
    }

    /**
     * Package-root URI for a type or module-info resource: the directory
     * that would be the default-package folder if {@code resourceUri} were
     * a file tree.
     *
     * <p>Strips the filename, then strips a trailing {@code packageJvm + "/"}
     * when {@code packageJvm} is non-empty. For {@code module-info} (default
     * package), pass an empty {@code packageJvm}.
     *
     * <p>Examples:
     * <ul>
     *   <li>{@code jar:file:///lib.jar!/com/example/Foo.class} + {@code com/example}
     *       → {@code jar:file:///lib.jar!/}</li>
     *   <li>{@code jdk:///C:/jdk!/java.base/java/lang/Object.class} + {@code java/lang}
     *       → {@code jdk:///C:/jdk!/java.base/}</li>
     *   <li>{@code …/module-info.class} + {@code ""} → parent directory URI</li>
     * </ul>
     */
    public static String packageRootUri(String resourceUri, String packageJvm) {
        if (resourceUri == null || resourceUri.isEmpty()) return resourceUri;
        int lastSlash = resourceUri.lastIndexOf('/');
        if (lastSlash < 0) return resourceUri;
        // Directory of the file, including trailing '/'.
        String withoutFile = resourceUri.substring(0, lastSlash + 1);
        String pkg = packageJvm == null ? "" : packageJvm;
        if (pkg.isEmpty()) return withoutFile;
        String suffix = pkg + "/";
        if (withoutFile.endsWith(suffix)) {
            return withoutFile.substring(0, withoutFile.length() - suffix.length());
        }
        return withoutFile;
    }

    /**
     * Classpath container claimed by {@code resourceUri}: the prefix through
     * {@code !/} inclusive for jar/jdk URIs, or the URI itself for directory
     * resources. Matches {@link ch.castleridge.javals.indexing.scan.InputSource#sourceUri()}.
     */
    public static String classpathContainer(String resourceUri) {
        if (resourceUri == null || resourceUri.isEmpty()) return null;
        int bang = resourceUri.indexOf("!/");
        if (bang < 0) return resourceUri;
        return resourceUri.substring(0, bang + 2);
    }

    private static boolean isAbsoluteResource(String stored) {
        return stored.indexOf("://") >= 0 || stored.startsWith("jar:");
    }
}
