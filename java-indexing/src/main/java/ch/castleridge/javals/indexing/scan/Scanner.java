package ch.castleridge.javals.indexing.scan;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ForkJoinTask;

import ch.castleridge.javals.indexing.bytecode.ClassFileIndexer;
import ch.castleridge.javals.indexing.index.Index;
import ch.castleridge.javals.indexing.source.SourceIndexer;

/**
 * Drives one or more {@link InputSource}s into a single {@link Index}.
 *
 * <p>Each input source is walked sequentially, but file-level indexing work
 * is offloaded to a {@link ForkJoinPool} so big sources (the whole JRT, big
 * jars) spread across cores. Individual file failures are swallowed and
 * collected so a single bad class file cannot stop the scan.
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
        List<Throwable> failures = new ArrayList<>();
        try {
            List<ForkJoinTask<?>> tasks = new ArrayList<>();
            for (InputSource src : sources) {
                URI srcUri = src.sourceUri();
                src.walk((uri, fileName, bytes) -> {
                    ForkJoinTask<?> task = pool.submit(() -> {
                        try {
                            indexOne(uri, srcUri, fileName, bytes.get(), into);
                        } catch (Throwable t) {
                            synchronized (failures) {
                                failures.add(t);
                            }
                        }
                    });
                    synchronized (tasks) {
                        tasks.add(task);
                    }
                });
            }
            for (ForkJoinTask<?> t : tasks) {
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
            if (ownsPool) {
                pool.shutdown();
            }
        }
        return failures;
    }

    private static void indexOne(URI uri, URI sourceUri, String fileName, byte[] content, Index into) throws IOException {
        if (fileName.endsWith(".class")) {
            ClassFileIndexer.index(uri, sourceUri, content, into);
        } else if (fileName.endsWith(".java")) {
            SourceIndexer.index(uri, sourceUri, new String(content, StandardCharsets.UTF_8), into);
        }
    }
}
