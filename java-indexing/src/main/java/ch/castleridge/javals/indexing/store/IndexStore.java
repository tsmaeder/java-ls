package ch.castleridge.javals.indexing.store;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

/**
 * Storage-agnostic substring index: async writes, async search streaming via {@link Consumer} of
 * {@link IndexEntry}.
 */
public interface IndexStore {

    CompletableFuture<Void> insert(IndexEntry entry);

    CompletableFuture<Void> insertAll(List<IndexEntry> entries);

    /** Removes entries where {@code fieldName}'s value contains {@code substring}. */
    CompletableFuture<Long> removeMatching(FieldSelector selector);

    /**
     * Removes all rows in the partition bucket for {@code partitionKey} (exact key, average {@code
     * O(1)}). Use when the key matches {@link IndexEntry} values for the store's partition field;
     * use {@link #removeMatching} for substring bulk delete.
     */
    CompletableFuture<Long> removePartition(String partitionKey);

    /**
     * Invokes {@code consumer} once per matching entry on {@code executor}. Completes when the scan
     * finishes (or exceptionally if {@code consumer} throws).
     */
    CompletableFuture<Void> search(
            SearchPredicate predicate, Consumer<IndexEntry> consumer, Executor executor);
}
