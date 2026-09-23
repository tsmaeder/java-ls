/**
 * Copyright 2026 by Castle Ridge Software GmbH
 *
 * Author: Thomas Mäder, Castle Ridge Software
 *
 */
package ch.castleridge.javals.ast;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;

public final class TypeDecl extends Declaration {

    private final TypeDeclKind kind;
    private final Set<Modifier> modifiers;
    private final List<Annotation> annotations;
    private final Identifier name;
    private final List<TypeParamDecl> typeParams;
    private final TypeNode superclass;
    private final List<TypeNode> interfaces;
    private final List<TypeNode> permits;
    private final List<RecordComponentDecl> recordComponents;
    private final List<Declaration> members;
    private final TypeSymbol symbol;

    public TypeDecl(TypeDeclKind kind,
                    Set<Modifier> modifiers,
                    List<Annotation> annotations,
                    Identifier name,
                    List<TypeParamDecl> typeParams,
                    TypeNode superclass,
                    List<TypeNode> interfaces,
                    List<TypeNode> permits,
                    List<RecordComponentDecl> recordComponents,
                    List<Declaration> members,
                    TypeSymbol symbol,
                    SourceRange range) {
        super(range);
        this.kind = kind == null ? TypeDeclKind.CLASS : kind;
        this.modifiers = modifiers == null || modifiers.isEmpty()
                ? Set.of() : Set.copyOf(EnumSet.copyOf(modifiers));
        this.annotations = annotations == null ? List.of() : List.copyOf(annotations);
        this.name = name;
        this.typeParams = typeParams == null ? List.of() : List.copyOf(typeParams);
        this.superclass = superclass;
        this.interfaces = interfaces == null ? List.of() : List.copyOf(interfaces);
        this.permits = permits == null ? List.of() : List.copyOf(permits);
        this.recordComponents = recordComponents == null ? List.of() : List.copyOf(recordComponents);
        this.members = members == null ? List.of() : List.copyOf(members);
        this.symbol = symbol;
    }

    public TypeDeclKind kind() {
        return kind;
    }

    public Set<Modifier> modifiers() {
        return modifiers;
    }

    public List<Annotation> annotations() {
        return annotations;
    }

    public Identifier name() {
        return name;
    }

    public List<TypeParamDecl> typeParams() {
        return typeParams;
    }

    public TypeNode superclass() {
        return superclass;
    }

    public List<TypeNode> interfaces() {
        return interfaces;
    }

    public List<TypeNode> permits() {
        return permits;
    }

    public List<RecordComponentDecl> recordComponents() {
        return recordComponents;
    }

    public List<Declaration> members() {
        return members;
    }

    public TypeSymbol symbol() {
        return symbol;
    }

    @Override
    public List<? extends Node> children() {
        return kids(annotations, name, typeParams, superclass, interfaces, permits, recordComponents, members);
    }

    @Override
    public void accept(AstVisitor visitor) {
        visitor.visitTypeDecl(this);
    }
}
