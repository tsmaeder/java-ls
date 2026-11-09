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
import java.util.Map;

/**
 * Outcome of a {@link Scanner#scan} pass, including wall-clock timings
 * split by input kind and summed per-extension indexing work time.
 *
 * <p>{@link #jdkMs()} covers {@link JrtInput}, {@link #sourceFilesMs()}
 * covers {@link DirInput} trees, and {@link #jarFilesMs()} covers
 * {@link JarInput} (and any other non-JDK/non-dir source). Phases run
 * sequentially (JDK → sources → jars) so the numbers are non-overlapping
 * wall-clock times; {@link #elapsedMs()} is the end-to-end scan duration.
 *
 * <p>{@link #indexWorkMsByExtension()} is the sum of per-file
 * {@code indexOne} task time by extension. Under parallel indexing these
 * values can exceed the corresponding wall-clock phase times.
 *
 * @param failures                 per-file / per-source errors collected during the scan
 * @param jdkMs                    wall-clock ms spent indexing JRT / JDK inputs
 * @param sourceFilesMs            wall-clock ms spent indexing source-directory inputs
 * @param jarFilesMs               wall-clock ms spent indexing jar inputs
 * @param elapsedMs                wall-clock ms for the whole scan
 * @param indexWorkMsByExtension   summed {@code indexOne} work ms keyed by extension
 */
public record ScanResult(
        List<Throwable> failures,
        long jdkMs,
        long sourceFilesMs,
        long jarFilesMs,
        long elapsedMs,
        Map<String, Long> indexWorkMsByExtension) {

    public ScanResult {
        failures = failures == null ? List.of() : List.copyOf(failures);
        indexWorkMsByExtension = indexWorkMsByExtension == null
                ? Map.of()
                : Map.copyOf(indexWorkMsByExtension);
    }
}
