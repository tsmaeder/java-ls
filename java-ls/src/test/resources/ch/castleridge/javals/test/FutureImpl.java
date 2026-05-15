package ch.castleridge.javals.test;

public class FutureImpl<T> extends FutureBase<T> {
    @Override
    public void removeListener(Completable<? super T> listener) {
        throw new UnsupportedOperationException("Unimplemented method 'removeListener'");
    }
}
