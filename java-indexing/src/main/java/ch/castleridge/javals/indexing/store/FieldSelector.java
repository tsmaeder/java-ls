package ch.castleridge.javals.indexing.store;

/**
 * Selects documents to remove: those where the named field's value contains the substring.
 */
public record FieldSelector(String fieldName, String substring) {
    public FieldSelector {
        if (fieldName == null || fieldName.isEmpty()) {
            throw new IllegalArgumentException("fieldName must be non-empty");
        }
        if (substring == null) {
            throw new IllegalArgumentException("substring must not be null");
        }
    }
}
