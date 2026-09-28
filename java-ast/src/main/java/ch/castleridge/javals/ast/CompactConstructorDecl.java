/**
 * Copyright 2026 by Castle Ridge Software GmbH
 *
 * Author: Thomas Mäder, Castle Ridge Software
 *
 */
package ch.castleridge.javals.ast;


public final class CompactConstructorDecl extends Declaration {

    private final int modifiers;
    private final Annotation[] annotations;
    private final Identifier name;
    private final Block body;
    private final MethodSymbol symbol;

    public CompactConstructorDecl(int modifiers,
                                  Annotation[] annotations,
                                  Identifier name,
                                  Block body,
                                  MethodSymbol symbol,
                                  SourceRange range) {
        super(range);
        this.modifiers = modifiers;
        this.annotations = EmptyArrays.orEmpty(annotations, EmptyArrays.ANNOTATION);
        this.name = name;
        this.body = body;
        this.symbol = symbol;
        for (Node __c : this.annotations) __c.setParent(this);
        if (name != null) name.setParent(this);
        if (body != null) body.setParent(this);
    }

    public int modifiers() {
        return modifiers;
    }

    public Annotation[] annotations() {
        return annotations;
    }

    public Identifier name() {
        return name;
    }

    public Block body() {
        return body;
    }

    public MethodSymbol symbol() {
        return symbol;
    }
    @Override
    public Node nodeAt(int offset) {
        if (!covers(offset)) return null;
        Node deepest = this;
        for (Node __c : annotations) deepest = deeper(deepest, __c, offset);
        deepest = deeper(deepest, name, offset);
        deepest = deeper(deepest, body, offset);
        return deepest;
    }

    @Override
    public void accept(AstVisitor visitor) {
        visitor.visitCompactConstructorDecl(this);
    }
}
