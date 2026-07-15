package ch.castleridge.javals.indexing.model;

import java.util.Arrays;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import ch.castleridge.javals.indexing.intern.Interner;

/**
 * A reference to a Java type produced by the indexer.
 *
 * <p>The indexer runs without classpath information, so it cannot always
 * resolve a simple name to a fully-qualified one (for instance
 * {@code List} in a file that does {@code import java.util.*}). Rather
 * than guess, we emit {@link TypeRef.Unresolved} for those cases and defer
 * resolution until the {@code IndexClassReader} materialises a
 * {@link ch.castleridge.javals.indexing.model.TypeEntry} into a javac
 * {@code ClassSymbol}. At that point we have the full
 * {@link ch.castleridge.javals.indexing.index.Index} and the enclosing
 * {@link SourceResolutionHints}, which together allow JLS-compliant
 * lookup (declared-in-CU &gt; single-type-import &gt; same-package &gt;
 * on-demand-import &gt; {@code java.lang}).
 *
 * <p>Only {@link TypeRef} leaves ({@link TypeRef.Resolved},
 * {@link TypeRef.Unresolved}) carry a name that may need classpath
 * resolution. Structural variants such as {@link Primitive},
 * {@link Array}, {@link TypeVariable}, {@link Wildcard} and
 * {@link Parameterized} are known from their shape alone.
 *
 * <p>Bytecode-derived {@link TypeEntry}s contain only {@link Primitive},
 * {@link Array}, {@link TypeRef.Resolved}, {@link TypeVariable},
 * {@link Wildcard} and {@link Parameterized} leaves.
 *
 * <p>{@link TypeRef} leaves, {@link TypeVariable}s, and annotation-free
 * structural forms ({@link Array}, {@link Wildcard}, {@link Parameterized})
 * are obtained through factories that cache by shape. Prefer those factories
 * ({@link TypeRef#resolved(String)}, {@link #array(Type)},
 * {@link #parameterized(TypeRef, Type[])}, {@link Wildcard#unbounded()}, …)
 * over the record constructors. {@link Annotated} wrappers are never
 * interned — type-use annotations stay unique per occurrence so they do not
 * poison the structural caches.
 */
