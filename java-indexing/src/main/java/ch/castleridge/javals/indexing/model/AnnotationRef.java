package ch.castleridge.javals.indexing.model;

import java.util.Map;

/**
 * Minimal representation of an annotation attached to a declaration. The
 * annotation's JVM binary name plus its supplied element values
 * (matching the JVM annotation grammar of JLS 9.6.1) are kept so that
 * compile-affecting annotations such as {@code @SuppressWarnings},
 * {@code @Deprecated}, {@code @Target}, {@code @Retention} and
 * {@code @Repeatable} can be reconstructed as javac
 * {@code Attribute.Compound} instances in {@code IndexClassReader}.
 *
 * <p>Source-derived references may carry {@link AnnotationValue.Unsupported}
 * leaves for expressions the indexer cannot evaluate without a symbol
 * table; the symbol-side converter drops those elements when building
 * attributes, mirroring "unspecified element" semantics.
 */
public record AnnotationRef(String jvmName, Map<String, AnnotationValue> values) {
    public AnnotationRef {
        if (jvmName == null || jvmName.isEmpty()) {
            throw new IllegalArgumentException("jvmName must be non-empty");
        }
        values = values == null ? Map.of() : Map.copyOf(values);
    }
}
