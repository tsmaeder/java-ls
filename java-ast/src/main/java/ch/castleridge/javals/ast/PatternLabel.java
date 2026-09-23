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
    }
    public Pattern pattern() { return pattern; }
    public Expression guard() { return guard; }
    @Override public List<? extends Node> children() { return kids(pattern, guard); }
    @Override public void accept(AstVisitor visitor) { visitor.visitPatternLabel(this); }
}
