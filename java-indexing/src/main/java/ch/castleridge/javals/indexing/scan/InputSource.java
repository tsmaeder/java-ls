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
package ch.castleridge.javals.indexing.scan;

/**
 * Something the scanner can pull declarations from. Three shapes:
 * a filesystem tree, a jar file, or a subset of the JRT image.
 *
 * <p>Every input source exposes a {@link #sourceUri()} that uniquely
 * identifies it. That URI is stamped on every {@link
 * ch.castleridge.javals.indexing.model.TypeEntry} the scanner emits from
 * this source so downstream consumers (e.g. the file manager) can later
 * decide, given a list of input sources ordered by classpath priority,
 * which duplicate entry to prefer.
 *
 * <p>Walkers emit paths relative to this source (jar entry names, paths under
 * a directory root, paths inside the jrt filesystem). Full resource URIs are
 * resolved later via {@link ch.castleridge.javals.indexing.model.ResourceUris}.
 */
public sealed interface InputSource permits DirInput, JarInput, JrtInput {

    void walk(ResourceSink sink);

    /**
     * URI identifying this source as a whole (the jar file, the directory
     * root, the JRT module subset). Stable across runs so callers can build
     * classpath-priority maps keyed by it.
     */
    String sourceUri();
}
