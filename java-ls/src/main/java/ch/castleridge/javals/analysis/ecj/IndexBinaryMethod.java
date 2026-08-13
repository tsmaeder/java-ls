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
import org.eclipse.jdt.internal.compiler.env.IBinaryMethod;
import org.eclipse.jdt.internal.compiler.env.IBinaryTypeAnnotation;

import ch.castleridge.javals.indexing.model.MethodEntry;
import ch.castleridge.javals.indexing.model.ParameterEntry;
import ch.castleridge.javals.indexing.model.Type;
import ch.castleridge.javals.indexing.model.TypeEntry;

final class IndexBinaryMethod implements IBinaryMethod {
    private final char[] selector;
    private final char[] descriptor;
    private final char[] signature;
    private final char[][] exceptions;
    private final char[][] argumentNames;
    private final int modifiers;
    private final long tagBits;
    private final IBinaryAnnotation[] annotations;
    private final Object defaultValue;
    private final boolean constructor;
    private final boolean clinit;

    IndexBinaryMethod(MethodEntry method, TypeEntry owner, IndexTypeEncoding encoding) {
        this.selector = method.name().toCharArray();
        this.descriptor = encoding.methodDescriptor(method).toCharArray();
        String sig = encoding.methodSignature(method);
        this.signature = sig == null ? null : sig.toCharArray();
        this.exceptions = exceptionNames(method, encoding);
        this.argumentNames = argumentNames(method);
        this.modifiers = IndexBinaryAccessFlags.methodModifiers(owner, method);
        this.tagBits = IndexBinaryAccessFlags.annotationTagBits(method.annotations());
        this.annotations = IndexBinaryAnnotations.of(method.annotations(), encoding);
        this.defaultValue = method.annotationDefault() == null
                ? null
                : IndexBinaryAnnotations.defaultValue(method.annotationDefault(), encoding);
        this.constructor = "<init>".equals(method.name());
        this.clinit = "<clinit>".equals(method.name());
    }

    /** Synthetic method not backed by an indexed {@link MethodEntry}. */
    IndexBinaryMethod(String name, String descriptor, int modifiers) {
        this(name, descriptor, null, null, modifiers);
    }

    /**
     * Synthetic method not backed by an indexed {@link MethodEntry}, carrying
     * a generic signature and/or parameter names. Record accessors and
     * canonical constructors need both: without the signature their component
     * types would erase (a {@code List<String>} component would only be
     * assignable as a raw {@code List}).
     */
    IndexBinaryMethod(String name, String descriptor, String signature,
                      char[][] argumentNames, int modifiers) {
        this.selector = name.toCharArray();
        this.descriptor = descriptor.toCharArray();
        this.signature = signature == null ? null : signature.toCharArray();
        this.exceptions = null;
        this.argumentNames = argumentNames;
        this.modifiers = modifiers;
        this.tagBits = 0L;
        this.annotations = null;
        this.defaultValue = null;
        this.constructor = "<init>".equals(name);
        this.clinit = "<clinit>".equals(name);
    }

    @Override
    public IBinaryAnnotation[] getAnnotations() {
        return annotations;
    }

    @Override
    public Object getDefaultValue() {
        return defaultValue;
    }

    @Override
    public char[][] getExceptionTypeNames() {
        return exceptions;
    }

    @Override
    public char[] getGenericSignature() {
        return signature;
    }

    @Override
    public char[] getMethodDescriptor() {
        return descriptor;
    }

    @Override
    public int getModifiers() {
        return modifiers;
    }

    @Override
    public char[][] getArgumentNames() {
        return argumentNames;
    }

    @Override
    public IBinaryAnnotation[] getParameterAnnotations(int index, char[] classFileName) {
        return null;
    }

    @Override
    public int getAnnotatedParametersCount() {
        return 0;
    }

    @Override
    public char[] getSelector() {
        return selector;
    }

    @Override
    public long getTagBits() {
        return tagBits;
    }

    @Override
    public boolean isClinit() {
        return clinit;
    }

    @Override
    public boolean isConstructor() {
        return constructor;
    }

    @Override
    public IBinaryTypeAnnotation[] getTypeAnnotations() {
        return null;
    }

    private static char[][] exceptionNames(MethodEntry method, IndexTypeEncoding encoding) {
        Type[] thrown = method.throwsTypes();
        if (thrown.length == 0) return null;
        char[][] names = new char[thrown.length][];
        for (int i = 0; i < thrown.length; i++) {
            names[i] = encoding.erasedJvm(thrown[i]).toCharArray();
        }
        return names;
    }

    private static char[][] argumentNames(MethodEntry method) {
        ParameterEntry[] parameters = method.parameters();
        if (parameters.length == 0) return null;
        boolean any = false;
        char[][] names = new char[parameters.length][];
        for (int i = 0; i < parameters.length; i++) {
            String name = parameters[i].name();
            if (name != null && !name.isEmpty()) {
                names[i] = name.toCharArray();
                any = true;
            } else {
                names[i] = ("arg" + i).toCharArray();
            }
        }
        return any ? names : null;
    }
}
