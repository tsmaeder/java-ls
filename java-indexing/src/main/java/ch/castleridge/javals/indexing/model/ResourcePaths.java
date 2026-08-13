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
 * Derives the usual {@code resourcePath} for a type from its JVM binary
 * name, so the codec can omit the path from storage when it matches.
 *
 * <p>Classfiles live at {@code jvmOwnerName + ".class"}. Source types live
 * in the outermost top-level type's {@code .java} file
 * ({@code pkg/Foo$Bar$Baz} → {@code pkg/Foo.java}). Paths that differ
 * (secondary top-level types, multi-release layouts, …) are stored
 * explicitly.
 */
public final class ResourcePaths {

    public enum Kind {
        CLASSFILE,
        SOURCE
    }

    private ResourcePaths() {}

    /**
     * Truncate a JVM binary name at the first {@code $} so nested types
     * map back to their outermost top-level type.
     */
    public static String outermostJvmName(String jvmOwnerName) {
        if (jvmOwnerName == null) return null;
        int dollar = jvmOwnerName.indexOf('$');
        return dollar < 0 ? jvmOwnerName : jvmOwnerName.substring(0, dollar);
    }

    /** Default archive/source-root-relative path for {@code jvmOwnerName}. */
    public static String defaultPath(String jvmOwnerName, Kind kind) {
        if (jvmOwnerName == null) return null;
        return switch (kind) {
            case CLASSFILE -> jvmOwnerName + ".class";
            case SOURCE -> outermostJvmName(jvmOwnerName) + ".java";
        };
    }

    /**
     * Path to encode: {@code null} when {@code path} equals the default for
     * {@code jvmOwnerName}/{@code kind}; otherwise {@code path} unchanged.
     */
    public static String forStorage(String path, String jvmOwnerName, Kind kind) {
        if (path == null) return null;
        String def = defaultPath(jvmOwnerName, kind);
        return path.equals(def) ? null : path;
    }

    /**
     * Expand a stored (possibly omitted) path back to the effective
     * resource path consumers expect.
     */
    public static String effective(String stored, String jvmOwnerName, Kind kind) {
        return stored != null ? stored : defaultPath(jvmOwnerName, kind);
    }
}
