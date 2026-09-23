/**
 * Copyright 2026 by Castle Ridge Software GmbH
 *
 * Author: Thomas Mäder, Castle Ridge Software
 *
 */
package ch.castleridge.javals.ast;

import java.util.List;

public final class ExportsDirective extends ModuleDirective {

    private final List<Identifier> packageName;
    private final List<List<Identifier>> targets;

    public ExportsDirective(List<Identifier> packageName, List<List<Identifier>> targets, SourceRange range) {
        super(range);
        this.packageName = packageName == null ? List.of() : List.copyOf(packageName);
        this.targets = targets == null ? List.of() : List.copyOf(targets);
    }

    public List<Identifier> packageName() {
        return packageName;
    }

    public List<List<Identifier>> targets() {
        return targets;
    }

    @Override
    public List<? extends Node> children() {
        List<Node> out = new java.util.ArrayList<>(packageName);
        for (List<Identifier> target : targets) out.addAll(target);
        return List.copyOf(out);
    }

    @Override
    public void accept(AstVisitor visitor) {
        visitor.visitExportsDirective(this);
    }
}
