public class CompositeFutureImpl extends FutureImpl<CompositeFuture> implements CompositeFuture, Completable<Object> {

  private static final int OP_ALL = 0;
  private static final int OP_ANY = 1;
  private static final int OP_JOIN = 2;

  public static CompositeFuture all(Future<?>... results) {
    return create(OP_ALL, results);
  }
}