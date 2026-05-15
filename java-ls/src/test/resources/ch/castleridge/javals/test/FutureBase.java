package ch.castleridge.javals.test;

public abstract class FutureBase<T> implements Future<T> {
    public abstract void removeListener(Completable<? super T> listener);

    public Future<T> expecting(Expectation<? super T> expectation) {
        return this;
    }
}
