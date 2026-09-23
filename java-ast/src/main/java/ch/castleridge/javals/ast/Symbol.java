/**
 * Copyright 2026 by Castle Ridge Software GmbH
 *
 * Author: Thomas Mäder, Castle Ridge Software
 *
 */
package ch.castleridge.javals.ast;

import java.util.ArrayList;
import java.util.List;

/**
 * Resolved symbol attached to identifiers and declarations. Synthetics
 * (default constructors, record accessors, enum {@code values()}) live
 * here, not as fake source nodes.
 */
public sealed interface Symbol
        permits TypeSymbol, MethodSymbol, FieldSymbol, RecordComponentSymbol,
                EnumConstantSymbol, LocalSymbol, PackageSymbol, TypeVarSymbol, ModuleSymbol {

    String name();

    JType type();

    SymbolKey key();

    default boolean synthetic() {
        return false;
    }

    default boolean fileLocal() {
        return key() != null && key().fileLocal();
    }
}
