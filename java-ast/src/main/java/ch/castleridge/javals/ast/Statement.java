/**
 * Copyright 2026 by Castle Ridge Software GmbH
 *
 * Author: Thomas Mäder, Castle Ridge Software
 *
 */
package ch.castleridge.javals.ast;

public abstract sealed class Statement extends Node
        permits Block, EmptyStmt, ExprStmt, IfStmt, WhileStmt, DoWhileStmt, ForStmt, ForEachStmt,
                SwitchStmt, ReturnStmt, ThrowStmt, BreakStmt, ContinueStmt, YieldStmt,
                SynchronizedStmt, TryStmt, AssertStmt, LabeledStmt, LocalDeclStmt, LocalTypeStmt,
                ConstructorCallStmt, ErroneousStmt {

    protected Statement(SourceRange range) {
        super(range);
    }
}
