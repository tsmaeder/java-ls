package ch.castleridge.javals.indexing.model;

/**
 * A single record component declared in the header of a record type.
 *
 * <p>The JVM exposes record components as a structured attribute, distinct
 * from the synthetic field and accessor method that the compiler generates
 * for each one. Tools that walk
 * {@code java.lang.Class.getRecordComponents()} (and javac itself, via
 * {@code ClassSymbol.getRecordComponents}) need to see this attribute,
 * not just the synthesised field/accessor pair.
 *
 * <p>Bytecode-derived entries populate this from the {@code Record}
 * classfile attribute; source-derived entries currently leave records
 * with an empty {@link TypeEntry#recordComponents()} list (still marked
 * with {@code ACC_RECORD}) - the canonical accessor methods are still
 * present, so name lookups against the record API keep working, but the
 * {@code RecordComponent} mirror is only fully populated for indexed
 * bytecode for now.
 */
public record RecordComponentEntry(
        String name,
        Type type,
        AnnotationRef[] annotations) {

    public RecordComponentEntry {
        if (name == null || name.isEmpty()) {
            throw new IllegalArgumentException("name must be non-empty");
        }
        if (type == null) {
            throw new IllegalArgumentException("type must not be null");
        }
        annotations = EmptyArrays.orEmpty(annotations, EmptyArrays.ANNOTATION_REF);
    }
}
