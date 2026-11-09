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

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ForkJoinTask;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import ch.castleridge.javals.indexing.FileIndexerRegistry;
import ch.castleridge.javals.indexing.bytecode.BytecodeIndexer;
import ch.castleridge.javals.indexing.index.Index;
import ch.castleridge.javals.indexing.index.InMemoryIndex;
import ch.castleridge.javals.indexing.source.SourceIndexer;

/**
 * Drives one or more {@link InputSource}s into a single {@link Index}.
 *
 * <p>Each input source is indexed as a unit: files are parsed in parallel
 * into a temporary {@link Index}, then merged into the target index via
 * {@link Index#addAll(Index)} so consumers see one change notification per
 * source.
 *
 * <p>Input sources are walked concurrently across a small driver pool so
 * that sequential {@link java.util.jar.JarFile} iteration does not
 * serialise the wall clock across hundreds of jars. File-level indexing
 * work - class-file parsing, javac tree analysis - is dispatched from the
 * walker onto a separate {@link ForkJoinPool} which can saturate every CPU.
 *
 * <p>{@link #scan} partitions inputs into three sequential phases —
 * JDK ({@link JrtInput}), source directories ({@link DirInput}), then
 * dependency jars ({@link JarInput}) — so callers can report separate
 * wall-clock timings and so project sources become available before
 * jars finish indexing.
 *
 * <p>Individual file failures are swallowed and collected so a single bad
 * class file cannot stop the scan.
 *
 * <p>Every emitted {@link ch.castleridge.javals.indexing.model.TypeEntry}
 * is stamped with the {@link InputSource#sourceUri()} of the source it
 * came from; the index keeps all duplicates. Walkers supply relative paths
 * only; full resource URIs are resolved on demand from sourceUri + path.
 */
public final class Scanner {

    private final ForkJoinPool pool;
    private final boolean ownsPool;
    private final FileIndexerRegistry indexers;

    public Scanner() {
        this(FileIndexerRegistry.defaults());
    }

    public Scanner(SourceIndexer sourceIndexer) {
        this(FileIndexerRegistry.of(sourceIndexer, BytecodeIndexer.asm()));
    }

    public Scanner(SourceIndexer sourceIndexer, BytecodeIndexer bytecodeIndexer) {
        this(FileIndexerRegistry.of(sourceIndexer, bytecodeIndexer));
    }

    public Scanner(FileIndexerRegistry indexers) {
        this(new ForkJoinPool(Math.max(2, Runtime.getRuntime().availableProcessors())),
                true, indexers);
    }

    public Scanner(ForkJoinPool pool) {
        this(pool, false, FileIndexerRegistry.defaults());
    }

    public Scanner(ForkJoinPool pool, SourceIndexer sourceIndexer) {
        this(pool, false, FileIndexerRegistry.of(sourceIndexer, BytecodeIndexer.asm()));
    }

    public Scanner(ForkJoinPool pool, SourceIndexer sourceIndexer, BytecodeIndexer bytecodeIndexer) {
        this(pool, false, FileIndexerRegistry.of(sourceIndexer, bytecodeIndexer));
    }

    public Scanner(ForkJoinPool pool, FileIndexerRegistry indexers) {
        this(pool, false, indexers);
    }

    private Scanner(ForkJoinPool pool, boolean owns, FileIndexerRegistry indexers) {
        this.pool = pool;
        this.ownsPool = owns;
        this.indexers = indexers == null ? FileIndexerRegistry.defaults() : indexers;
    }

    /** Indexes {@code sources} into {@code into}; see {@link #scan}. */
    public List<Throwable> scanAll(Collection<InputSource> sources, Index into) {
        return scan(sources, into).failures();
    }

    /**
     * Indexes {@code sources} into {@code into}, scanning JDK inputs,
     * then source directories, then jars so the returned
     * {@link ScanResult} carries separate wall-clock timings.
     */
    public ScanResult scan(Collection<InputSource> sources, Index into) {
        return scan(sources, into, null);
    }

    /**
     * Like {@link #scan(Collection, Index)} but reports per-file progress via
     * {@code progress} (may be {@code null}).
     */
    public ScanResult scan(Collection<InputSource> sources, Index into,
            ScanProgressListener progress) {
        PhasePartition phases = partition(sources);

        List<Throwable> failures = Collections.synchronizedList(new ArrayList<>());
        ConcurrentHashMap<String, AtomicLong> indexWorkNanos = new ConcurrentHashMap<>();
        long t0 = System.nanoTime();
        long jdkMs;
        long sourceFilesMs;
        long jarFilesMs;
        try {
            long j0 = System.nanoTime();
            driveAll(phases.jdk(), into, failures, progress, indexWorkNanos);
            jdkMs = (System.nanoTime() - j0) / 1_000_000L;

            long s0 = System.nanoTime();
            driveAll(phases.dirs(), into, failures, progress, indexWorkNanos);
            sourceFilesMs = (System.nanoTime() - s0) / 1_000_000L;

            long jar0 = System.nanoTime();
            driveAll(phases.jars(), into, failures, progress, indexWorkNanos);
            jarFilesMs = (System.nanoTime() - jar0) / 1_000_000L;
        } finally {
            if (ownsPool) {
                pool.shutdown();
            }
        }
        long elapsedMs = (System.nanoTime() - t0) / 1_000_000L;
        return new ScanResult(List.copyOf(failures), jdkMs, sourceFilesMs, jarFilesMs, elapsedMs,
                toWorkMs(indexWorkNanos));
    }

