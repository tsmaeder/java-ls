/**
 * Copyright 2026 by Castle Ridge Software GmbH
 *
 * Author: Thomas Mäder, Castle Ridge Software
 *
 */
package ch.castleridge.javals.ast;

import java.util.ArrayList;
import java.util.List;

/**
 * Owned attributed AST node. Trees are frozen after lowering.
 */
public abstract sealed class Node
        permits Expression, Statement, TypeNode, Pattern, Declaration, ModuleDirective, CaseLabel,
                CompilationUnit, Identifier, ImportDecl, AnnoArg, SwitchArm, CatchClause,
                ReceiverParam, VarFragment, TypeParamDecl, ModuleDecl {

    private CompilationUnit cu;
    private Node parent;
    private final SourceRange range;
    private boolean frozen;

    protected Node(SourceRange range) {
        this.range = range == null ? SourceRange.NONE : range;
    }

    public final CompilationUnit cu() {
        return cu;
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

    public final boolean frozen() {
        return frozen;
    }

    public final <T extends Node> T enclosing(Class<T> type) {
        Node n = parent;
        while (n != null) {
            if (type.isInstance(n)) return type.cast(n);
            n = n.parent;
        }
        return null;
    }

    public abstract List<? extends Node> children();

    public abstract void accept(AstVisitor visitor);

    final void attach(CompilationUnit unit, Node parent) {
        this.cu = unit;
        this.parent = parent;
        for (Node child : children()) {
            if (child != null) child.attach(unit, this);
        }
    }

    final void freeze() {
        this.frozen = true;
        for (Node child : children()) {
            if (child != null) child.freeze();
        }
    }

    public final Node nodeAt(int offset) {
        if (hasRange() && (offset < range.start() || offset >= range.end())) {
            return null;
        }
        Node deepest = this;
        for (Node child : children()) {
            if (child == null) continue;
            Node hit = child.nodeAt(offset);
            if (hit != null) deepest = hit;
        }
        return deepest;
    }

    static List<Node> kids(Object... items) {
        List<Node> out = new ArrayList<>();
        addKids(out, items);
        return List.copyOf(out);
    }

    private static void addKids(List<Node> out, Object[] items) {
        for (Object item : items) {
            switch (item) {
                case null -> {}
                case Node node -> out.add(node);
                case List<?> list -> {
                    for (Object inner : list) {
                        if (inner instanceof Node node) out.add(node);
                    }
                }
                default -> {}
            }
        }
    }
}
