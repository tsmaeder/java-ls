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
package ch.castleridge.javals.indexing.mbt;

import java.util.Collection;
import java.util.Map;

/**
 * Top-level shape of an {@code mbt.json} file aligned with the Metals MBT schema
 * ({@code mbt.schema.json}). {@code uncheckedSources} is intentionally omitted
 * as a Metals-specific extension.
 */
public class MbtInfo {
    public Map<String, MbtTargetInfo> namespaces;
    public Collection<MbtDependencyModuleInfo> dependencyModules;
}
