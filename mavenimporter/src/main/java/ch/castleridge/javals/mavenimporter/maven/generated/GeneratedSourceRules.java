/**
 * Copyright 2026 by Castle Ridge Software GmbH
 *
 * Author: Thomas Mäder, Castle Ridge Software
 *
 */
package ch.castleridge.javals.mavenimporter.maven.generated;

import java.util.List;

/** Seed registry of known generated-source plugins. */
public final class GeneratedSourceRules {

    private static final List<GeneratedSourceRule> RULES = List.of(
            new GeneratedSourceRule(
                    "org.codehaus.modello:modello-maven-plugin",
                    List.of(),
                    GeneratedSourceScope.MAIN,
                    "outputDirectory",
                    false,
                    "${project.build.directory}/generated-sources/modello",
                    false),
            new GeneratedSourceRule(
                    "org.apache.maven.plugins:maven-compiler-plugin",
                    List.of(),
                    GeneratedSourceScope.MAIN,
                    "generatedSourcesDirectory",
                    false,
                    "${project.build.directory}/generated-sources/annotations",
                    false),
            new GeneratedSourceRule(
                    "org.apache.maven.plugins:maven-compiler-plugin",
                    List.of(),
                    GeneratedSourceScope.TEST,
                    "generatedTestSourcesDirectory",
                    false,
                    "${project.build.directory}/generated-test-sources/test-annotations",
                    false),
            new GeneratedSourceRule(
                    "org.codehaus.mojo:build-helper-maven-plugin",
                    List.of("add-source"),
                    GeneratedSourceScope.MAIN,
                    "sources/source",
                    true,
                    null,
                    true),
            new GeneratedSourceRule(
                    "org.codehaus.mojo:build-helper-maven-plugin",
                    List.of("add-test-source"),
                    GeneratedSourceScope.TEST,
                    "sources/source",
                    true,
                    null,
                    true));

    private GeneratedSourceRules() {}

    public static List<GeneratedSourceRule> all() {
        return RULES;
    }
}
