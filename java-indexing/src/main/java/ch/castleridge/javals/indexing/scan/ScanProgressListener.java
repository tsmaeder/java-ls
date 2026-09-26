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
 * Optional callback fired by {@link Scanner} after each file is indexed.
 * Implementations must be thread-safe: sources and files are processed
 * concurrently.
 */
@FunctionalInterface
public interface ScanProgressListener {

    /**
     * @param sourceUri     {@link InputSource#sourceUri()} of the file's source
     * @param sourceIndex   1-based ordinal of that source among all sources in the scan
     * @param sourceCount   total number of input sources in the scan
     * @param filesInSource number of files indexed so far in this source
     */
    void onFileIndexed(String sourceUri, int sourceIndex, int sourceCount, int filesInSource);
}
