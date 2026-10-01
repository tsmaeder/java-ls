/**
 * Copyright 2026 by Castle Ridge Software GmbH
 *
 * Author: Thomas Mäder, Castle Ridge Software
 *
 */
package ch.castleridge.javals.mavenimporter.mbt;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Root Metals-dialect {@code mbt.json} document.
 */
public final class MbtDocument {
    public List<MbtDependencyModule> dependencyModules = new ArrayList<>();
    public Map<String, MbtNamespace> namespaces = new LinkedHashMap<>();
}
