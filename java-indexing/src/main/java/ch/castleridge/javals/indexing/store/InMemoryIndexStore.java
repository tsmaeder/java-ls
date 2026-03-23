package ch.castleridge.javals.indexing.store;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

/** Thread-safe linear-scan store; suitable for tests and small workspaces. */
public final class InMemoryIndexStore implements IndexStore {

    private final CopyOnWriteArrayList<IndexEntry> entries = new CopyOnWriteArrayList<>();
    private final Executor executor;

    public InMemoryIndexStore(Executor executor) {
        this.executor = executor;
    }

    @Override
    public CompletableFuture<Void> insert(IndexEntry entry) {
        return CompletableFuture.runAsync(() -> entries.add(copy(entry)), executor);
    }

    @Override
    public CompletableFuture<Void> insertAll(List<IndexEntry> toAdd) {
        return CompletableFuture.runAsync(
                () -> {
                    for (IndexEntry e : toAdd) {
                        this.entries.add(copy(e));
                    }
                },
                executor);
    }

    @Override
    public CompletableFuture<Long> removeMatching(FieldSelector selector) {
        return CompletableFuture.supplyAsync(
                () -> {
                    long removed = 0;
                    List<IndexEntry> kept = new ArrayList<>();
                    for (IndexEntry e : entries) {
                        if (matchesSelector(e, selector)) {
                            removed++;
                        } else {
                            kept.add(e);
                        }
                    }
                    entries.clear();
                    entries.addAll(kept);
                    return removed;
                },
                executor);
    }

    @Override
    public CompletableFuture<Void> search(
            SearchPredicate predicate, Consumer<IndexEntry> consumer, Executor searchExecutor) {
        return CompletableFuture.runAsync(
                () -> {
                    for (IndexEntry e : entries) {
                        if (matchesPredicate(e, predicate)) {
                            consumer.accept(copy(e));
                        }
                    }
                },
                searchExecutor);
    }

    private static boolean matchesSelector(IndexEntry entry, FieldSelector selector) {
        String v = entry.field(selector.fieldName());
        return v != null && v.contains(selector.substring());
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
