/**
 * Copyright 2026 by Castle Ridge Software GmbH
 *
 * Author: Thomas Mäder, Castle Ridge Software
 *
 */
package ch.castleridge.javals.mavenimporter.maven.generated;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.util.List;

import org.apache.maven.model.Build;
import org.apache.maven.model.Plugin;
import org.apache.maven.model.PluginExecution;
import org.apache.maven.project.MavenProject;
import org.codehaus.plexus.util.xml.Xpp3Dom;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GeneratedSourceRootsTest {

    @TempDir
    File basedir;

    @Test
    void modelloUsesDefaultWhenUnconfigured() {
        MavenProject project = projectWithPlugin(modelloPlugin(null));

        List<String> roots = GeneratedSourceRoots.collect(project, false);

        assertEquals(
                List.of(new File(basedir, "target/generated-sources/modello").getAbsolutePath()),
                roots);
    }

    @Test
    void modelloHonorsConfiguredOutputDirectory() {
        MavenProject project = projectWithPlugin(modelloPlugin("target/custom-modello"));

        List<String> roots = GeneratedSourceRoots.collect(project, false);

        assertEquals(List.of(new File(basedir, "target/custom-modello").getAbsolutePath()), roots);
    }

    @Test
    void compilerDefaultsForMainAndTest() {
        MavenProject project = projectWithPlugin(compilerPlugin(null, null));

        assertEquals(
                List.of(new File(basedir, "target/generated-sources/annotations").getAbsolutePath()),
                GeneratedSourceRoots.collect(project, false));
        assertEquals(
                List.of(new File(basedir, "target/generated-test-sources/test-annotations").getAbsolutePath()),
                GeneratedSourceRoots.collect(project, true));
    }

    @Test
    void compilerHonorsConfiguredGeneratedSourcesDirectory() {
        MavenProject project = projectWithPlugin(compilerPlugin("target/apt-main", "target/apt-test"));

        assertEquals(
                List.of(new File(basedir, "target/apt-main").getAbsolutePath()),
                GeneratedSourceRoots.collect(project, false));
        assertEquals(
                List.of(new File(basedir, "target/apt-test").getAbsolutePath()),
                GeneratedSourceRoots.collect(project, true));
    }

    @Test
    void buildHelperAddSourceListsConfiguredPaths() {
        Plugin plugin = new Plugin();
        plugin.setGroupId("org.codehaus.mojo");
        plugin.setArtifactId("build-helper-maven-plugin");

        PluginExecution execution = new PluginExecution();
        execution.setId("add-sources");
        execution.setGoals(List.of("add-source"));
        Xpp3Dom config = new Xpp3Dom("configuration");
        Xpp3Dom sources = new Xpp3Dom("sources");
        sources.addChild(textChild("source", "extra/main"));
        sources.addChild(textChild("source", "extra/other"));
        config.addChild(sources);
        execution.setConfiguration(config);
        plugin.addExecution(execution);

        MavenProject project = projectWithPlugin(plugin);

        List<String> roots = GeneratedSourceRoots.collect(project, false);
        assertEquals(
                List.of(
                        new File(basedir, "extra/main").getAbsolutePath(),
                        new File(basedir, "extra/other").getAbsolutePath()),
                roots);
        assertTrue(GeneratedSourceRoots.collect(project, true).isEmpty());
    }

    @Test
    void buildHelperWithoutSourcesEmitsNothing() {
        Plugin plugin = new Plugin();
        plugin.setGroupId("org.codehaus.mojo");
        plugin.setArtifactId("build-helper-maven-plugin");
        PluginExecution execution = new PluginExecution();
        execution.setId("add-sources");
        execution.setGoals(List.of("add-source"));
        plugin.addExecution(execution);

        MavenProject project = projectWithPlugin(plugin);
        assertTrue(GeneratedSourceRoots.collect(project, false).isEmpty());
    }

    @Test
    void missingPluginEmitsNothing() {
        MavenProject project = baseProject();
        assertTrue(GeneratedSourceRoots.collect(project, false).isEmpty());
        assertTrue(GeneratedSourceRoots.collect(project, true).isEmpty());
    }

    @Test
    void executionConfigOverridesPluginLevel() {
        Plugin plugin = modelloPlugin("target/plugin-level");
        PluginExecution execution = new PluginExecution();
        execution.setId("java");
        execution.setGoals(List.of("java"));
        Xpp3Dom config = new Xpp3Dom("configuration");
        config.addChild(textChild("outputDirectory", "target/exec-level"));
        execution.setConfiguration(config);
        plugin.addExecution(execution);

        MavenProject project = projectWithPlugin(plugin);
        List<String> roots = GeneratedSourceRoots.collect(project, false);
        assertTrue(roots.contains(new File(basedir, "target/plugin-level").getAbsolutePath()));
        assertTrue(roots.contains(new File(basedir, "target/exec-level").getAbsolutePath()));
        assertEquals(2, roots.size());
    }

    private MavenProject projectWithPlugin(Plugin plugin) {
        MavenProject project = baseProject();
        project.getBuild().addPlugin(plugin);
        return project;
    }

    private MavenProject baseProject() {
        MavenProject project = new MavenProject();
        project.setFile(new File(basedir, "pom.xml"));
        Build build = new Build();
        build.setDirectory(new File(basedir, "target").getAbsolutePath());
        project.setBuild(build);
        return project;
    }

    private static Plugin modelloPlugin(String outputDirectory) {
        Plugin plugin = new Plugin();
        plugin.setGroupId("org.codehaus.modello");
        plugin.setArtifactId("modello-maven-plugin");
        if (outputDirectory != null) {
            Xpp3Dom config = new Xpp3Dom("configuration");
            config.addChild(textChild("outputDirectory", outputDirectory));
            plugin.setConfiguration(config);
        }
        return plugin;
    }

    private static Plugin compilerPlugin(String generatedSources, String generatedTestSources) {
        Plugin plugin = new Plugin();
        plugin.setGroupId("org.apache.maven.plugins");
        plugin.setArtifactId("maven-compiler-plugin");
        if (generatedSources != null || generatedTestSources != null) {
            Xpp3Dom config = new Xpp3Dom("configuration");
            if (generatedSources != null) {
                config.addChild(textChild("generatedSourcesDirectory", generatedSources));
            }
            if (generatedTestSources != null) {
                config.addChild(textChild("generatedTestSourcesDirectory", generatedTestSources));
            }
            plugin.setConfiguration(config);
        }
        return plugin;
    }

    private static Xpp3Dom textChild(String name, String value) {
        Xpp3Dom child = new Xpp3Dom(name);
        child.setValue(value);
        return child;
    }
}
