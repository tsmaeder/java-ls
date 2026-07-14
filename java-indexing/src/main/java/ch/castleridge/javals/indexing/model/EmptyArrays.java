package ch.castleridge.javals.indexing.model;

import java.util.Arrays;
import java.util.List;

/**
 * Shared empty arrays for the in-memory index model. Empty member lists are
 * common; sharing one zero-length array per element type avoids allocating a
 * distinct empty array (and the List wrapper) per entry.
 */
public final class EmptyArrays {

    public static final AnnotationRef[] ANNOTATION_REF = new AnnotationRef[0];
    public static final AnnotationValue[] ANNOTATION_VALUE = new AnnotationValue[0];
    public static final FieldEntry[] FIELD = new FieldEntry[0];
    public static final MethodEntry[] METHOD = new MethodEntry[0];
    public static final ParameterEntry[] PARAMETER = new ParameterEntry[0];
    public static final RecordComponentEntry[] RECORD_COMPONENT = new RecordComponentEntry[0];
    public static final Type[] TYPE = new Type[0];
    public static final TypeParamRef[] TYPE_PARAM = new TypeParamRef[0];
    public static final TypeRef[] TYPE_REF = new TypeRef[0];
    public static final String[] STRING = new String[0];
    public static final ModuleEntry.Requires[] REQUIRES = new ModuleEntry.Requires[0];
    public static final ModuleEntry.Exports[] EXPORTS = new ModuleEntry.Exports[0];
    public static final ModuleEntry.Opens[] OPENS = new ModuleEntry.Opens[0];
    public static final ModuleEntry.Provides[] PROVIDES = new ModuleEntry.Provides[0];

    private EmptyArrays() {}

    /**
     * Returns {@code empty} when {@code src} is null or zero-length; otherwise
     * a defensive copy of {@code src}.
     */
    public static <T> T[] copyOrEmpty(T[] src, T[] empty) {
        if (src == null || src.length == 0) {
            return empty;
        }
        return Arrays.copyOf(src, src.length);
    }

    /**
     * Returns {@code empty} when {@code list} is null or empty; otherwise
     * {@code list.toArray(empty)} (which allocates a correctly typed array).
     */
    public static <T> T[] toArray(List<T> list, T[] empty) {
        if (list == null || list.isEmpty()) {
            return empty;
        }
        return list.toArray(empty);
    }
}
