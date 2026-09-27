/**
 * Copyright 2026 by Castle Ridge Software GmbH
 *
 * Author: Thomas Mäder, Castle Ridge Software
 *
 */
package ch.castleridge.javals.ast;

import java.util.List;

public final class MemberRefExpr extends Expression {

    public enum Mode { INVOKE, NEW }

    private final Expression qualifierExpr;
    private final TypeNode qualifierType;
    private final Mode mode;
    private final Identifier name;
    private final List<TypeNode> typeArguments;

    public MemberRefExpr(Expression qualifierExpr,
                         TypeNode qualifierType,
                         Mode mode,
                         Identifier name,
                         List<TypeNode> typeArguments,
                         JType type,
                         SourceRange range) {
        super(range, type);
        this.qualifierExpr = qualifierExpr;
        this.qualifierType = qualifierType;
        this.mode = mode == null ? Mode.INVOKE : mode;
        this.name = name;
        this.typeArguments = typeArguments == null ? List.of() : List.copyOf(typeArguments);
        if (qualifierExpr != null) qualifierExpr.setParent(this);
        if (qualifierType != null) qualifierType.setParent(this);
        if (name != null) name.setParent(this);
        for (Node __c : this.typeArguments) __c.setParent(this);
    }

    public Expression qualifierExpr() {
        return qualifierExpr;
    }

    public TypeNode qualifierType() {
        return qualifierType;
    }

    public Mode mode() {
        return mode;
    }

    public Identifier name() {
        return name;
    }

    public List<TypeNode> typeArguments() {
        return typeArguments;
    }
    @Override
    public Node nodeAt(int offset) {
        if (!covers(offset)) return null;
        Node deepest = this;
        deepest = deeper(deepest, qualifierExpr, offset);
        deepest = deeper(deepest, qualifierType, offset);
        deepest = deeper(deepest, name, offset);
        for (Node __c : typeArguments) deepest = deeper(deepest, __c, offset);
        return deepest;
    }

    @Override
    public void accept(AstVisitor visitor) {
        visitor.visitMemberRefExpr(this);
    }
}
