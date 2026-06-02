package ch.castleridge.javals.indexing.model;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import ch.castleridge.javals.indexing.intern.Interner;

/**
 * A reference to a Java type produced by the indexer.
 *
 * <p>The indexer runs without classpath information, so it cannot always
 * resolve a simple name to a fully-qualified one (for instance
 * {@code List} in a file that does {@code import java.util.*}). Rather
 * than guess, we emit {@link Unresolved} for those cases and defer
 * resolution until the {@code IndexClassReader} materialises a
 * {@link ch.castleridge.javals.indexing.model.TypeEntry} into a javac
 * {@code ClassSymbol}. At that point we have the full
 * {@link ch.castleridge.javals.indexing.index.Index} and the enclosing
 * {@link SourceResolutionHints}, which together allow JLS-compliant
 * lookup (declared-in-CU &gt; single-type-import &gt; same-package &gt;
 * on-demand-import &gt; {@code java.lang}).
 *
 * <p>Bytecode-derived {@link TypeEntry}s contain only {@link Primitive},
 * {@link Array}, {@link Resolved}, {@link TypeVariable}, {@link Wildcard}
 * and {@link Parameterized} leaves.
 *
 * <p>{@link Resolved} and {@link Unresolved} are constructed through the
 * {@link #resolved(String)} / {@link #unresolved(String)} factories which
 * cache instances by name. The indexer emits millions of refs for a small
 * set of common JVM names (most of {@code java.lang}, etc.); sharing a
 * single record per distinct name turns a ~100 MiB allocation into a few
 * kilobytes.
 */
