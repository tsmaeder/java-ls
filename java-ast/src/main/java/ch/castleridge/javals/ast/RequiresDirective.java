/**
 * Copyright 2026 by Castle Ridge Software GmbH
 *
 * Author: Thomas Mäder, Castle Ridge Software
 *
 */
package ch.castleridge.javals.ast;

import java.util.List;

public final class RequiresDirective extends ModuleDirective {

    private final boolean transitive;
    private final boolean staticPhase;
    private final List<Identifier> moduleName;

    public RequiresDirective(boolean transitive, boolean staticPhase, List<Identifier> moduleName, SourceRange range) {
        super(range);
        this.transitive = transitive;
        this.staticPhase = staticPhase;
        this.moduleName = moduleName == null ? List.of() : List.copyOf(moduleName);
        for (Node __c : this.moduleName) __c.setParent(this);
    }

    public boolean transitive() {
        return transitive;
    }

    public boolean staticPhase() {
        return staticPhase;
    }

    public List<Identifier> moduleName() {
        return moduleName;
    }
    @Override
    public Node nodeAt(int offset) {
        if (!covers(offset)) return null;
        Node deepest = this;
        for (Node __c : moduleName) deepest = deeper(deepest, __c, offset);
        return deepest;
    }

    @Override
    public void accept(AstVisitor visitor) {
        visitor.visitRequiresDirective(this);
    }
}
