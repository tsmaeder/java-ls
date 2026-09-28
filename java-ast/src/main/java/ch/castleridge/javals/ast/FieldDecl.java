/**
 * Copyright 2026 by Castle Ridge Software GmbH
 *
 * Author: Thomas Mäder, Castle Ridge Software
 *
 */
package ch.castleridge.javals.ast;


public final class FieldDecl extends Declaration {

    private final int modifiers;
    private final Annotation[] annotations;
    private final TypeNode type;
    private final VarFragment[] fragments;

    public FieldDecl(int modifiers,
                     Annotation[] annotations,
                     TypeNode type,
                     VarFragment[] fragments,
                     SourceRange range) {
        super(range);
        this.modifiers = modifiers;
        this.annotations = EmptyArrays.orEmpty(annotations, EmptyArrays.ANNOTATION);
        this.type = type;
        this.fragments = EmptyArrays.orEmpty(fragments, EmptyArrays.VAR_FRAGMENT);
        for (Node __c : this.annotations) __c.setParent(this);
        if (type != null) type.setParent(this);
        for (Node __c : this.fragments) __c.setParent(this);
    }

    public int modifiers() {
        return modifiers;
    }

    public Annotation[] annotations() {
        return annotations;
    }

    public TypeNode type() {
        return type;
    }

    public VarFragment[] fragments() {
        return fragments;
    }
    @Override
    public Node nodeAt(int offset) {
        if (!covers(offset)) return null;
        Node deepest = this;
        for (Node __c : annotations) deepest = deeper(deepest, __c, offset);
        deepest = deeper(deepest, type, offset);
        for (Node __c : fragments) deepest = deeper(deepest, __c, offset);
        return deepest;
    }

    @Override
    public void accept(AstVisitor visitor) {
        visitor.visitFieldDecl(this);
    }
}
