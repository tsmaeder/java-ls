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
 * Aggregate file counts and sizes collected while scanning.
 *
 * @param sourceFileCount number of {@code .java} resources walked
 * @param classFileBytes  sum of known sizes of {@code .class} resources
 */
public record ScanStats(int sourceFileCount, long classFileBytes) {
    public static final ScanStats EMPTY = new ScanStats(0, 0L);
}
