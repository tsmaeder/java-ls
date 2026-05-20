package ch.castleridge.javals.test;

public interface Future<T> {
    Future<T> expecting(Expectation<? super T> expectation);

    static CompositeFuture all(Future<?> f1, Future<?> f2, Future<?> f3, Future<?> f4, Future<?> f5) {
        return CompositeFutureImpl.all(f1, f2, f3, f4, f5);
    }
}
