package ch.castleridge.javals.test;

public interface Future<T> {
    Future<T> expecting(Expectation<? super T> expectation);
}
