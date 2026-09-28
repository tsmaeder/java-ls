/**
 * Copyright 2026 by Castle Ridge Software GmbH
 *
 * Author: Thomas Mäder, Castle Ridge Software
 *
 */
package ch.castleridge.javals.ast;


public final class InitializerDecl extends Declaration {

    private final boolean staticInit;
    private final Block body;

    public InitializerDecl(boolean staticInit, Block body, SourceRange range) {
        super(range);
        this.staticInit = staticInit;
        this.body = body;
        if (body != null) body.setParent(this);
    }

    public boolean staticInit() {
        return staticInit;
    }

    public Block body() {
        return body;
    }
    @Override
    public Node nodeAt(int offset) {
        if (!covers(offset)) return null;
        Node deepest = this;
        deepest = deeper(deepest, body, offset);
        return deepest;
    }

    @Override
    public void accept(AstVisitor visitor) {
        visitor.visitInitializerDecl(this);
    }
}
