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

import java.util.List;

/**
 * Outcome of a {@link Scanner#scan} pass, including wall-clock timings
 * split by input kind.
 *
 * <p>{@link #classFilesMs()} covers {@link JarInput} / {@link JrtInput}
 * (and any other non-{@link DirInput} source). {@link #sourceFilesMs()}
 * covers {@link DirInput} trees. Phases run sequentially so the numbers
 * are non-overlapping wall-clock times; {@link #elapsedMs()} is the
 * end-to-end scan duration.
 *
 * @param failures      per-file / per-source errors collected during the scan
 * @param classFilesMs  wall-clock ms spent indexing class-file inputs
 * @param sourceFilesMs wall-clock ms spent indexing source-directory inputs
 * @param elapsedMs     wall-clock ms for the whole scan
 */
public record ScanResult(
        List<Throwable> failures,
        long classFilesMs,
        long sourceFilesMs,
        long elapsedMs) {
}
