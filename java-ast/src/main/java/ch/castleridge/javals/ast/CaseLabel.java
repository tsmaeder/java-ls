/**
 * Copyright 2026 by Castle Ridge Software GmbH
 *
 * Author: Thomas Mäder, Castle Ridge Software
 *
 */
package ch.castleridge.javals.ast;

public abstract sealed class CaseLabel extends Node
        permits DefaultLabel, ConstantLabel, PatternLabel {
    protected CaseLabel(SourceRange range) { super(range); }
}