    /**
     * Groups inputs into scan phases (JDK → dirs → jars) and assigns
     * progress ordinals in that order. Package-visible for tests.
     */
    static PhasePartition partition(Collection<InputSource> sources) {
        List<InputSource> jdks = new ArrayList<>();
        List<InputSource> dirs = new ArrayList<>();
        List<InputSource> jars = new ArrayList<>();
        if (sources != null) {
            for (InputSource src : sources) {
                if (src == null) {
                    continue;
                }
                switch (src) {
                    case JrtInput jrt -> jdks.add(jrt);
                    case DirInput dir -> dirs.add(dir);
                    case JarInput jar -> jars.add(jar);
                }
            }
        }
        int total = jdks.size() + dirs.size() + jars.size();
        return new PhasePartition(
                toSlots(jdks, 0, total),
                toSlots(dirs, jdks.size(), total),
                toSlots(jars, jdks.size() + dirs.size(), total));
    }

    private static List<SourceSlot> toSlots(List<InputSource> sources, int startOrdinal, int total) {
        List<SourceSlot> slots = new ArrayList<>(sources.size());
        int ordinal = startOrdinal;
        for (InputSource src : sources) {
            ordinal++;
            slots.add(new SourceSlot(src, ordinal, total));
        }
        return slots;
    }

    record PhasePartition(List<SourceSlot> jdk, List<SourceSlot> dirs, List<SourceSlot> jars) {}

    private static Map<String, Long> toWorkMs(ConcurrentHashMap<String, AtomicLong> nanosByExt) {
        Map<String, Long> ms = new HashMap<>();
        for (Map.Entry<String, AtomicLong> e : nanosByExt.entrySet()) {
            ms.put(e.getKey(), e.getValue().get() / 1_000_000L);
        }
        return ms;
    }

    private void driveAll(Collection<SourceSlot> sources, Index into, List<Throwable> failures,
            ScanProgressListener progress, ConcurrentHashMap<String, AtomicLong> indexWorkNanos) {
        if (sources.isEmpty()) {
            return;
        }
        // Bound walker concurrency so we never hold more than a handful of
        // JarFiles open at once. Indexing itself is still parallel up to
        // every CPU via the shared ForkJoinPool.
        int drivers = Math.max(2, Math.min(8,
                Math.max(1, Runtime.getRuntime().availableProcessors() / 2)));
        ExecutorService driverPool = Executors.newFixedThreadPool(drivers, r -> {
            Thread t = new Thread(r, "scanner-driver");
            t.setDaemon(true);
            return t;
        });
        try {
            List<Future<?>> sourceFutures = new ArrayList<>(sources.size());
            for (SourceSlot slot : sources) {
                sourceFutures.add(driverPool.submit(
                        () -> scanOneSource(slot, into, failures, progress, indexWorkNanos)));
            }
            for (Future<?> f : sourceFutures) {
                try {
                    f.get();
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    failures.add(ie);
                } catch (ExecutionException ee) {
                    failures.add(ee.getCause() == null ? ee : ee.getCause());
                }
            }
        } finally {
            driverPool.shutdown();
        }
    }

    private void scanOneSource(SourceSlot slot, Index into, List<Throwable> failures,
            ScanProgressListener progress, ConcurrentHashMap<String, AtomicLong> indexWorkNanos) {
        InputSource src = slot.source();
        String srcUri = src.sourceUri();
        Index temp = new InMemoryIndex();
        List<ForkJoinTask<?>> indexTasks = new ArrayList<>();
        AtomicInteger filesInSource = new AtomicInteger();
        try {
            src.walk((relativePath, fileName, bytes) -> {
                ForkJoinTask<?> task = pool.submit(() -> {
                    long t0 = System.nanoTime();
                    try {
                        indexers.indexOne(relativePath, srcUri, fileName, bytes.get(), temp);
                        int n = filesInSource.incrementAndGet();
                        if (progress != null) {
                            progress.onFileIndexed(srcUri, slot.index(), slot.total(), n);
                        }
                    } catch (Throwable t) {
                        failures.add(t);
                    } finally {
                        String ext = FileIndexerRegistry.extensionOf(fileName);
                        if (!ext.isEmpty()) {
                            indexWorkNanos.computeIfAbsent(ext, k -> new AtomicLong())
                                    .addAndGet(System.nanoTime() - t0);
                        }
                    }
                });
                indexTasks.add(task);
            });
        } catch (Throwable t) {
            System.err.println("Skipping unreadable source " + srcUri + ": "
                    + t.getClass().getSimpleName() + ": " + t.getMessage());
            failures.add(t);
            return;
        }
        for (ForkJoinTask<?> task : indexTasks) {
            try {
                task.get();
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                failures.add(ie);
            } catch (ExecutionException ee) {
                failures.add(ee.getCause() == null ? ee : ee.getCause());
            }
        }
        into.addAll(temp);
    }

    /** Package-visible so phase-order tests can inspect ordinals. */
    record SourceSlot(InputSource source, int index, int total) {}
}
