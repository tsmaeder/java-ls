package ch.castleridge.javals.indexing.scan;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
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
 * <p>Input sources (typically one per jar / source directory / jrt module)
 * are walked concurrently across a small driver pool so that sequential
 * {@link java.util.jar.JarFile} iteration does not serialise the wall clock
 * across hundreds of jars. File-level indexing work - ASM parsing, javac
 * tree analysis - is then dispatched from the walker onto a separate
 * {@link ForkJoinPool} which can saturate every CPU.
 *
 * <p>Individual file failures are swallowed and collected so a single bad
 * class file cannot stop the scan.
 *
 * <p>Every emitted {@link ch.castleridge.javals.indexing.model.TypeEntry}
 * is stamped with the {@link InputSource#sourceUri()} of the source it
 * came from; the index keeps all duplicates.
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

    public List<Throwable> scanAll(List<InputSource> sources, Index into) {
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
            List<Future<?>> walkFutures = new ArrayList<>(sources.size());
            List<ForkJoinTask<?>> indexTasks = Collections.synchronizedList(new ArrayList<>());
            for (InputSource src : sources) {
                URI srcUri = src.sourceUri();
                walkFutures.add(driverPool.submit(() -> {
                    try {
                        src.walk((uri, fileName, bytes) -> {
                            ForkJoinTask<?> task = pool.submit(() -> {
                                try {
                                    indexOne(uri, srcUri, fileName, bytes.get(), into);
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
                    }
                }));
            }
            // Let the walkers finish enqueueing before we join the index
            // tasks, otherwise new tasks may still be added while we iterate.
            for (Future<?> f : walkFutures) {
                try {
                    f.get();
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    failures.add(ie);
                } catch (ExecutionException ee) {
                    failures.add(ee.getCause() == null ? ee : ee.getCause());
                }
            }
            // Snapshot before draining to decouple from any late walker.
            ForkJoinTask<?>[] snapshot;
            synchronized (indexTasks) {
                snapshot = indexTasks.toArray(new ForkJoinTask<?>[0]);
            }
            for (ForkJoinTask<?> t : snapshot) {
                try {
                    t.get();
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

    private static void indexOne(URI uri, URI sourceUri, String fileName, byte[] content, Index into) {
        if (fileName.endsWith(".class")) {
            ClassFileIndexer.index(uri, sourceUri, content, into);
        } else if (fileName.endsWith(".java")) {
            SourceIndexer.index(uri, sourceUri, new String(content, StandardCharsets.UTF_8), into);
        }
    }
}
