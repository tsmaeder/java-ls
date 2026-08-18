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

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ForkJoinTask;
import java.util.concurrent.Future;

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
 * <p>{@link #scan} partitions inputs into class-file sources
 * ({@link JarInput}, {@link JrtInput}, …) and source-directory inputs
 * ({@link DirInput}), scanning each phase sequentially so callers can
 * report separate wall-clock timings.
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
    private final SourceIndexer sourceIndexer;
    private final BytecodeIndexer bytecodeIndexer;

    public Scanner() {
        this(SourceIndexer.javac(), BytecodeIndexer.asm());
    }

    public Scanner(SourceIndexer sourceIndexer) {
        this(sourceIndexer, BytecodeIndexer.asm());
    }

    public Scanner(SourceIndexer sourceIndexer, BytecodeIndexer bytecodeIndexer) {
        this(new ForkJoinPool(Math.max(2, Runtime.getRuntime().availableProcessors())),
                true, sourceIndexer, bytecodeIndexer);
    }

    public Scanner(ForkJoinPool pool) {
        this(pool, false, SourceIndexer.javac(), BytecodeIndexer.asm());
    }

    public Scanner(ForkJoinPool pool, SourceIndexer sourceIndexer) {
        this(pool, false, sourceIndexer, BytecodeIndexer.asm());
    }

    public Scanner(ForkJoinPool pool, SourceIndexer sourceIndexer, BytecodeIndexer bytecodeIndexer) {
        this(pool, false, sourceIndexer, bytecodeIndexer);
    }

    private Scanner(ForkJoinPool pool, boolean owns, SourceIndexer sourceIndexer,
                    BytecodeIndexer bytecodeIndexer) {
        this.pool = pool;
        this.ownsPool = owns;
        this.sourceIndexer = sourceIndexer == null ? SourceIndexer.javac() : sourceIndexer;
        this.bytecodeIndexer = bytecodeIndexer == null ? BytecodeIndexer.asm() : bytecodeIndexer;
    }

    /** Indexes {@code sources} into {@code into}; see {@link #scan}. */
    public List<Throwable> scanAll(Collection<InputSource> sources, Index into) {
        return scan(sources, into).failures();
    }

    /**
     * Indexes {@code sources} into {@code into}, scanning class-file
     * inputs first and source directories second so the returned
     * {@link ScanResult} carries separate wall-clock timings.
     */
    public ScanResult scan(Collection<InputSource> sources, Index into) {
        List<InputSource> classSources = new ArrayList<>();
        List<InputSource> sourceDirs = new ArrayList<>();
        for (InputSource src : sources) {
            if (src instanceof DirInput) {
                sourceDirs.add(src);
            } else {
                classSources.add(src);
            }
        }

        List<Throwable> failures = Collections.synchronizedList(new ArrayList<>());
        long t0 = System.nanoTime();
        long classFilesMs;
        long sourceFilesMs;
        try {
            long c0 = System.nanoTime();
            driveAll(classSources, into, failures);
            classFilesMs = (System.nanoTime() - c0) / 1_000_000L;

            long s0 = System.nanoTime();
            driveAll(sourceDirs, into, failures);
            sourceFilesMs = (System.nanoTime() - s0) / 1_000_000L;
        } finally {
            if (ownsPool) {
                pool.shutdown();
            }
        }
        long elapsedMs = (System.nanoTime() - t0) / 1_000_000L;
        return new ScanResult(List.copyOf(failures), classFilesMs, sourceFilesMs, elapsedMs);
    }

    private void driveAll(Collection<InputSource> sources, Index into, List<Throwable> failures) {
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
            for (InputSource src : sources) {
                sourceFutures.add(driverPool.submit(() -> scanOneSource(src, into, failures)));
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

    private void scanOneSource(InputSource src, Index into, List<Throwable> failures) {
        String srcUri = src.sourceUri();
        Index temp = new InMemoryIndex();
        List<ForkJoinTask<?>> indexTasks = new ArrayList<>();
        try {
            src.walk((relativePath, fileName, bytes) -> {
                ForkJoinTask<?> task = pool.submit(() -> {
                    try {
                        indexOne(sourceIndexer, bytecodeIndexer, relativePath, srcUri,
                                fileName, bytes.get(), temp);
                    } catch (Throwable t) {
                        failures.add(t);
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

    private static void indexOne(SourceIndexer sourceIndexer, BytecodeIndexer bytecodeIndexer,
                                 String relativePath, String sourceUri,
                                 String fileName, byte[] content, Index into) {
        if (fileName.endsWith(".class")) {
            bytecodeIndexer.index(relativePath, sourceUri, content, into);
        } else if (fileName.endsWith(".java")) {
            sourceIndexer.index(relativePath, sourceUri,
                    new String(content, StandardCharsets.UTF_8), into);
        }
    }
}
