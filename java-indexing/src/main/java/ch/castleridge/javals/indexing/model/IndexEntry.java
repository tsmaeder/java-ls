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
 * Common superinterface for every declaration the indexer produces. Every
 * entry knows its stored declaration modifiers and any annotations attached
 * to the declaration.
 *
 * <p>Resource URI and JVM owner name live on {@link TypeEntry} (and related
 * locator types). {@link FieldEntry} / {@link MethodEntry} are nested under
 * their enclosing type and do not duplicate those locator fields.
 *
 * <p>For source-derived type entries, {@link #modifiers()} holds only explicit
 * source modifiers; JVM classfile access flags are synthesized later by
 * {@code IndexClassReader}. For bytecode-derived entries, {@link #modifiers()}
 * is the ASM access mask and is used as-is at read time.
 */
public sealed interface IndexEntry permits TypeEntry, FieldEntry, MethodEntry {

    int modifiers();

    AnnotationRef[] annotations();

    EntryKind kind();
}
