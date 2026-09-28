/**
 * Copyright 2026 by Castle Ridge Software GmbH
 *
 * Author: Thomas Mäder, Castle Ridge Software
 *
 */
package ch.castleridge.javals.ast;


public final class TryStmt extends Statement {
    private final Node[] resources;
    private final Block body;
    private final CatchClause[] catches;
    private final Block finallyBlock;
    public TryStmt(Node[] resources, Block body, CatchClause[] catches, Block finallyBlock, SourceRange range) {
        super(range);
        this.resources = EmptyArrays.orEmpty(resources, EmptyArrays.NODE);
        this.body = body;
        this.catches = EmptyArrays.orEmpty(catches, EmptyArrays.CATCH_CLAUSE);
        this.finallyBlock = finallyBlock;
        for (Node __c : this.resources) __c.setParent(this);
        if (body != null) body.setParent(this);
        for (Node __c : this.catches) __c.setParent(this);
        if (finallyBlock != null) finallyBlock.setParent(this);
    }
    public Node[] resources() { return resources; }
    public Block body() { return body; }
    public CatchClause[] catches() { return catches; }
    public Block finallyBlock() { return finallyBlock; }
    @Override
    public Node nodeAt(int offset) {
        if (!covers(offset)) return null;
        Node deepest = this;
        for (Node __c : resources) deepest = deeper(deepest, __c, offset);
        deepest = deeper(deepest, body, offset);
        for (Node __c : catches) deepest = deeper(deepest, __c, offset);
        deepest = deeper(deepest, finallyBlock, offset);
        return deepest;
    }

    @Override
    public void accept(AstVisitor visitor) { visitor.visitTryStmt(this); }
}
