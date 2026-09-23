/**
 * Copyright 2026 by Castle Ridge Software GmbH
 *
 * Author: Thomas Mäder, Castle Ridge Software
 *
 */
package ch.castleridge.javals.ast;

import java.util.List;

public final class ImportDecl extends Node {

    private final boolean staticImport;
    private final boolean onDemand;
    private final List<Identifier> names;

    public ImportDecl(boolean staticImport, boolean onDemand, List<Identifier> names, SourceRange range) {
        super(range);
        this.staticImport = staticImport;
        this.onDemand = onDemand;
        this.names = names == null ? List.of() : List.copyOf(names);
    }

    public boolean staticImport() {
        return staticImport;
    }

    public boolean onDemand() {
        return onDemand;
    }

    public List<Identifier> names() {
        return names;
    }

    @Override
    public List<? extends Node> children() {
        return kids(names);
    }

    @Override
    public void accept(AstVisitor visitor) {
        visitor.visitImportDecl(this);
    }
}
