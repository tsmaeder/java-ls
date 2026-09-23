/**
 * Copyright 2026 by Castle Ridge Software GmbH
 *
 * Author: Thomas Mäder, Castle Ridge Software
 *
 */
package ch.castleridge.javals.ast;

import java.util.List;
import java.util.Set;

public final class ConstructorDecl extends Declaration {

    private final Set<Modifier> modifiers;
    private final List<Annotation> annotations;
    private final List<TypeParamDecl> typeParams;
    private final Identifier name;
    private final ReceiverParam receiver;
    private final List<ParamDecl> parameters;
    private final List<TypeNode> thrown;
    private final Block body;
    private final MethodSymbol symbol;

    public ConstructorDecl(Set<Modifier> modifiers,
                           List<Annotation> annotations,
                           List<TypeParamDecl> typeParams,
                           Identifier name,
                           ReceiverParam receiver,
                           List<ParamDecl> parameters,
                           List<TypeNode> thrown,
                           Block body,
                           MethodSymbol symbol,
                           SourceRange range) {
        super(range);
        this.modifiers = MethodDecl.copyMods(modifiers);
        this.annotations = annotations == null ? List.of() : List.copyOf(annotations);
        this.typeParams = typeParams == null ? List.of() : List.copyOf(typeParams);
        this.name = name;
        this.receiver = receiver;
        this.parameters = parameters == null ? List.of() : List.copyOf(parameters);
        this.thrown = thrown == null ? List.of() : List.copyOf(thrown);
        this.body = body;
        this.symbol = symbol;
    }

    public Set<Modifier> modifiers() {
        return modifiers;
    }

    public List<Annotation> annotations() {
        return annotations;
    }

    public List<TypeParamDecl> typeParams() {
        return typeParams;
    }

    public Identifier name() {
        return name;
    }

    public ReceiverParam receiver() {
        return receiver;
    }

    public List<ParamDecl> parameters() {
        return parameters;
    }

    public List<TypeNode> thrown() {
        return thrown;
    }

    public Block body() {
        return body;
    }

    public MethodSymbol symbol() {
        return symbol;
    }

    @Override
    public List<? extends Node> children() {
        return kids(annotations, typeParams, name, receiver, parameters, thrown, body);
    }

    @Override
    public void accept(AstVisitor visitor) {
        visitor.visitConstructorDecl(this);
    }
}
