package ch.castleridge.javals.indexing.model;

import java.util.List;

/**
 * A single field declaration attached to a {@link TypeEntry}.
 *
 * <p>The declared type is captured as a {@link TypeRef}; for source-derived
 * fields this may be {@link TypeRef.Unresolved}, to be resolved later by
 * the class reader using the {@link SourceResolutionHints} on the
 * enclosing type.
 *
 * <p>{@link #constantValue()} is non-null when this field carries a
 * JLS-style compile-time constant (a {@code static final} primitive or
 * {@code String}). The actual value is captured as the matching boxed
 * type ({@link Integer}/{@link Long}/{@link Float}/{@link Double}/
 * {@link String}), matching the convention used by javac's
 * {@code ClassReader} so that callers can pipe it straight into
 * {@code VarSymbol.setData(...)} and javac can constant-fold use sites
 * against it.
 *
 * <p>Bytecode-derived entries populate this from the {@code ConstantValue}
 * classfile attribute; source-derived entries do a best-effort literal
 * extraction (typed literals and unary-minus over a numeric literal). A
 * {@code null} value just means "no compile-time constant known", and
 * downstream code falls back to the same behaviour as before.
 */
public record FieldEntry(
        String resourceUri,
        String jvmOwnerName,
        int modifiers,
        String name,
        TypeRef type,
        Object constantValue,
        List<AnnotationRef> annotations) implements IndexEntry {

    public FieldEntry {
        annotations = annotations == null ? List.of() : List.copyOf(annotations);
    }

    /** Backward-compatible constructor without a constant value. */
    public FieldEntry(
            String resourceUri,
            String jvmOwnerName,
            int modifiers,
            String name,
            TypeRef type,
            List<AnnotationRef> annotations) {
        this(resourceUri, jvmOwnerName, modifiers, name, type, null, annotations);
    }

    @Override
    public EntryKind kind() {
        return EntryKind.FIELD;
    }
}
