package ch.castleridge.javals.indexing.model;

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
 * <p>{@link TypeRef.Resolved} and {@link TypeRef.Unresolved} are
 * constructed through {@link TypeRef#resolved(String)} /
 * {@link TypeRef#unresolved(String)} factories which cache instances by
 * name. The indexer emits millions of refs for a small set of common JVM
 * names (most of {@code java.lang}, etc.); sharing a single record per
 * distinct name turns a ~100 MiB allocation into a few kilobytes.
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

    /** An array type; {@code element} may itself be any {@link Type}. */
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
     */
    record Wildcard(BoundKind kind, Type bound) implements Type {
        public enum BoundKind {
            UNBOUNDED, EXTENDS, SUPER
        }

        public static Wildcard unbounded() {
            return new Wildcard(BoundKind.UNBOUNDED, null);
        }

        public static Wildcard extendsBound(Type bound) {
            return new Wildcard(BoundKind.EXTENDS, bound);
        }

        public static Wildcard superBound(Type bound) {
            return new Wildcard(BoundKind.SUPER, bound);
        }
    }

    /**
     * A parameterized type such as {@code List<String>} or
     * {@code Expectation<? super T>}.
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
}
