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

/** A single dependency module entry inside an {@code mbt.json} file. */
public class MbtDependencyModuleInfo {
    public String id;
    public String jar;
    public String sources;
}
