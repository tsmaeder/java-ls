/**
 * Copyright 2026 by Castle Ridge Software GmbH
 *
 * Author: Thomas Mäder, Castle Ridge Software
 *
 */
package ch.castleridge.javals.ast;

import java.util.List;

public abstract sealed class ModuleDirective extends Node
        permits RequiresDirective, ExportsDirective, OpensDirective, UsesDirective, ProvidesDirective {

    protected ModuleDirective(SourceRange range) {
        super(range);
    }
}
