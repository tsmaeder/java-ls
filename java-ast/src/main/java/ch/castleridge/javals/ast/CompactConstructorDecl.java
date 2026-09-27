/**
 * Copyright 2026 by Castle Ridge Software GmbH
 *
 * Author: Thomas Mäder, Castle Ridge Software
 *
 */
package ch.castleridge.javals.ast;

import java.util.List;
import java.util.Set;

public final class CompactConstructorDecl extends Declaration {

    private final Set<Modifier> modifiers;
    private final List<Annotation> annotations;
    private final Identifier name;
    private final Block body;
    private final MethodSymbol symbol;

    public CompactConstructorDecl(Set<Modifier> modifiers,
                                  List<Annotation> annotations,
                                  Identifier name,
                                  Block body,
                                  MethodSymbol symbol,
                                  SourceRange range) {
        super(range);
        this.modifiers = MethodDecl.copyMods(modifiers);
        this.annotations = annotations == null ? List.of() : List.copyOf(annotations);
        this.name = name;
        this.body = body;
        this.symbol = symbol;
        for (Node __c : this.annotations) __c.setParent(this);
        if (name != null) name.setParent(this);
        if (body != null) body.setParent(this);
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

    public Block body() {
        return body;
    }

    public MethodSymbol symbol() {
        return symbol;
    }
    @Override
    public Node nodeAt(int offset) {
        if (!covers(offset)) return null;
        Node deepest = this;
        for (Node __c : annotations) deepest = deeper(deepest, __c, offset);
        deepest = deeper(deepest, name, offset);
        deepest = deeper(deepest, body, offset);
        return deepest;
    }

    @Override
    public void accept(AstVisitor visitor) {
        visitor.visitCompactConstructorDecl(this);
    }
}
