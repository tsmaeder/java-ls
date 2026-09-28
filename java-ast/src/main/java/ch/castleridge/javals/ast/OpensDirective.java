/**
 * Copyright 2026 by Castle Ridge Software GmbH
 *
 * Author: Thomas Mäder, Castle Ridge Software
 *
 */
package ch.castleridge.javals.ast;


public final class OpensDirective extends ModuleDirective {

    private final Identifier[] packageName;
    private final Identifier[][] targets;

    public OpensDirective(Identifier[] packageName, Identifier[][] targets, SourceRange range) {
        super(range);
        this.packageName = EmptyArrays.orEmpty(packageName, EmptyArrays.IDENTIFIER);
        this.targets = EmptyArrays.orEmpty(targets, EmptyArrays.IDENTIFIER_TABLE);
        for (Identifier name : this.packageName) name.setParent(this);
        for (Identifier[] target : this.targets) {
            for (Identifier name : target) name.setParent(this);
        }
    }

    public Identifier[] packageName() {
        return packageName;
    }

    public Identifier[][] targets() {
        return targets;
    }

    @Override
    public Node nodeAt(int offset) {
        if (!covers(offset)) return null;
        Node deepest = this;
        for (Identifier name : packageName) deepest = deeper(deepest, name, offset);
        for (Identifier[] target : targets) {
            for (Identifier name : target) deepest = deeper(deepest, name, offset);
        }
        return deepest;
    }

    @Override
    public void accept(AstVisitor visitor) {
        visitor.visitOpensDirective(this);
    }
}
