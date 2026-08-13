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
package ch.castleridge.javals.analysis.ecj;

import org.eclipse.jdt.internal.compiler.env.IBinaryAnnotation;
import org.eclipse.jdt.internal.compiler.env.IBinaryField;
import org.eclipse.jdt.internal.compiler.env.IBinaryTypeAnnotation;
import org.eclipse.jdt.internal.compiler.impl.BooleanConstant;
import org.eclipse.jdt.internal.compiler.impl.ByteConstant;
import org.eclipse.jdt.internal.compiler.impl.CharConstant;
import org.eclipse.jdt.internal.compiler.impl.Constant;
import org.eclipse.jdt.internal.compiler.impl.DoubleConstant;
import org.eclipse.jdt.internal.compiler.impl.FloatConstant;
import org.eclipse.jdt.internal.compiler.impl.IntConstant;
import org.eclipse.jdt.internal.compiler.impl.LongConstant;
import org.eclipse.jdt.internal.compiler.impl.ShortConstant;
import org.eclipse.jdt.internal.compiler.impl.StringConstant;

import ch.castleridge.javals.indexing.model.FieldEntry;
import ch.castleridge.javals.indexing.model.TypeEntry;

final class IndexBinaryField implements IBinaryField {
    private final TypeEntry owner;
    private final char[] name;
    private final char[] typeName;
    private final char[] signature;
    private final int modifiers;
    private final long tagBits;
    private final Constant constant;
    private final IBinaryAnnotation[] annotations;

    IndexBinaryField(FieldEntry field, TypeEntry owner, IndexTypeEncoding encoding) {
        this.owner = owner;
        this.name = field.name().toCharArray();
        this.typeName = encoding.descriptor(field.type()).toCharArray();
        String sig = encoding.fieldSignature(field.type());
        this.signature = sig == null ? null : sig.toCharArray();
        this.modifiers = IndexBinaryAccessFlags.fieldModifiers(owner, field);
        this.tagBits = IndexBinaryAccessFlags.annotationTagBits(field.annotations());
        this.constant = constantOf(field);
        this.annotations = IndexBinaryAnnotations.of(field.annotations(), encoding);
    }

    @Override
    public IBinaryAnnotation[] getAnnotations() {
        return annotations;
    }

    @Override
    public IBinaryTypeAnnotation[] getTypeAnnotations() {
        return null;
    }

    @Override
    public Constant getConstant() {
        return constant;
    }

    @Override
    public char[] getGenericSignature() {
        return signature;
    }

    @Override
    public int getModifiers() {
        return modifiers;
    }

    @Override
    public char[] getName() {
        return name;
    }

    @Override
    public long getTagBits() {
        return tagBits;
    }

    @Override
    public char[] getTypeName() {
        return typeName;
    }

    private Constant constantOf(FieldEntry entry) {
        int flags = IndexBinaryAccessFlags.fieldModifiers(owner, entry);
        if ((flags & org.eclipse.jdt.internal.compiler.classfmt.ClassFileConstants.AccStatic) == 0
                || (flags & org.eclipse.jdt.internal.compiler.classfmt.ClassFileConstants.AccFinal) == 0) {
            return Constant.NotAConstant;
        }
        Object value = entry.constantValue();
        if (value == null) return Constant.NotAConstant;
        return switch (value) {
            case Boolean b -> BooleanConstant.fromValue(b);
            case Byte b -> ByteConstant.fromValue(b);
            case Short s -> ShortConstant.fromValue(s);
            case Character c -> CharConstant.fromValue(c);
            case Integer i -> IntConstant.fromValue(i);
            case Long l -> LongConstant.fromValue(l);
            case Float f -> FloatConstant.fromValue(f);
            case Double d -> DoubleConstant.fromValue(d);
            case String s -> StringConstant.fromValue(s);
            default -> Constant.NotAConstant;
        };
    }
}
