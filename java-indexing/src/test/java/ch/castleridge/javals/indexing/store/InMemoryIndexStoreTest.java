package ch.castleridge.javals.indexing.store;

import ch.castleridge.javals.indexing.declaration.DeclarationFields;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Collections;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InMemoryIndexStoreTest {

    @Test
    void searchSubstringConjunctionAndBulkRemove() throws Exception {
        ExecutorService exec = Executors.newSingleThreadExecutor();
        try {
            InMemoryIndexStore store = new InMemoryIndexStore(exec);
            IndexEntry a =
                    doc(
                            Map.of(
                                    DeclarationFields.RESOURCE_URI,
                                    "file:/a/Foo.class",
                                    "kind",
                                    "TYPE",
                                    "name",
                                    "Foo"));
            IndexEntry b =
                    doc(
                            Map.of(
                                    DeclarationFields.RESOURCE_URI,
                                    "file:/b/Bar.class",
                                    "kind",
                                    "TYPE",
                                    "name",
                                    "Bar"));
            IndexEntry c =
                    doc(
                            Map.of(
                                    DeclarationFields.RESOURCE_URI,
                                    "file:/a/Baz.class",
                                    "kind",
                                    "METHOD",
                                    "name",
                                    "baz"));

            CompletableFuture.allOf(store.insert(a), store.insert(b), store.insert(c)).join();

            Map<String, Integer> hits = new HashMap<>();
            store.search(
                            SearchPredicate.allOf(new FieldCondition(DeclarationFields.RESOURCE_URI, "file:/a/")),
                            d -> hits.merge(d.field("name"), 1, Integer::sum),
                            exec)
                    .join();
            assertEquals(2, hits.size());
            assertEquals(1, hits.get("Foo").intValue());
            assertEquals(1, hits.get("baz").intValue());

            Long removed =
                    store.removeMatching(new FieldSelector(DeclarationFields.RESOURCE_URI, "file:/a/"))
                            .join();
            assertEquals(2L, removed.longValue());

            List<IndexEntry> remaining = new java.util.ArrayList<>();
            store.search(new SearchPredicate(Collections.emptyList()), remaining::add, exec).join();
            assertEquals(1, remaining.size());
            assertEquals("Bar", remaining.get(0).field("name"));
        } finally {
            exec.shutdown();
            assertTrue(exec.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void removePartitionExactKey() throws Exception {
        ExecutorService exec = Executors.newSingleThreadExecutor();
        try {
            InMemoryIndexStore store = new InMemoryIndexStore(exec);
            String uriA = "file:/a/Foo.class";
            String uriB = "file:/a/Foo.class.extra";
            IndexEntry a =
                    doc(
                            Map.of(
                                    DeclarationFields.RESOURCE_URI,
                                    uriA,
                                    "kind",
                                    "TYPE",
                                    "name",
                                    "Foo"));
            IndexEntry b =
                    doc(
                            Map.of(
                                    DeclarationFields.RESOURCE_URI,
                                    uriB,
                                    "kind",
                                    "TYPE",
                                    "name",
                                    "FooExtra"));

            CompletableFuture.allOf(store.insert(a), store.insert(b)).join();

            assertEquals(1L, store.removePartition(uriA).join().longValue());

            List<IndexEntry> remaining = new java.util.ArrayList<>();
            store.search(new SearchPredicate(Collections.emptyList()), remaining::add, exec).join();
            assertEquals(1, remaining.size());
            assertEquals("FooExtra", remaining.get(0).field("name"));

            assertEquals(0L, store.removePartition("missing").join().longValue());
        } finally {
            exec.shutdown();
            assertTrue(exec.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    private static IndexEntry doc(Map<String, String> fields) {
        return new IndexEntry(fields);
    }
}
