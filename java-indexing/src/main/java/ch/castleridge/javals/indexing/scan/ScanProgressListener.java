/**
 * Copyright (c) 2026 IBM Corporation and others.
 *
 * Licensed under the MIT License.
 *
 * SPDX-License-Identifier: MIT
 *
 * Author: Angelo Zerr
 *
 */
package ch.castleridge.javals.indexing.scan;

/**
 * Callback to report progress during a {@link Scanner} scan.
 *
 * <p>Implementations receive periodic updates as individual
 * {@link InputSource}s are indexed. The {@code phase} string
 * describes the current scanning phase (e.g. "Indexing class files"
 * or "Indexing source files"), {@code current} is the number of
 * sources processed so far in that phase, and {@code total} is the
 * total number of sources in that phase.
 */
@FunctionalInterface
public interface ScanProgressListener {

    /**
     * Called when a scan phase makes progress.
     *
     * @param phase   human-readable label for the current phase
     * @param current number of sources completed so far in this phase
     * @param total   total number of sources in this phase
     */
    void onProgress(String phase, int current, int total);
}
