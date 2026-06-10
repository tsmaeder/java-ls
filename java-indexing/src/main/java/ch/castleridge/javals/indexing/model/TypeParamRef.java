package ch.castleridge.javals.indexing.model;

import java.util.List;

/**
 * A formal type parameter declared on a {@link TypeEntry} (class) or, in
 * the future, a {@link MethodEntry}.
 *
 * <p>Phase 1 of generics support only stores the parameter <em>name</em>
 * (e.g. {@code "T"}, {@code "R"}); bounds are normalised to a single
 * {@code java/lang/Object} bound. That is enough for javac to see the
 * correct arity on the enclosing {@code ClassType} so that source
 * references like {@code Function<? super T, U>} parse and type-check
 * structurally.
 *
 * <p>The {@link #bounds()} list is never empty: a parameter with no
 * declared bound is normalised to {@code [java/lang/Object]} so callers
 * can synthesize a {@code TypeVar} unconditionally.
 */
public record TypeParamRef(String name, List<Type> bounds) {

    public TypeParamRef {
        if (name == null || name.isEmpty()) {
            throw new IllegalArgumentException("name must be non-empty");
        }
        bounds = (bounds == null || bounds.isEmpty())
                ? List.of(TypeRef.resolved("java/lang/Object"))
                : List.copyOf(bounds);
    }

    /** Convenience for the common case of "no declared bound". */
    public static TypeParamRef of(String name) {
        return new TypeParamRef(name, List.of());
    }
}