public sealed interface TypeRef
        permits TypeRef.Primitive,
                TypeRef.Array,
                TypeRef.Resolved,
                TypeRef.Unresolved,
                TypeRef.TypeVariable,
                TypeRef.Wildcard,
                TypeRef.Parameterized,
                TypeRef.Annotated {

    /** The nine primitive forms, plus {@code void}. */
    enum Primitive implements TypeRef {
        VOID, BOOLEAN, BYTE, CHAR, SHORT, INT, LONG, FLOAT, DOUBLE
    }

    /**
     * A decorator that attaches type-use annotations to an inner
     * {@link TypeRef} without forcing every variant to carry an
     * annotations list itself (which would balloon the canonical
     * constructors and force {@link Primitive} to stop being an enum).
     *
     * <p>The inner TypeRef may itself be another {@code Annotated}; the
     * outermost annotations are the ones that apply at this position in
     * the type tree. {@link Annotated#annotations()} is always non-empty
     * (callers should use the bare inner {@link TypeRef} when the list
     * would be empty).
     *
     * <p>{@link Annotated#unwrap()} strips any annotation wrappers and
     * returns the underlying inner type, which is what symbol-level
     * resolution typically cares about.
     */
    record Annotated(TypeRef inner, List<AnnotationRef> annotations) implements TypeRef {
        public Annotated {
            if (inner == null) {
                throw new IllegalArgumentException("inner must not be null");
            }
            annotations = annotations == null ? List.of() : List.copyOf(annotations);
            if (annotations.isEmpty()) {
                throw new IllegalArgumentException("annotations must be non-empty - "
                        + "use the bare inner TypeRef when there are no annotations");
            }
        }

        /**
         * Convenience factory that returns {@code inner} directly when
         * {@code annotations} is empty, avoiding pointless wrapping.
         */
        public static TypeRef wrap(TypeRef inner, List<AnnotationRef> annotations) {
            if (annotations == null || annotations.isEmpty()) return inner;
            if (inner instanceof Annotated a) {
                List<AnnotationRef> merged = new java.util.ArrayList<>(a.annotations().size() + annotations.size());
                merged.addAll(a.annotations());
                merged.addAll(annotations);
                return new Annotated(a.inner(), List.copyOf(merged));
            }
            return new Annotated(inner, annotations);
        }

        /** Strip every nested {@link Annotated} wrapper and return the underlying TypeRef. */
        public TypeRef unwrap() {
            TypeRef cur = inner;
            while (cur instanceof Annotated a) {
                cur = a.inner();
            }
            return cur;
        }
    }

    /** An array type; {@code element} may itself be any {@link TypeRef}. */
    record Array(TypeRef element) implements TypeRef {
        public Array {
            if (element == null) {
                throw new IllegalArgumentException("element must not be null");
            }
        }
    }

    /**
     * A fully-resolved reference carrying a JVM binary name
     * (e.g. {@code java/util/Map$Entry}). Emitted for bytecode lookups and
     * for source references that are qualified enough to not need further
     * resolution (fully-qualified names, imports that already bind a simple
     * name to a fully-qualified target, etc.).
     *
     * <p>Prefer {@link TypeRef#resolved(String)} to the canonical
     * constructor: the factory shares one instance per JVM name.
     */
    record Resolved(String jvmBinaryName) implements TypeRef {
        public Resolved {
            if (jvmBinaryName == null || jvmBinaryName.isEmpty()) {
                throw new IllegalArgumentException("jvmBinaryName must be non-empty");
            }
        }
    }

    /**
     * A reference that only knows its simple name; the final JVM binary
     * name is determined later from the {@link SourceResolutionHints}
     * attached to the enclosing {@link TypeEntry}.
     *
     * <p>Prefer {@link TypeRef#unresolved(String)} to the canonical
     * constructor: the factory shares one instance per simple name.
     */
    record Unresolved(String simpleName) implements TypeRef {
        public Unresolved {
            if (simpleName == null || simpleName.isEmpty()) {
                throw new IllegalArgumentException("simpleName must be non-empty");
            }
        }
    }

    /**
     * A type variable reference such as {@code T} or {@code V} declared on
     * the enclosing class or method.
     */
    record TypeVariable(String name) implements TypeRef {
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
    record Wildcard(BoundKind kind, TypeRef bound) implements TypeRef {
        public enum BoundKind {
            UNBOUNDED, EXTENDS, SUPER
        }

        public static Wildcard unbounded() {
            return new Wildcard(BoundKind.UNBOUNDED, null);
        }

        public static Wildcard extendsBound(TypeRef bound) {
            return new Wildcard(BoundKind.EXTENDS, bound);
        }

        public static Wildcard superBound(TypeRef bound) {
            return new Wildcard(BoundKind.SUPER, bound);
        }
    }

    /**
     * A parameterized type such as {@code List<String>} or
     * {@code Expectation<? super T>}.
     */
    record Parameterized(TypeRef raw, List<TypeRef> typeArgs) implements TypeRef {
        public Parameterized {
            if (raw == null) {
                throw new IllegalArgumentException("raw must not be null");
            }
            typeArgs = typeArgs == null ? List.of() : List.copyOf(typeArgs);
        }
    }

    ConcurrentMap<String, Resolved> RESOLVED_CACHE = new ConcurrentHashMap<>(1 << 13);
    ConcurrentMap<String, Unresolved> UNRESOLVED_CACHE = new ConcurrentHashMap<>(1 << 10);
    ConcurrentMap<String, TypeVariable> TYPE_VARIABLE_CACHE = new ConcurrentHashMap<>(1 << 8);

    /**
     * Return a cached {@link Resolved} for {@code jvmBinaryName}. The name
     * is interned through {@link Interner} so callers don't have to.
     */
    static Resolved resolved(String jvmBinaryName) {
        if (jvmBinaryName == null || jvmBinaryName.isEmpty()) {
            throw new IllegalArgumentException("jvmBinaryName must be non-empty");
        }
        String key = Interner.intern(jvmBinaryName);
        Resolved cached = RESOLVED_CACHE.get(key);
        if (cached != null) return cached;
        Resolved made = new Resolved(key);
        Resolved prior = RESOLVED_CACHE.putIfAbsent(key, made);
        return prior == null ? made : prior;
    }

    /** Return a cached {@link Unresolved} for {@code simpleName}. */
    static Unresolved unresolved(String simpleName) {
        if (simpleName == null || simpleName.isEmpty()) {
            throw new IllegalArgumentException("simpleName must be non-empty");
        }
        String key = Interner.intern(simpleName);
        Unresolved cached = UNRESOLVED_CACHE.get(key);
        if (cached != null) return cached;
        Unresolved made = new Unresolved(key);
        Unresolved prior = UNRESOLVED_CACHE.putIfAbsent(key, made);
        return prior == null ? made : prior;
    }

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
