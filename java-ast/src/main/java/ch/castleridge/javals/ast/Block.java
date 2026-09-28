/**
 * Copyright 2026 by Castle Ridge Software GmbH
 *
 * Author: Thomas Mäder, Castle Ridge Software
 *
 */
package ch.castleridge.javals.ast;


public final class Block extends Statement {

    private final boolean staticBlock;
    private final Statement[] statements;

    public Block(boolean staticBlock, Statement[] statements, SourceRange range) {
        super(range);
        this.staticBlock = staticBlock;
        this.statements = EmptyArrays.orEmpty(statements, EmptyArrays.STATEMENT);
        for (Node __c : this.statements) __c.setParent(this);
    }

    public Block(Statement[] statements, SourceRange range) {
        this(false, statements, range);
    }

    public boolean staticBlock() {
        return staticBlock;
    }

    public Statement[] statements() {
        return statements;
    }
    @Override
    public Node nodeAt(int offset) {
        if (!covers(offset)) return null;
        Node deepest = this;
        for (Node __c : statements) deepest = deeper(deepest, __c, offset);
        return deepest;
    }

    @Override
    public void accept(AstVisitor visitor) {
        visitor.visitBlock(this);
    }
}
