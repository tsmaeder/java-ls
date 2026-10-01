/**
 * Copyright 2026 by Castle Ridge Software GmbH
 */
package ch.castleridge.javals.mavenimporter.maven.generated;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

class GeneratedSourceRuleConfigTest {

    @Test
    void resolveWithoutOverridesReturnsSeed() {
        assertEquals(GeneratedSourceRules.all(), GeneratedSourceRuleConfig.resolve(List.of()));
        assertEquals(GeneratedSourceRules.all(), GeneratedSourceRuleConfig.resolve(null));
    }

    @Test
    void disableByPluginKeyRemovesAllMatchingBuiltIns() {
        List<GeneratedSourceRule> rules = GeneratedSourceRuleConfig.resolve(List.of(
                new GeneratedSourceRuleConfig.Entry(
                        "org.apache.maven.plugins:maven-compiler-plugin",
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        false)));

        assertTrue(rules.stream()
                .noneMatch(r -> r.pluginKey().equals("org.apache.maven.plugins:maven-compiler-plugin")));
        assertTrue(rules.stream()
                .anyMatch(r -> r.pluginKey().equals("org.codehaus.modello:modello-maven-plugin")));
    }

    @Test
    void disableNarrowedByScopeKeepsOtherScopes() {
        List<GeneratedSourceRule> rules = GeneratedSourceRuleConfig.resolve(List.of(
                new GeneratedSourceRuleConfig.Entry(
                        "org.apache.maven.plugins:maven-compiler-plugin",
                        null,
                        GeneratedSourceScope.MAIN,
                        null,
                        null,
                        null,
                        null,
                        false)));

        assertTrue(rules.stream()
                .noneMatch(r -> r.pluginKey().equals("org.apache.maven.plugins:maven-compiler-plugin")
                        && r.scope() == GeneratedSourceScope.MAIN));
        assertTrue(rules.stream()
                .anyMatch(r -> r.pluginKey().equals("org.apache.maven.plugins:maven-compiler-plugin")
                        && r.scope() == GeneratedSourceScope.TEST));
    }

    @Test
    void additionAppendedAfterBuiltIns() {
        GeneratedSourceRuleConfig.Entry addition = new GeneratedSourceRuleConfig.Entry(
                "org.antlr:antlr4-maven-plugin",
                List.of(),
                GeneratedSourceScope.MAIN,
                "outputDirectory",
                false,
                "${project.build.directory}/generated-sources/antlr4",
                false,
                true);

        List<GeneratedSourceRule> rules = GeneratedSourceRuleConfig.resolve(List.of(addition));
        assertEquals(GeneratedSourceRules.all().size() + 1, rules.size());
        GeneratedSourceRule last = rules.get(rules.size() - 1);
        assertEquals("org.antlr:antlr4-maven-plugin", last.pluginKey());
        assertEquals("outputDirectory", last.configPath());
        assertEquals("${project.build.directory}/generated-sources/antlr4", last.defaultPath());
    }

    @Test
    void incompleteAdditionSkipped() {
        List<GeneratedSourceRule> rules = GeneratedSourceRuleConfig.resolve(List.of(
                new GeneratedSourceRuleConfig.Entry(
                        "org.example:incomplete",
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        true)));
        assertEquals(GeneratedSourceRules.all(), rules);
        assertFalse(rules.stream().anyMatch(r -> r.pluginKey().equals("org.example:incomplete")));
    }

    @Test
    void parseJsonReadsDisableAndAddition() {
        String json = """
                [
                  { "pluginKey": "org.codehaus.modello:modello-maven-plugin", "enabled": false },
                  {
                    "pluginKey": "org.antlr:antlr4-maven-plugin",
                    "scope": "main",
                    "configPath": "outputDirectory",
                    "defaultPath": "${project.build.directory}/generated-sources/antlr4"
                  }
                ]
                """;
        List<GeneratedSourceRuleConfig.Entry> entries = GeneratedSourceRuleConfig.parseJson(json);
        assertEquals(2, entries.size());
        assertFalse(entries.get(0).enabled());
        assertTrue(entries.get(1).enabled());
        assertEquals(GeneratedSourceScope.MAIN, entries.get(1).scope());

        List<GeneratedSourceRule> rules = GeneratedSourceRuleConfig.resolve(entries);
        assertTrue(rules.stream()
                .noneMatch(r -> r.pluginKey().equals("org.codehaus.modello:modello-maven-plugin")));
        assertTrue(rules.stream().anyMatch(r -> r.pluginKey().equals("org.antlr:antlr4-maven-plugin")));
    }
}
