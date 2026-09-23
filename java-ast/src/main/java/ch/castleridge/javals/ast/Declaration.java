/**
 * Copyright 2026 by Castle Ridge Software GmbH
 *
 * Author: Thomas Mäder, Castle Ridge Software
 *
 */
package ch.castleridge.javals.ast;

public abstract sealed class Declaration extends Node
        permits PackageDecl, TypeDecl, MethodDecl, ConstructorDecl, CompactConstructorDecl,
                FieldDecl, ParamDecl, RecordComponentDecl, EnumConstantDecl, InitializerDecl {

    protected Declaration(SourceRange range) {
        super(range);
    }

    /**
     * Javadoc immediately before this declaration in the original buffer,
     * or empty when there is none (or the node is synthetic).
     */
    public final String javadoc() {
        CompilationUnit unit = cu();
        if (unit == null || !hasRange()) return "";
        return unit.source().javadocBefore(range().start());
    }
}
