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
package ch.castleridge.javals.indexing.cli;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryType;
import java.lang.management.MemoryUsage;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import ch.castleridge.javals.indexing.index.Index;
import ch.castleridge.javals.indexing.index.InMemoryIndex;
import ch.castleridge.javals.indexing.mbt.MbtInfo;
import ch.castleridge.javals.indexing.mbt.MbtJson;
import ch.castleridge.javals.indexing.scan.DirInput;
import ch.castleridge.javals.indexing.scan.InputSource;
import ch.castleridge.javals.indexing.scan.JarInput;
import ch.castleridge.javals.indexing.scan.JrtInput;
import ch.castleridge.javals.indexing.scan.Scanner;

/**
 * Minimal CLI driver around {@link Scanner}. Accepted tokens:
 *
 * <pre>
 *   --dir &lt;path&gt;       index a directory recursively
 *   --jar &lt;path&gt;       index a jar file
 *   --jrt [module]    index jrt:/ (every module if no name is given)
 *   --mbt &lt;path&gt;       read an mbt.json file and index every source/class/jar/jdk it references
 * </pre>
 *
 * After the scan finishes, prints the number of indexed types, any walker
 * errors, and the wall-clock time.
 */
public final class IndexDecompilerMain {

    public static void main(String[] args) {
        List<InputSource> sources = parseArgs(args);
        if (sources.isEmpty()) {
            System.err.println("Usage: --dir <path> | --jar <path> | --jrt [module] | --mbt <mbt.json>");
            System.exit(2);
        }

        resetPeakHeap();
        long baselineBytes = sampleUsedHeapBytes();

        Index index = new InMemoryIndex();
        Scanner scanner = new Scanner();
        long t0 = System.nanoTime();
        List<Throwable> failures = scanner.scanAll(sources, index);
        long elapsedMs = (System.nanoTime() - t0) / 1_000_000L;

        long retainedBytes = sampleUsedHeapBytes();
        long peakBytes = peakUsedHeapBytes();
        long indexBytes = Math.max(0L, retainedBytes - baselineBytes);
        int types = index.size();
        int entries = index.entryCount();

        System.out.println("Indexed types: " + types);
        System.out.println("Indexed entries: " + entries);
        System.out.println("Elapsed: " + elapsedMs + " ms");
        System.out.println("Memory (heap, JVM delta):");
        System.out.println("  baseline:  " + formatBytes(baselineBytes));
        System.out.println("  retained:  " + formatBytes(retainedBytes)
                + " (index delta: " + formatBytes(indexBytes) + ")");
        System.out.println("  peak:      " + formatBytes(peakBytes));
        if (types > 0) {
            System.out.println("  per type:  " + formatBytes(indexBytes / (long) types));
        }
        if (entries > 0) {
            System.out.println("  per entry: " + formatBytes(indexBytes / (long) entries));
        }

        HeapSizeEstimator est = new HeapSizeEstimator();
        long estimatedBytes = est.estimate(index);
        System.out.println("Memory (shape-based estimate):");
        System.out.println("  total:     " + formatBytes(estimatedBytes));
        if (types > 0) {
            System.out.println("  per type:  " + formatBytes(estimatedBytes / (long) types));
        }
        if (entries > 0) {
            System.out.println("  per entry: " + formatBytes(estimatedBytes / (long) entries));
        }
        System.out.println("  top contributors:");
        for (String row : est.topByBytes(15)) {
            System.out.println("    " + row);
        }

        if (!failures.isEmpty()) {
            System.out.println("Failures: " + failures.size());
            for (Throwable t : failures) {
                System.out.println("  " + t.getClass().getSimpleName() + ": " + t.getMessage());
            }
        }
    }

    /**
     * Force a GC cycle and return currently used heap bytes. The GC is a hint,
     * but repeating it stabilises the reading enough for a coarse estimate of
     * the memory retained by the index.
     */
    private static long sampleUsedHeapBytes() {
        for (int i = 0; i < 3; i++) {
            System.gc();
            try {
                Thread.sleep(20L);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        Runtime rt = Runtime.getRuntime();
        return rt.totalMemory() - rt.freeMemory();
    }

    /** Sum the peak-used bytes across all heap memory pools. */
    private static long peakUsedHeapBytes() {
        long peak = 0L;
        for (MemoryPoolMXBean pool : ManagementFactory.getMemoryPoolMXBeans()) {
            if (pool.getType() != MemoryType.HEAP) continue;
            MemoryUsage usage = pool.getPeakUsage();
            if (usage != null) peak += usage.getUsed();
        }
        return peak;
    }

    /** Reset per-pool peak counters so the reported peak reflects indexing only. */
    private static void resetPeakHeap() {
        for (MemoryPoolMXBean pool : ManagementFactory.getMemoryPoolMXBeans()) {
            if (pool.getType() != MemoryType.HEAP) continue;
            try {
                pool.resetPeakUsage();
            } catch (UnsupportedOperationException ignored) {
            }
        }
    }

    private static String formatBytes(long bytes) {
        if (bytes < 1024L) return bytes + " B";
        double kb = bytes / 1024.0;
        if (kb < 1024.0) return String.format("%.1f KiB", kb);
        double mb = kb / 1024.0;
        if (mb < 1024.0) return String.format("%.1f MiB", mb);
        return String.format("%.2f GiB", mb / 1024.0);
    }

    private static List<InputSource> parseArgs(String[] args) {
        List<InputSource> sources = new ArrayList<>();
        int i = 0;
        while (i < args.length) {
            String a = args[i];
            switch (a) {
                case "--dir" -> {
                    if (i + 1 >= args.length) fail("--dir requires a path");
                    sources.add(new DirInput(Path.of(args[++i])));
                }
                case "--jar" -> {
                    if (i + 1 >= args.length) fail("--jar requires a path");
                    sources.add(new JarInput(Path.of(args[++i])));
                }
                case "--jrt" -> {
                    sources.add(new JrtInput(Path.of(System.getProperty("java.home"))));
                }
                case "--mbt" -> {
                    if (i + 1 >= args.length) fail("--mbt requires a path");
                    Path mbtPath = Path.of(args[++i]);
                    try {
                        MbtInfo info = MbtJson.read(mbtPath);
                        Path workspace = mbtPath.toAbsolutePath().normalize().getParent();
                        sources.addAll(MbtJson.toInputSources(info, workspace));
                    } catch (IOException e) {
                        fail("Failed reading " + mbtPath + ": " + e.getMessage());
                    }
                }
                default -> fail("Unknown argument: " + a);
            }
            i++;
        }
        return sources;
    }

    private static void fail(String msg) {
        System.err.println(msg);
        System.exit(2);
    }
}
