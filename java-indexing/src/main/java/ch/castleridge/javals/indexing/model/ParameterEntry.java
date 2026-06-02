package ch.castleridge.javals.indexing.model;

import java.util.List;

/**
 * A single formal parameter of a {@link MethodEntry}.
 *
 * <p>Carries the declared type, the parameter modifiers (e.g.
 * {@code ACC_FINAL}, {@code ACC_SYNTHETIC}, {@code ACC_MANDATED}) and any
 * declaration annotations. {@link #name()} may be {@code null} when the
 * underlying source of truth (bytecode without a {@code MethodParameters}
 * attribute, or {@code SKIP_DEBUG} parsing options) didn't expose a name -
 * callers should fall back to a synthetic {@code "arg" + index} in that
 * case.
 */
public record ParameterEntry(
        String name,
        int modifiers,
        TypeRef type,
        List<AnnotationRef> annotations) {

    public ParameterEntry {
        if (type == null) {
            throw new IllegalArgumentException("type must not be null");
        }
        annotations = annotations == null ? List.of() : List.copyOf(annotations);
    }

    /** Convenience for the common name-only / no-annotations case. */
    public static ParameterEntry of(TypeRef type) {
        return new ParameterEntry(null, 0, type, List.of());
    }
}