public sealed interface Type
        permits Type.Primitive,
                Type.Array,
                Type.TypeVariable,
                Type.Wildcard,
                Type.Parameterized,
                Type.Annotated,
                TypeRef {

    /** The nine primitive forms, plus {@code void}. */
    enum Primitive implements Type {
        VOID, BOOLEAN, BYTE, CHAR, SHORT, INT, LONG, FLOAT, DOUBLE
    }

    /**
     * A decorator that attaches type-use annotations to an inner
     * {@link Type} without forcing every variant to carry an
     * annotations array itself (which would balloon the canonical
     * constructors and force {@link Primitive} to stop being an enum).
     *
     * <p>The inner Type may itself be another {@code Annotated}; the
     * outermost annotations are the ones that apply at this position in
     * the type tree. {@link Annotated#annotations()} is always non-empty
     * (callers should use the bare inner {@link Type} when the array
     * would be empty).
     *
     * <p>{@link Annotated#unwrap()} strips any annotation wrappers and
     * returns the underlying inner type, which is what symbol-level
     * resolution typically cares about.
     */
    record Annotated(Type inner, AnnotationRef[] annotations) implements Type {
        public Annotated {
            if (inner == null) {
                throw new IllegalArgumentException("inner must not be null");
            }
            annotations = EmptyArrays.orEmpty(annotations, EmptyArrays.ANNOTATION_REF);
            if (annotations.length == 0) {
                throw new IllegalArgumentException("annotations must be non-empty - "
                        + "use the bare inner Type when there are no annotations");
            }
        }

        /**
         * Convenience factory that returns {@code inner} directly when
         * {@code annotations} is empty, avoiding pointless wrapping.
         */
        public static Type wrap(Type inner, AnnotationRef[] annotations) {
            if (annotations == null || annotations.length == 0) return inner;
            if (inner instanceof Annotated a) {
                AnnotationRef[] merged = new AnnotationRef[a.annotations().length + annotations.length];
                System.arraycopy(a.annotations(), 0, merged, 0, a.annotations().length);
                System.arraycopy(annotations, 0, merged, a.annotations().length, annotations.length);
                return new Annotated(a.inner(), merged);
            }
            return new Annotated(inner, annotations);
        }

        /** Strip every nested {@link Annotated} wrapper and return the underlying Type. */
        public Type unwrap() {
            Type cur = inner;
            while (cur instanceof Annotated a) {
                cur = a.inner();
            }
            return cur;
        }
    }

    /**
     * An array type; {@code element} may itself be any {@link Type}.
     *
     * <p>Prefer {@link Type#array(Type)} so annotation-free shapes are shared.
     */
    record Array(Type element) implements Type {
        public Array {
            if (element == null) {
                throw new IllegalArgumentException("element must not be null");
            }
        }
    }

    /**
     * A type variable reference such as {@code T} or {@code V} declared on
     * the enclosing class or method.
     */
    record TypeVariable(String name) implements Type {
        public TypeVariable {
            if (name == null || name.isEmpty()) {
                throw new IllegalArgumentException("name must be non-empty");
            }
        }
    }

    /**
     * A wildcard type argument: unbounded {@code ?}, {@code ? extends X},
     * or {@code ? super X}. {@code bound} is {@code null} for unbounded
     * {@code ?}.
     *
     * <p>Prefer the factories ({@link #unbounded()}, {@link #extendsBound(Type)},
     * {@link #superBound(Type)}) so annotation-free shapes are shared.
     */
    record Wildcard(BoundKind kind, Type bound) implements Type {
        public enum BoundKind {
            UNBOUNDED, EXTENDS, SUPER
        }

        private static final Wildcard UNBOUNDED = new Wildcard(BoundKind.UNBOUNDED, null);

        public static Wildcard unbounded() {
            return UNBOUNDED;
        }

        public static Wildcard extendsBound(Type bound) {
            if (bound instanceof Annotated) {
                return new Wildcard(BoundKind.EXTENDS, bound);
            }
            return cachedWildcard(BoundKind.EXTENDS, bound, TypeCaches.EXTENDS);
        }

        public static Wildcard superBound(Type bound) {
            if (bound instanceof Annotated) {
                return new Wildcard(BoundKind.SUPER, bound);
            }
            return cachedWildcard(BoundKind.SUPER, bound, TypeCaches.SUPER);
        }

        private static Wildcard cachedWildcard(
                BoundKind kind, Type bound, ConcurrentMap<Type, Wildcard> cache) {
            if (bound == null) {
                return new Wildcard(kind, null);
            }
            Wildcard cached = cache.get(bound);
            if (cached != null) return cached;
            Wildcard made = new Wildcard(kind, bound);
            Wildcard prior = cache.putIfAbsent(bound, made);
            return prior == null ? made : prior;
        }
    }

    /**
     * A parameterized type such as {@code List<String>} or
     * {@code Expectation<? super T>}.
     *
     * <p>Prefer {@link Type#parameterized(TypeRef, Type[])} so annotation-free
     * shapes are shared.
     */
    record Parameterized(TypeRef raw, Type[] typeArgs) implements Type {
        public Parameterized {
            if (raw == null) {
                throw new IllegalArgumentException("raw must not be null");
            }
            typeArgs = EmptyArrays.orEmpty(typeArgs, EmptyArrays.TYPE);
        }
    }

    ConcurrentMap<String, TypeVariable> TYPE_VARIABLE_CACHE = new ConcurrentHashMap<>(1 << 8);

    /** Return a cached {@link TypeVariable} for {@code name}. */
    static TypeVariable typeVariable(String name) {
        if (name == null || name.isEmpty()) {
            throw new IllegalArgumentException("name must be non-empty");
        }
        String key = Interner.intern(name);
        TypeVariable cached = TYPE_VARIABLE_CACHE.get(key);
        if (cached != null) return cached;
        TypeVariable made = new TypeVariable(key);
        TypeVariable prior = TYPE_VARIABLE_CACHE.putIfAbsent(key, made);
        return prior == null ? made : prior;
    }

    /**
     * Return a cached {@link Array} for {@code element} when the element is
     * not an {@link Annotated} wrapper; annotated array types are allocated
     * fresh so type-use annotations stay unique.
     */
    static Array array(Type element) {
        if (element == null) {
            throw new IllegalArgumentException("element must not be null");
        }
        if (element instanceof Annotated) {
            return new Array(element);
        }
        Array cached = TypeCaches.ARRAY.get(element);
        if (cached != null) return cached;
        Array made = new Array(element);
        Array prior = TypeCaches.ARRAY.putIfAbsent(element, made);
        return prior == null ? made : prior;
    }

    /**
     * Return a cached {@link Parameterized} for {@code raw} and
     * {@code typeArgs} when no argument is {@link Annotated}; annotated
     * type arguments force a fresh allocation.
     */
    static Parameterized parameterized(TypeRef raw, Type[] typeArgs) {
        if (raw == null) {
            throw new IllegalArgumentException("raw must not be null");
        }
        typeArgs = EmptyArrays.orEmpty(typeArgs, EmptyArrays.TYPE);
        if (hasAnnotatedArg(typeArgs)) {
            return new Parameterized(raw, typeArgs);
        }
        TypeCaches.ParameterizedKey probe = new TypeCaches.ParameterizedKey(raw, typeArgs);
        Parameterized cached = TypeCaches.PARAMETERIZED.get(probe);
        if (cached != null) return cached;
        Type[] owned = typeArgs.length == 0
                ? EmptyArrays.TYPE
                : Arrays.copyOf(typeArgs, typeArgs.length);
        Parameterized made = new Parameterized(raw, owned);
        TypeCaches.ParameterizedKey key =
                owned == typeArgs ? probe : new TypeCaches.ParameterizedKey(raw, owned);
        Parameterized prior = TypeCaches.PARAMETERIZED.putIfAbsent(key, made);
        return prior == null ? made : prior;
    }

    private static boolean hasAnnotatedArg(Type[] typeArgs) {
        for (Type arg : typeArgs) {
            if (arg instanceof Annotated) return true;
        }
        return false;
    }
}
