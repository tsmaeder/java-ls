package ch.castleridge.javals.indexing.index;

import java.util.LinkedHashMap;
import java.util.Map;

import ch.castleridge.javals.indexing.model.TypeEntry;
import ch.castleridge.javals.indexing.model.TypeEntryCodec;

/**
 * Fixed-size LRU of decoded {@link TypeEntry}s keyed by blob identity.
 *
 * <p>{@code byte[]} keys use reference equality/hashCode, so each encoded
 * blob caches at most one decoded graph. Eviction drops the decoded object
 * graph only; the blob itself remains in the {@link Index}.
 */
final class DecodedTypeCache {

    static final int DEFAULT_CAPACITY = 4096;

    private final int capacity;
    private final Map<byte[], TypeEntry> cache;

    DecodedTypeCache() {
        this(DEFAULT_CAPACITY);
    }

    DecodedTypeCache(int capacity) {
        if (capacity < 1) {
            throw new IllegalArgumentException("capacity must be positive");
        }
        this.capacity = capacity;
        this.cache = new LinkedHashMap<>(capacity, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<byte[], TypeEntry> eldest) {
                return size() > DecodedTypeCache.this.capacity;
            }
        };
    }

    synchronized TypeEntry get(byte[] blob) {
        if (blob == null) return null;
        TypeEntry cached = cache.get(blob);
        if (cached != null) return cached;
        TypeEntry decoded = TypeEntryCodec.decode(blob);
        cache.put(blob, decoded);
        return decoded;
    }
}
