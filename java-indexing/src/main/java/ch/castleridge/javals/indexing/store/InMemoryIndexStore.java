package ch.castleridge.javals.indexing.store;

import ch.castleridge.javals.indexing.declaration.DeclarationFields;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Consumer;

/**
 * Thread-safe store with rows grouped by a partition field (default {@link
 * DeclarationFields#RESOURCE_URI}). Inserts are {@code O(1)} per row; {@link #removePartition} is
 * {@code O(1)} average; {@link #removeMatching} on the partition field is {@code O(buckets)} when
 * the substring is non-empty (substring semantics).
 */
public final class InMemoryIndexStore implements IndexStore {

    private static final String UNPARTITIONED_KEY = "";

    private final Map<String, List<IndexEntry>> byPartition = new HashMap<>();
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
    private final String partitionFieldName;
    private final Executor executor;

    public InMemoryIndexStore(Executor executor) {
        this(executor, DeclarationFields.RESOURCE_URI);
    }

    public InMemoryIndexStore(Executor executor, String partitionFieldName) {
        this.executor = executor;
        this.partitionFieldName = Objects.requireNonNull(partitionFieldName, "partitionFieldName");
    }

    @Override
    public CompletableFuture<Void> insert(IndexEntry entry) {
        return CompletableFuture.runAsync(
                () -> {
                    lock.writeLock().lock();
                    try {
                        insertUnderLock(copy(entry));
                    } finally {
                        lock.writeLock().unlock();
                    }
                },
                executor);
    }

    @Override
    public CompletableFuture<Void> insertAll(List<IndexEntry> toAdd) {
        return CompletableFuture.runAsync(
                () -> {
                    lock.writeLock().lock();
                    try {
                        for (IndexEntry e : toAdd) {
                            insertUnderLock(copy(e));
                        }
                    } finally {
                        lock.writeLock().unlock();
                    }
                },
                executor);
    }

    private void insertUnderLock(IndexEntry entry) {
        String key = partitionKey(entry);
        byPartition.computeIfAbsent(key, k -> new ArrayList<>()).add(entry);
    }

    private String partitionKey(IndexEntry entry) {
        String v = entry.field(partitionFieldName);
        return v != null ? v : UNPARTITIONED_KEY;
    }

    @Override
    public CompletableFuture<Long> removePartition(String partitionKey) {
        return CompletableFuture.supplyAsync(
                () -> {
                    lock.writeLock().lock();
                    try {
                        List<IndexEntry> bucket = byPartition.remove(partitionKey);
                        return bucket == null ? 0L : (long) bucket.size();
                    } finally {
                        lock.writeLock().unlock();
                    }
                },
                executor);
    }

    @Override
    public CompletableFuture<Void> search(
            SearchPredicate predicate, Consumer<IndexEntry> consumer, Executor searchExecutor) {
        return CompletableFuture.runAsync(
                () -> {
                    lock.readLock().lock();
                    List<IndexEntry> snapshot;
                    try {
                        snapshot = new ArrayList<>();
                        for (List<IndexEntry> bucket : byPartition.values()) {
                            snapshot.addAll(bucket);
                        }
                    } finally {
                        lock.readLock().unlock();
                    }
                    for (IndexEntry e : snapshot) {
                        if (matchesPredicate(e, predicate)) {
                            consumer.accept(copy(e));
                        }
                    }
                },
                searchExecutor);
    }

    private static boolean matchesPredicate(IndexEntry entry, SearchPredicate predicate) {
        for (FieldCondition c : predicate.conditions()) {
            String v = entry.field(c.fieldName());
            if (v == null || !v.contains(c.substring())) {
                return false;
            }
        }
        return true;
    }

    private static IndexEntry copy(IndexEntry entry) {
        return new IndexEntry(entry.fields());
    }
}
