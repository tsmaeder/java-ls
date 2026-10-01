/**
 * Copyright 2026 by Castle Ridge Software GmbH
 */
package ch.castleridge.javals.mavenimporter.maven.generated;

import java.util.List;
import java.util.Objects;

/**
 * Describes how to find a generated source directory for one Maven plugin.
 *
 * @param pluginKey {@code groupId:artifactId} matching {@code MavenProject.getPlugin}
 * @param goals when non-empty, only executions whose goals intersect apply
 * @param scope main or test namespace
 * @param configPath slash-separated path under {@code <configuration>}
 * @param list if true, each matching child element is a path
 * @param defaultPath property-style default when config is absent; null if none
 * @param required if true and no value/default, skip (e.g. build-helper)
 */
public record GeneratedSourceRule(
        String pluginKey,
        List<String> goals,
        GeneratedSourceScope scope,
        String configPath,
        boolean list,
        String defaultPath,
        boolean required) {

    public GeneratedSourceRule {
        Objects.requireNonNull(pluginKey, "pluginKey");
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(configPath, "configPath");
        goals = goals == null ? List.of() : List.copyOf(goals);
    }

    public boolean hasGoals() {
        return !goals.isEmpty();
    }
}
