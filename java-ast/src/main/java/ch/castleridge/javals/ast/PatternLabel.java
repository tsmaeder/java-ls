/**
 * Copyright 2026 by Castle Ridge Software GmbH
 *
 * Author: Thomas Mäder, Castle Ridge Software
 *
 */
package ch.castleridge.javals.ast;

import java.util.List;

public final class PatternLabel extends CaseLabel {
    private final Pattern pattern;
    private final Expression guard;
    public PatternLabel(Pattern pattern, Expression guard, SourceRange range) {
        super(range);
        this.pattern = pattern;
        this.guard = guard;
        if (pattern != null) pattern.setParent(this);
        if (guard != null) guard.setParent(this);
    }
    public Pattern pattern() { return pattern; }
    public Expression guard() { return guard; }
    @Override
    public Node nodeAt(int offset) {
        if (!covers(offset)) return null;
        Node deepest = this;
        deepest = deeper(deepest, pattern, offset);
        deepest = deeper(deepest, guard, offset);
        return deepest;
    }

    @Override
    public void accept(AstVisitor visitor) { visitor.visitPatternLabel(this); }
}
