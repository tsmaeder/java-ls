package ch.castleridge.javals.indexing.scan;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Mutable, thread-safe accumulator for scan statistics. Walkers record
 * per-resource counts/sizes when they already know them; {@link Scanner}
 * snapshots the result after {@link Scanner#scanAll}.
 */
public final class ScanCollector {

    private final AtomicInteger sourceFileCount = new AtomicInteger();
    private final AtomicLong classFileBytes = new AtomicLong();

    public void addSourceFile() {
        sourceFileCount.incrementAndGet();
    }

    public void addClassFileBytes(long n) {
        if (n >= 0) {
            classFileBytes.addAndGet(n);
        }
    }

    public ScanStats snapshot() {
        return new ScanStats(sourceFileCount.get(), classFileBytes.get());
    }
}
