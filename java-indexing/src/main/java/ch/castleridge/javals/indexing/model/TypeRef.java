package ch.castleridge.javals.indexing.model;

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
     */
    record Unresolved(String simpleName) implements TypeRef {
        public Unresolved {
            if (simpleName == null || simpleName.isEmpty()) {
                throw new IllegalArgumentException("simpleName must be non-empty");
            }
        }
    }
}
