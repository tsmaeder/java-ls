/**
 * Copyright 2026 by Castle Ridge Software GmbH
 *
 * Author: Thomas Mäder, Castle Ridge Software
 *
 */
package ch.castleridge.javals.ast;

public abstract sealed class Pattern extends Node
        permits TypePattern, RecordPattern, UnnamedPattern {

    protected Pattern(SourceRange range) {
        super(range);
    }
}
