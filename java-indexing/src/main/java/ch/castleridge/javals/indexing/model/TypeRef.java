package ch.castleridge.javals.indexing.model;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * A class-type reference that may need name resolution against the
 * classpath and enclosing compilation unit.
 *
 * <p>{@link Resolved} carries a fully-qualified JVM binary name; {@link
 * Unresolved} carries only a simple name to be resolved later via
 * {@link SourceResolutionHints}.
 */
public sealed interface TypeRef extends Type
        permits TypeRef.Resolved, TypeRef.Unresolved {

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

    /** Return a cached {@link Resolved} for {@code jvmBinaryName}. */
    static Resolved resolved(String jvmBinaryName) {
        if (jvmBinaryName == null || jvmBinaryName.isEmpty()) {
            throw new IllegalArgumentException("jvmBinaryName must be non-empty");
        }
        Resolved cached = RESOLVED_CACHE.get(jvmBinaryName);
        if (cached != null) return cached;
        Resolved made = new Resolved(jvmBinaryName);
        Resolved prior = RESOLVED_CACHE.putIfAbsent(jvmBinaryName, made);
        return prior == null ? made : prior;
    }

    /** Return a cached {@link Unresolved} for {@code simpleName}. */
    static Unresolved unresolved(String simpleName) {
        if (simpleName == null || simpleName.isEmpty()) {
            throw new IllegalArgumentException("simpleName must be non-empty");
        }
        Unresolved cached = UNRESOLVED_CACHE.get(simpleName);
        if (cached != null) return cached;
        Unresolved made = new Unresolved(simpleName);
        Unresolved prior = UNRESOLVED_CACHE.putIfAbsent(simpleName, made);
        return prior == null ? made : prior;
    }
}
