package ch.castleridge.javals.indexing.model;

import java.util.Map;

/**
 * Minimal representation of an annotation attached to a declaration. Only the
 * annotation's JVM binary name and a shallow map of element values are kept;
 * this is sufficient to reconstruct the handful of compile-affecting
 * annotations ({@code @Deprecated}, {@code @FunctionalInterface},
 * {@code @PolymorphicSignature}) that the symbol synthesizer cares about.
 */
public record AnnotationRef(String jvmName, Map<String, Object> values) {
    public AnnotationRef {
        values = values == null ? Map.of() : Map.copyOf(values);
    }
}
