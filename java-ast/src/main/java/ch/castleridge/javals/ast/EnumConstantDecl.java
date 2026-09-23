/**
 * Copyright 2026 by Castle Ridge Software GmbH
 *
 * Author: Thomas Mäder, Castle Ridge Software
 *
 */
package ch.castleridge.javals.ast;

import java.util.List;

public final class EnumConstantDecl extends Declaration {

    private final List<Annotation> annotations;
    private final Identifier name;
    private final List<Expression> arguments;
    private final TypeDecl body;
    private final EnumConstantSymbol symbol;

    public EnumConstantDecl(List<Annotation> annotations,
                            Identifier name,
                            List<Expression> arguments,
                            TypeDecl body,
                            EnumConstantSymbol symbol,
                            SourceRange range) {
        super(range);
        this.annotations = annotations == null ? List.of() : List.copyOf(annotations);
        this.name = name;
        this.arguments = arguments == null ? List.of() : List.copyOf(arguments);
        this.body = body;
        this.symbol = symbol;
    }

    public List<Annotation> annotations() {
        return annotations;
    }

    public Identifier name() {
        return name;
    }

    public List<Expression> arguments() {
        return arguments;
    }

    public TypeDecl body() {
        return body;
    }

    public EnumConstantSymbol symbol() {
        return symbol;
    }

    @Override
    public List<? extends Node> children() {
        return kids(annotations, name, arguments, body);
    }

    @Override
    public void accept(AstVisitor visitor) {
        visitor.visitEnumConstantDecl(this);
    }
}
