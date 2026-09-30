/**
 * Copyright 2026 by Castle Ridge Software GmbH
 */
package ch.castleridge.javals.mavenimporter.mbt;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Aggregates reactor import results into one Metals-dialect document.
 */
public final class MbtAggregator {

    private final Map<String, MbtDependencyModule> dependencyModulesById = new LinkedHashMap<>();
    private final Map<String, MbtNamespace> namespaces = new LinkedHashMap<>();

    public void add(MbtDocument partial) {
        for (MbtDependencyModule module : partial.dependencyModules) {
            dependencyModulesById.putIfAbsent(module.id, module);
        }
        namespaces.putAll(partial.namespaces);
    }

    public MbtDocument toDocument() {
        MbtDocument document = new MbtDocument();
        document.dependencyModules.addAll(dependencyModulesById.values());
        document.namespaces.putAll(namespaces);
        return document;
    }
}
