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
package ch.castleridge.javals;

import java.util.Locale;

/**
 * Which indexed containers find-references may scan beyond workspace sources.
 *
 * <p>Workspace {@code .java} files are always eligible. Dependency jars and
 * the JDK are included only when the matching flag is set. Open documents
 * are added by the caller and are not filtered here.
 */
record ReferenceSearchScope(boolean inJars, boolean inJdk) {

    static final ReferenceSearchScope DEFAULT = new ReferenceSearchScope(false, false);

    /**
     * @param sourceUri classpath container stamped on the bloom entry
     *        (a source root, a jar, or {@code jrt:///...})
     * @param resourcePath compact entry path ({@code com/example/Foo.java}
     *        or {@code java/lang/Object.class})
     */
    boolean include(String sourceUri, String resourcePath) {
        if (resourcePath == null) return false;
        if (!resourcePath.endsWith(".java") && !resourcePath.endsWith(".class")) return false;
        return includeContainer(sourceUri);
    }

    private boolean includeContainer(String sourceUri) {
        if (sourceUri == null || sourceUri.isEmpty()) {
            return true;
        }
        if (sourceUri.startsWith("jrt:")) {
            return inJdk;
        }
        if (isArchive(sourceUri)) {
            return inJars;
        }
        return true;
    }

    private static boolean isArchive(String sourceUri) {
        if (sourceUri.startsWith("jar:")) return true;
        int query = sourceUri.indexOf('?');
        int fragment = sourceUri.indexOf('#');
        int end = sourceUri.length();
        if (query >= 0) end = Math.min(end, query);
        if (fragment >= 0) end = Math.min(end, fragment);
        String path = sourceUri.substring(0, end).toLowerCase(Locale.ROOT);
        return path.endsWith(".jar") || path.endsWith(".jmod") || path.endsWith(".zip");
    }
}
