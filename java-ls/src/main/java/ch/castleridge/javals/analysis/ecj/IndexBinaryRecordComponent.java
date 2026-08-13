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
import org.eclipse.jdt.internal.compiler.env.IBinaryTypeAnnotation;
import org.eclipse.jdt.internal.compiler.env.IRecordComponent;
import org.eclipse.jdt.internal.compiler.impl.Constant;

import ch.castleridge.javals.indexing.model.RecordComponentEntry;

final class IndexBinaryRecordComponent implements IRecordComponent {
    private final char[] name;
    private final char[] typeName;
    private final char[] signature;
    private final IBinaryAnnotation[] annotations;

    IndexBinaryRecordComponent(RecordComponentEntry component, IndexTypeEncoding encoding) {
        this.name = component.name().toCharArray();
        this.typeName = encoding.descriptor(component.type()).toCharArray();
        String sig = encoding.fieldSignature(component.type());
        this.signature = sig == null ? null : sig.toCharArray();
        this.annotations = IndexBinaryAnnotations.of(component.annotations(), encoding);
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
        return Constant.NotAConstant;
    }

    @Override
    public char[] getGenericSignature() {
        return signature;
    }

    @Override
    public int getModifiers() {
        return 0;
    }

    @Override
    public char[] getName() {
        return name;
    }

    @Override
    public long getTagBits() {
        return 0L;
    }

    @Override
    public char[] getTypeName() {
        return typeName;
    }
}
