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

import ch.castleridge.javals.indexing.bytecode.ClassFileIndexer;
import ch.castleridge.javals.indexing.index.Index;
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
 * work - ASM parsing, javac tree analysis - is dispatched from the walker
 * onto a separate {@link ForkJoinPool} which can saturate every CPU.
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

    public Scanner() {
        this(new ForkJoinPool(Math.max(2, Runtime.getRuntime().availableProcessors())), true);
    }

    public Scanner(ForkJoinPool pool) {
        this(pool, false);
    }

    private Scanner(ForkJoinPool pool, boolean owns) {
        this.pool = pool;
        this.ownsPool = owns;
    }

    public List<Throwable> scanAll(Collection<InputSource> sources, Index into) {
        List<Throwable> failures = Collections.synchronizedList(new ArrayList<>());
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
            if (ownsPool) {
                pool.shutdown();
            }
        }
        return failures;
    }

    private void scanOneSource(InputSource src, Index into, List<Throwable> failures) {
        String srcUri = src.sourceUri();
        Index temp = new Index();
        List<ForkJoinTask<?>> indexTasks = new ArrayList<>();
        try {
            src.walk((relativePath, fileName, bytes) -> {
                ForkJoinTask<?> task = pool.submit(() -> {
                    try {
                        indexOne(relativePath, srcUri, fileName, bytes.get(), temp);
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

    private static void indexOne(String relativePath, String sourceUri, String fileName, byte[] content, Index into) {
        if (fileName.endsWith(".class")) {
            ClassFileIndexer.index(relativePath, sourceUri, content, into);
        } else if (fileName.endsWith(".java")) {
            SourceIndexer.index(relativePath, sourceUri,
                    new String(content, StandardCharsets.UTF_8), into);
        }
    }
}
