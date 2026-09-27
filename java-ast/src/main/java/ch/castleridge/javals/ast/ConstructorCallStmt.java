/**
 * Copyright 2026 by Castle Ridge Software GmbH
 *
 * Author: Thomas Mäder, Castle Ridge Software
 *
 */
package ch.castleridge.javals.ast;

import java.util.List;

public final class ConstructorCallStmt extends Statement {
    private final boolean isSuper;
    private final Expression qualifier;
    private final List<TypeNode> typeArguments;
    private final List<Expression> arguments;
    private final MethodSymbol symbol;
    public ConstructorCallStmt(boolean isSuper, Expression qualifier, List<TypeNode> typeArguments,
                               List<Expression> arguments, MethodSymbol symbol, SourceRange range) {
        super(range);
        this.isSuper = isSuper;
        this.qualifier = qualifier;
        this.typeArguments = typeArguments == null ? List.of() : List.copyOf(typeArguments);
        this.arguments = arguments == null ? List.of() : List.copyOf(arguments);
        this.symbol = symbol;
        if (qualifier != null) qualifier.setParent(this);
        for (Node __c : this.typeArguments) __c.setParent(this);
        for (Node __c : this.arguments) __c.setParent(this);
    }
    public boolean isSuper() { return isSuper; }
    public Expression qualifier() { return qualifier; }
    public List<TypeNode> typeArguments() { return typeArguments; }
    public List<Expression> arguments() { return arguments; }
    public MethodSymbol symbol() { return symbol; }
    @Override
    public Node nodeAt(int offset) {
        if (!covers(offset)) return null;
        Node deepest = this;
        deepest = deeper(deepest, qualifier, offset);
        for (Node __c : typeArguments) deepest = deeper(deepest, __c, offset);
        for (Node __c : arguments) deepest = deeper(deepest, __c, offset);
        return deepest;
    }

    @Override
    public void accept(AstVisitor visitor) { visitor.visitConstructorCallStmt(this); }
}
