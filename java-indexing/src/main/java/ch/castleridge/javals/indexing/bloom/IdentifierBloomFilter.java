package ch.castleridge.javals.indexing.bloom;

import java.util.Collection;

/**
 * A compact, immutable bloom filter over identifier simple names.
 *
 * <p>Built per source file during indexing; after construction the filter
 * is thread-safe for concurrent {@link #mightContain} queries.
 */
public final class IdentifierBloomFilter {

    private static final double TARGET_FPR = 0.01;
    private static final int MIN_BITS = 64;
    private static final int MAX_HASH_FUNCTIONS = 8;

    private final long[] bits;
    private final int bitCount;
    private final int hashCount;

    private IdentifierBloomFilter(long[] bits, int bitCount, int hashCount) {
        this.bits = bits;
        this.bitCount = bitCount;
        this.hashCount = hashCount;
    }

    /**
     * Build a filter sized for {@code expectedElements} distinct names.
     */
    public static IdentifierBloomFilter create(Collection<? extends CharSequence> names) {
        int n = names.size();
        if (n == 0) {
            return new IdentifierBloomFilter(new long[1], MIN_BITS, 1);
        }
        int bitCount = Math.max(MIN_BITS, optimalBitCount(n, TARGET_FPR));
        int hashCount = Math.min(MAX_HASH_FUNCTIONS, optimalHashCount(bitCount, n));
        int wordCount = (bitCount + 63) >>> 6;
        long[] bits = new long[wordCount];
        for (CharSequence name : names) {
            addTo(bits, bitCount, hashCount, name);
        }
        return new IdentifierBloomFilter(bits, bitCount, hashCount);
    }

    public boolean mightContain(CharSequence name) {
        if (name == null || name.isEmpty()) return false;
        int h1 = hash1(name);
        int h2 = hash2(name);
        for (int i = 0; i < hashCount; i++) {
            int combined = h1 + i * h2;
            int index = Math.floorMod(combined, bitCount);
            if (!isSet(bits, index)) return false;
        }
        return true;
    }

    private static void addTo(long[] bits, int bitCount, int hashCount, CharSequence name) {
        int h1 = hash1(name);
        int h2 = hash2(name);
        for (int i = 0; i < hashCount; i++) {
            int combined = h1 + i * h2;
            int index = Math.floorMod(combined, bitCount);
            set(bits, index);
        }
    }

    private static boolean isSet(long[] bits, int index) {
        int word = index >>> 6;
        int bit = index & 63;
        return (bits[word] & (1L << bit)) != 0;
    }

    private static void set(long[] bits, int index) {
        int word = index >>> 6;
        int bit = index & 63;
        bits[word] |= (1L << bit);
    }

    private static int optimalBitCount(int n, double fpr) {
        double m = -n * Math.log(fpr) / (Math.log(2) * Math.log(2));
        return (int) Math.ceil(m);
    }

    private static int optimalHashCount(int bitCount, int n) {
        double k = (bitCount / (double) n) * Math.log(2);
        return Math.max(1, (int) Math.round(k));
    }

    /** FNV-1a 32-bit. */
    private static int hash1(CharSequence s) {
        int h = 0x811c9dc5;
        for (int i = 0; i < s.length(); i++) {
            h ^= s.charAt(i);
            h *= 0x01000193;
        }
        return h;
    }

    /** Murmur-ish mixing of length + first/last chars. */
    private static int hash2(CharSequence s) {
        int h = s.length();
        if (s.length() > 0) {
            h = 31 * h + s.charAt(0);
            h = 31 * h + s.charAt(s.length() - 1);
        }
        h ^= h >>> 16;
        h *= 0x85ebca6b;
        h ^= h >>> 13;
        h *= 0xc2b2ae35;
        h ^= h >>> 16;
        return h | 1; // avoid zero stride in double hashing
    }
}
