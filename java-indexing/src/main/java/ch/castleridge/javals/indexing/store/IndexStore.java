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
     * Invokes {@code consumer} once per matching entry on {@code executor}. Completes when the scan
     * finishes (or exceptionally if {@code consumer} throws).
     */
    CompletableFuture<Void> search(
            SearchPredicate predicate, Consumer<IndexEntry> consumer, Executor executor);
}
