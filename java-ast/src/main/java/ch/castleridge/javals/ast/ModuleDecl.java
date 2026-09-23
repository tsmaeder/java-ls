/**
 * Copyright 2026 by Castle Ridge Software GmbH
 *
 * Author: Thomas Mäder, Castle Ridge Software
 *
 */
package ch.castleridge.javals.ast;

import java.util.List;

public final class ModuleDecl extends Node {

    private final boolean open;
    private final List<Annotation> annotations;
    private final List<Identifier> names;
    private final List<ModuleDirective> directives;
    private final ModuleSymbol symbol;

    public ModuleDecl(boolean open,
                      List<Annotation> annotations,
                      List<Identifier> names,
                      List<ModuleDirective> directives,
                      ModuleSymbol symbol,
                      SourceRange range) {
        super(range);
        this.open = open;
        this.annotations = annotations == null ? List.of() : List.copyOf(annotations);
        this.names = names == null ? List.of() : List.copyOf(names);
        this.directives = directives == null ? List.of() : List.copyOf(directives);
        this.symbol = symbol;
    }

    public boolean open() {
        return open;
    }

    public List<Annotation> annotations() {
        return annotations;
    }

    public List<Identifier> names() {
        return names;
    }

    public List<ModuleDirective> directives() {
        return directives;
    }

    public ModuleSymbol symbol() {
        return symbol;
    }

    @Override
    public List<? extends Node> children() {
        return kids(annotations, names, directives);
    }

    @Override
    public void accept(AstVisitor visitor) {
        visitor.visitModuleDecl(this);
    }
}
