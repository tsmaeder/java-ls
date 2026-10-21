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

import java.util.Map;
import java.util.TreeSet;

/**
 * Aggregate file counts and sizes collected while scanning.
 *
 * @param fileCounts     count of indexable resources walked, keyed by
 *                       extension (e.g. {@code ".java"}, {@code ".kt"},
 *                       {@code ".class"})
 * @param classFileBytes sum of known sizes of {@code .class} resources
 */
public record ScanStats(Map<String, Integer> fileCounts, long classFileBytes) {
    public static final ScanStats EMPTY = new ScanStats(Map.of(), 0L);

    public ScanStats {
        fileCounts = fileCounts == null ? Map.of() : Map.copyOf(fileCounts);
    }

    /** Total count of source-like files ({@code .java}, {@code .kt}, …). */
    public int sourceFileCount() {
        int n = 0;
        for (Map.Entry<String, Integer> e : fileCounts.entrySet()) {
            String ext = e.getKey();
            if (ext != null && !".class".equals(ext)) {
                n += e.getValue() == null ? 0 : e.getValue();
            }
        }
        return n;
    }

    public int fileCount(String extension) {
        if (extension == null) return 0;
        Integer n = fileCounts.get(extension);
        return n == null ? 0 : n;
    }

    /**
     * Formats per-extension counts with average per-file indexing time derived
     * from summed parallel task time. Averages are comparable across types;
     * they are not a partition of wall-clock scan time.
     *
     * <p>Example: {@code .java: 12 files, avg 2 ms; .kt: 3 files, avg 40 ms}.
     */
    public String formatWithWorkMs(Map<String, Long> indexWorkMsByExtension) {
        Map<String, Long> workMs = indexWorkMsByExtension == null
                ? Map.of()
                : indexWorkMsByExtension;
        TreeSet<String> keys = new TreeSet<>();
        keys.addAll(fileCounts.keySet());
        keys.addAll(workMs.keySet());
        if (keys.isEmpty()) {
            return "(none)";
        }
        StringBuilder sb = new StringBuilder();
        for (String ext : keys) {
            if (sb.length() > 0) {
                sb.append("; ");
            }
            int count = fileCount(ext);
            sb.append(ext).append(": ").append(count).append(" files");
            Long ms = workMs.get(ext);
            if (ms != null && count > 0) {
                sb.append(", avg ").append(ms / count).append(" ms");
            }
        }
        return sb.toString();
    }
}
