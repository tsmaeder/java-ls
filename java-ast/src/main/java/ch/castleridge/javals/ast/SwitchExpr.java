/**
 * Copyright 2026 by Castle Ridge Software GmbH
 *
 * Author: Thomas Mäder, Castle Ridge Software
 *
 */
package ch.castleridge.javals.ast;

import java.util.List;

public final class SwitchExpr extends Expression {

    private final Expression selector;
    private final List<SwitchArm> arms;

    public SwitchExpr(Expression selector, List<SwitchArm> arms, JType type, SourceRange range) {
        super(range, type);
        this.selector = selector;
        this.arms = arms == null ? List.of() : List.copyOf(arms);
        if (selector != null) selector.setParent(this);
        for (Node __c : this.arms) __c.setParent(this);
    }

    public Expression selector() {
        return selector;
    }

    public List<SwitchArm> arms() {
        return arms;
    }
    @Override
    public Node nodeAt(int offset) {
        if (!covers(offset)) return null;
        Node deepest = this;
        deepest = deeper(deepest, selector, offset);
        for (Node __c : arms) deepest = deeper(deepest, __c, offset);
        return deepest;
    }

    @Override
    public void accept(AstVisitor visitor) {
        visitor.visitSwitchExpr(this);
    }
}
