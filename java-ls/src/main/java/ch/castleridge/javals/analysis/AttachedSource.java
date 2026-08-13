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
package ch.castleridge.javals.analysis;

import java.net.URI;
import java.util.Map;
import java.util.Optional;

import ch.castleridge.javals.indexing.model.ResourcePaths;

/**
 * Maps the resource an index entry was read from to the source a client can
 * navigate to.
 *
 * <p>Source entries - workspace files, {@code .java} entries of an archive -
 * are already navigable. A {@code .class} entry only becomes navigable when
 * its container has an attached sources archive (a dependency's
 * {@code -sources.jar}, the JDK's {@code src.zip}) registered under the
 * container's URI. Nested types compile to {@code Outer$Inner.class} but ship
 * in {@code Outer.java}, so the entry path is truncated to the outermost type.
 *
 * <p>Class files are never a navigation target: without attached source the
 * result is empty. Handing a client a {@code .class} or {@code jrt:} URI only
 * makes it show bytecode or fail to open the document.
 */
public final class AttachedSource {

    private AttachedSource() {}

    /**
     * @param resourceUri full resource URI of an indexed type, e.g.
     *        {@code jar:file:///lib/dep.jar!/com/example/Foo.class}
     * @param containerUri URI of the classpath container the type came from
     *        ({@code TypeEntry#sourceUri()}), the key under which its
     *        sources archive is registered
     */
    public static Optional<String> javaUri(String resourceUri,
                                           String containerUri,
                                           Map<String, String> sourceJarByBinaryJar) {
        if (resourceUri == null || resourceUri.isBlank()) return Optional.empty();
        if (!resourceUri.endsWith(".class")) return Optional.of(resourceUri);

        String sourcesArchive = sourceJarByBinaryJar == null ? null : sourceJarByBinaryJar.get(containerUri);
        if (sourcesArchive == null || sourcesArchive.isBlank()) return Optional.empty();

        int separator = resourceUri.indexOf("!/");
        if (separator < 0 || separator + 2 >= resourceUri.length()) return Optional.empty();
        String classEntry = resourceUri.substring(separator + 2);
        try {
            return Optional.of("jar:" + URI.create(sourcesArchive) + "!/" + outerClassJavaEntry(classEntry));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    /** Companion {@code .java} entry of a {@code .class} archive entry. */
    public static String outerClassJavaEntry(String classEntryPath) {
        String withoutExtension = classEntryPath.substring(0, classEntryPath.length() - ".class".length());
        return ResourcePaths.defaultPath(withoutExtension, ResourcePaths.Kind.SOURCE);
    }
}
