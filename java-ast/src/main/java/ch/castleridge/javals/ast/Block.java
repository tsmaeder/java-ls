/**
 * Copyright 2026 by Castle Ridge Software GmbH
 *
 * Author: Thomas Mäder, Castle Ridge Software
 *
 */
package ch.castleridge.javals.ast;

import java.util.List;

public final class Block extends Statement {

    private final boolean staticBlock;
    private final List<Statement> statements;

    public Block(boolean staticBlock, List<Statement> statements, SourceRange range) {
        super(range);
        this.staticBlock = staticBlock;
        this.statements = statements == null ? List.of() : List.copyOf(statements);
    }

    public Block(List<Statement> statements, SourceRange range) {
        this(false, statements, range);
    }

    public boolean staticBlock() {
        return staticBlock;
    }

    public List<Statement> statements() {
        return statements;
    }

    @Override
    public List<? extends Node> children() {
        return kids(statements);
    }

    @Override
    public void accept(AstVisitor visitor) {
        visitor.visitBlock(this);
    }
}
