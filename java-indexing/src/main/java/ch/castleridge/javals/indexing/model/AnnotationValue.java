/**
 * Copyright 2026 by Anysphere Inc.
 * 
 * Licensed under the MIT License.
 * 
 * SPDX-License-Identifier: MIT
 *
 * Author: Thomas Mäder, Castle Ridge Software
 *
 */
package ch.castleridge.javals.indexing.model;

/**
 * Typed representation of a single annotation element value.
 *
 * <p>This is the leaf type stored inside
 * {@link AnnotationRef#values()}; it is the JVM annotation value grammar
 * (JLS 9.6.1) plus a {@link Unsupported} escape hatch for source-side
 * expressions the indexer cannot evaluate without a symbol table.
 *
 * <p>The model is intentionally agnostic of javac: the conversion from
 * {@code AnnotationValue} into {@code com.sun.tools.javac.code.Attribute}
 * happens in {@code IndexClassReader}, which has the
 * {@link ch.castleridge.javals.indexing.index.Index} and a
 * {@code TypeRefResolver} on hand to materialise enum constants and
 * class references into real symbols.
 */
public sealed interface AnnotationValue {

    /**
     * A boxed Java primitive value: {@link Boolean}, {@link Byte},
     * {@link Short}, {@link Character}, {@link Integer}, {@link Long},
     * {@link Float} or {@link Double}.
     */
    record Primitive(Object boxed) implements AnnotationValue {
        public Primitive {
            if (boxed == null) {
                throw new IllegalArgumentException("boxed must not be null");
            }
            if (!(boxed instanceof Boolean
                    || boxed instanceof Byte
                    || boxed instanceof Short
                    || boxed instanceof Character
                    || boxed instanceof Integer
                    || boxed instanceof Long
                    || boxed instanceof Float
                    || boxed instanceof Double)) {
                throw new IllegalArgumentException(
                        "Primitive boxed value must be a wrapper of a Java primitive; got "
                                + boxed.getClass().getName());
            }
        }
    }

    /** A {@code String} literal annotation element value. */
    record Str(String value) implements AnnotationValue {
        public Str {
            if (value == null) {
                throw new IllegalArgumentException("value must not be null");
            }
        }
    }

    /**
     * A {@code Foo.class} class literal. The referenced type may still be
     * {@link TypeRef.Unresolved} when produced by the source indexer; the
     * symbol-side converter resolves it before constructing
     * {@code Attribute.Class}.
     */
    record ClassRef(Type type) implements AnnotationValue {
        public ClassRef {
            if (type == null) {
                throw new IllegalArgumentException("type must not be null");
            }
        }
    }

    /**
     * An enum constant reference such as
     * {@code java.lang.annotation.ElementType.METHOD}. {@code enumType}
     * may be {@link TypeRef.Unresolved} when the source indexer could
     * only see an unqualified identifier; the converter falls back to
     * {@link Unsupported} if it cannot bind it to an enum
     * {@code VarSymbol}.
     */
    record EnumConst(Type enumType, String constant) implements AnnotationValue {
        public EnumConst {
            if (enumType == null) {
                throw new IllegalArgumentException("enumType must not be null");
            }
            if (constant == null || constant.isEmpty()) {
                throw new IllegalArgumentException("constant must be non-empty");
            }
        }
    }

    /**
     * An array element value, e.g. the right-hand side of
     * {@code @SuppressWarnings({"a", "b"})}. {@code elements} preserves
     * source order.
     */
    record Arr(AnnotationValue[] elements) implements AnnotationValue {
        public Arr {
            elements = EmptyArrays.orEmpty(elements, EmptyArrays.ANNOTATION_VALUE);
        }
    }

    /** A nested annotation, as in {@code @Container(@Inner(...))}. */
    record Nested(AnnotationRef annotation) implements AnnotationValue {
        public Nested {
            if (annotation == null) {
                throw new IllegalArgumentException("annotation must not be null");
            }
        }
    }

    /**
     * Best-effort sentinel for source expressions the indexer cannot
     * reduce to one of the other shapes (binary expressions, references
     * to {@code static final} constants in other compilation units,
     * etc.). The compiler-side converter typically drops these from the
     * attached element map; javac then treats the element as
     * unspecified, which is the right semantic for a value we don't
     * actually know.
     */
    record Unsupported(String reason) implements AnnotationValue {
        public Unsupported {
            reason = reason == null ? "" : reason;
        }
    }
}
