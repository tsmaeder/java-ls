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
 * {@link Array} and {@link Resolved} leaves - the classfile format
 * never produces {@link Unresolved}.
 *
 * <p>{@link Resolved} and {@link Unresolved} are constructed through the
 * {@link #resolved(String)} / {@link #unresolved(String)} factories which
 * cache instances by name. The indexer emits millions of refs for a small
 * set of common JVM names (most of {@code java.lang}, type-parameter
 * erasure to {@code java/lang/Object}, etc.); sharing a single record per
 * distinct name turns a ~100 MiB allocation into a few kilobytes.
 */
public sealed interface TypeRef
        permits TypeRef.Primitive, TypeRef.Array, TypeRef.Resolved, TypeRef.Unresolved {

    /** The nine primitive forms, plus {@code void}. */
    enum Primitive implements TypeRef {
        VOID, BOOLEAN, BYTE, CHAR, SHORT, INT, LONG, FLOAT, DOUBLE
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

    ConcurrentMap<String, Resolved> RESOLVED_CACHE = new ConcurrentHashMap<>(1 << 13);
    ConcurrentMap<String, Unresolved> UNRESOLVED_CACHE = new ConcurrentHashMap<>(1 << 10);

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
}
