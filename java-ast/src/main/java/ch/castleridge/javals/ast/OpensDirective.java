/**
 * Copyright 2026 by Castle Ridge Software GmbH
 *
 * Author: Thomas Mäder, Castle Ridge Software
 *
 */
package ch.castleridge.javals.ast;

import java.util.List;

public final class OpensDirective extends ModuleDirective {

    private final List<Identifier> packageName;
    private final List<List<Identifier>> targets;

    public OpensDirective(List<Identifier> packageName, List<List<Identifier>> targets, SourceRange range) {
        super(range);
        this.packageName = packageName == null ? List.of() : List.copyOf(packageName);
        this.targets = targets == null ? List.of() : List.copyOf(targets);
        for (Identifier name : this.packageName) name.setParent(this);
        for (List<Identifier> target : this.targets) {
            for (Identifier name : target) name.setParent(this);
        }
    }

    public List<Identifier> packageName() {
        return packageName;
    }

    public List<List<Identifier>> targets() {
        return targets;
    }

    @Override
    public Node nodeAt(int offset) {
        if (!covers(offset)) return null;
        Node deepest = this;
        for (Identifier name : packageName) deepest = deeper(deepest, name, offset);
        for (List<Identifier> target : targets) {
            for (Identifier name : target) deepest = deeper(deepest, name, offset);
        }
        return deepest;
    }

    @Override
    public void accept(AstVisitor visitor) {
        visitor.visitOpensDirective(this);
    }
}
