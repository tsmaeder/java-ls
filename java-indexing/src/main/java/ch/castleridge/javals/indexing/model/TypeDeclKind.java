/**
 * Copyright 2026 by Anysphere Inc.
 * 
 * Licensed under the MIT License.
 * 
 * SPDX-License-Identifier: MIT
 *
 * Author: Thomas Mäder, Castle Ridge Software
 *
 */
package ch.castleridge.javals.indexing.model;

/**
 * Declaration kind of an indexed type. Set from source AST for
 * source-derived entries; {@link #UNKNOWN} for bytecode-derived entries
 * where kind is not stored separately.
 */
public enum TypeDeclKind {
    UNKNOWN,
    CLASS,
    INTERFACE,
    ENUM,
    RECORD,
    ANNOTATION
}
