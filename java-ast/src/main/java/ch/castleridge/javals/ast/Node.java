/**
 * Copyright 2026 by Castle Ridge Software GmbH
 *
 * Author: Thomas Mäder, Castle Ridge Software
 *
 */
package ch.castleridge.javals.ast;

/**
 * Owned attributed AST node. Structure and attribution are fixed at construction;
 * only {@link #parent()} is set afterward by the owning parent's constructor.
 */
public abstract sealed class Node
        permits Expression, Statement, TypeNode, Pattern, Declaration, ModuleDirective, CaseLabel,
                CompilationUnit, Identifier, ImportDecl, AnnoArg, SwitchArm, CatchClause,
                ReceiverParam, VarFragment, TypeParamDecl, ModuleDecl {

    private Node parent;
    private final SourceRange range;

    protected Node(SourceRange range) {
        this.range = range == null ? SourceRange.NONE : range;
    }

    public final CompilationUnit cu() {
        Node n = this;
        while (n != null) {
            if (n instanceof CompilationUnit unit) return unit;
            n = n.parent;
        }
        return null;
    }

    public final Node parent() {
        return parent;
    }

    public final SourceRange range() {
        return range;
    }

    public final boolean hasRange() {
        return range.isPresent();
    }

    public final <T extends Node> T enclosing(Class<T> type) {
        Node n = parent;
        while (n != null) {
            if (type.isInstance(n)) return type.cast(n);
            n = n.parent;
        }
        return null;
    }

    public abstract void accept(AstVisitor visitor);

    /** Deepest node covering {@code offset}, or {@code null} if this node does not cover it. */
    public abstract Node nodeAt(int offset);

    final void setParent(Node parent) {
        if (parent == null) throw new IllegalArgumentException("parent");
        if (this.parent != null && this.parent != parent) {
            throw new IllegalStateException("child already adopted by another parent");
        }
        this.parent = parent;
    }

    protected final boolean covers(int offset) {
        return !hasRange() || (offset >= range.start() && offset < range.end());
    }

    protected final Node deeper(Node deepest, Node child, int offset) {
        if (child == null) return deepest;
        Node hit = child.nodeAt(offset);
        return hit != null ? hit : deepest;
    }
}
