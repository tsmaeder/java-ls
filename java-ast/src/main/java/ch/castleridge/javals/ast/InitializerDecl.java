/**
 * Copyright 2026 by Castle Ridge Software GmbH
 *
 * Author: Thomas Mäder, Castle Ridge Software
 *
 */
package ch.castleridge.javals.ast;

import java.util.List;

public final class InitializerDecl extends Declaration {

    private final boolean staticInit;
    private final Block body;

    public InitializerDecl(boolean staticInit, Block body, SourceRange range) {
        super(range);
        this.staticInit = staticInit;
        this.body = body;
    }

    public boolean staticInit() {
        return staticInit;
    }

    public Block body() {
        return body;
    }

    @Override
    public List<? extends Node> children() {
        return kids(body);
    }

    @Override
    public void accept(AstVisitor visitor) {
        visitor.visitInitializerDecl(this);
    }
}
