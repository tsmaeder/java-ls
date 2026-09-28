/**
 * Copyright 2026 by Castle Ridge Software GmbH
 *
 * Author: Thomas Mäder, Castle Ridge Software
 *
 */
package ch.castleridge.javals.ast;


public final class TypeDecl extends Declaration {

    private final TypeDeclKind kind;
    private final int modifiers;
    private final Annotation[] annotations;
    private final Identifier name;
    private final TypeParamDecl[] typeParams;
    private final TypeNode superclass;
    private final TypeNode[] interfaces;
    private final TypeNode[] permits;
    private final RecordComponentDecl[] recordComponents;
    private final Declaration[] members;
    private final TypeSymbol symbol;

    public TypeDecl(TypeDeclKind kind,
                    int modifiers,
                    Annotation[] annotations,
                    Identifier name,
                    TypeParamDecl[] typeParams,
                    TypeNode superclass,
                    TypeNode[] interfaces,
                    TypeNode[] permits,
                    RecordComponentDecl[] recordComponents,
                    Declaration[] members,
                    TypeSymbol symbol,
                    SourceRange range) {
        super(range);
        this.kind = kind == null ? TypeDeclKind.CLASS : kind;
        this.modifiers = modifiers;
        this.annotations = EmptyArrays.orEmpty(annotations, EmptyArrays.ANNOTATION);
        this.name = name;
        this.typeParams = EmptyArrays.orEmpty(typeParams, EmptyArrays.TYPE_PARAM);
        this.superclass = superclass;
        this.interfaces = EmptyArrays.orEmpty(interfaces, EmptyArrays.TYPE_NODE);
        this.permits = EmptyArrays.orEmpty(permits, EmptyArrays.TYPE_NODE);
        this.recordComponents = EmptyArrays.orEmpty(recordComponents, EmptyArrays.RECORD_COMPONENT);
        this.members = EmptyArrays.orEmpty(members, EmptyArrays.DECLARATION);
        this.symbol = symbol;
        for (Node __c : this.annotations) __c.setParent(this);
        if (name != null) name.setParent(this);
        for (Node __c : this.typeParams) __c.setParent(this);
        if (superclass != null) superclass.setParent(this);
        for (Node __c : this.interfaces) __c.setParent(this);
        for (Node __c : this.permits) __c.setParent(this);
        for (Node __c : this.recordComponents) __c.setParent(this);
        for (Node __c : this.members) __c.setParent(this);
    }

    public TypeDeclKind kind() {
        return kind;
    }

    public int modifiers() {
        return modifiers;
    }

    public Annotation[] annotations() {
        return annotations;
    }

    public Identifier name() {
        return name;
    }

    public TypeParamDecl[] typeParams() {
        return typeParams;
    }

    public TypeNode superclass() {
        return superclass;
    }

    public TypeNode[] interfaces() {
        return interfaces;
    }

    public TypeNode[] permits() {
        return permits;
    }

    public RecordComponentDecl[] recordComponents() {
        return recordComponents;
    }

    public Declaration[] members() {
        return members;
    }

    public TypeSymbol symbol() {
        return symbol;
    }
    @Override
    public Node nodeAt(int offset) {
        if (!covers(offset)) return null;
        Node deepest = this;
        for (Node __c : annotations) deepest = deeper(deepest, __c, offset);
        deepest = deeper(deepest, name, offset);
        for (Node __c : typeParams) deepest = deeper(deepest, __c, offset);
        deepest = deeper(deepest, superclass, offset);
        for (Node __c : interfaces) deepest = deeper(deepest, __c, offset);
        for (Node __c : permits) deepest = deeper(deepest, __c, offset);
        for (Node __c : recordComponents) deepest = deeper(deepest, __c, offset);
        for (Node __c : members) deepest = deeper(deepest, __c, offset);
        return deepest;
    }

    @Override
    public void accept(AstVisitor visitor) {
        visitor.visitTypeDecl(this);
    }
}
