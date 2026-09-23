/**
 * Copyright 2026 by Castle Ridge Software GmbH
 *
 * Author: Thomas Mäder, Castle Ridge Software
 *
 */
package ch.castleridge.javals.ast;

import java.util.List;

public final class TryStmt extends Statement {
    private final List<Node> resources;
    private final Block body;
    private final List<CatchClause> catches;
    private final Block finallyBlock;
    public TryStmt(List<Node> resources, Block body, List<CatchClause> catches, Block finallyBlock, SourceRange range) {
        super(range);
        this.resources = resources == null ? List.of() : List.copyOf(resources);
        this.body = body;
        this.catches = catches == null ? List.of() : List.copyOf(catches);
        this.finallyBlock = finallyBlock;
    }
    public List<Node> resources() { return resources; }
    public Block body() { return body; }
    public List<CatchClause> catches() { return catches; }
    public Block finallyBlock() { return finallyBlock; }
    @Override public List<? extends Node> children() { return kids(resources, body, catches, finallyBlock); }
    @Override public void accept(AstVisitor visitor) { visitor.visitTryStmt(this); }
}
