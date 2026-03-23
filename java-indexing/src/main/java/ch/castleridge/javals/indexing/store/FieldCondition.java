package ch.castleridge.javals.indexing.store;

/**
 * One conjunct in a {@link SearchPredicate}: the field value must contain the substring.
 */
public record FieldCondition(String fieldName, String substring) {
    public FieldCondition {
        if (fieldName == null || fieldName.isEmpty()) {
            throw new IllegalArgumentException("fieldName must be non-empty");
        }
        if (substring == null) {
            throw new IllegalArgumentException("substring must not be null");
        }
    }
}
