/**
 * Copyright 2026 by Castle Ridge Software GmbH
 *
 * Author: Thomas Mäder, Castle Ridge Software
 *
 */
package ch.castleridge.javals.mavenimporter.maven;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.util.LinkedHashMap;
import java.util.Map;

import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.DefaultArtifact;
import org.apache.maven.artifact.handler.DefaultArtifactHandler;
import org.apache.maven.project.MavenProject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ArtifactClassifierTest {

    @TempDir
    File temp;

    @Test
    void reactorModulesGoToDependsOnNotExternal() throws Exception {
        MavenProject reactorMod = new MavenProject();
        reactorMod.setGroupId("ex");
        reactorMod.setArtifactId("mod-a");
        reactorMod.setVersion("1.0");

        Map<String, MavenProject> reactor = new LinkedHashMap<>();
        reactor.put("ex:mod-a:1.0", reactorMod);

        MavenProject project = new MavenProject();
        Artifact reactorArtifact = artifact("ex", "mod-a", "1.0", "compile", null);
        Artifact external = artifact("com.google.code.gson", "gson", "2.11.0", "compile", jarFile("gson.jar"));
        project.setArtifacts(java.util.Set.of(reactorArtifact, external));

        ArtifactClassifier.Classification classification =
                ArtifactClassifier.classify(project, reactor, false);

        assertTrue(classification.dependsOnMainIds().contains("ex:mod-a:1.0"));
        assertFalse(classification.externalArtifacts().containsKey("ex:mod-a:1.0"));
        assertTrue(classification.externalArtifacts().containsKey("com.google.code.gson:gson:2.11.0"));
    }

    @Test
    void mainClasspathExcludesTestScope() throws Exception {
        MavenProject project = new MavenProject();
        Artifact compile = artifact("g", "compile-dep", "1", "compile", jarFile("c.jar"));
        Artifact test = artifact("g", "test-dep", "1", "test", jarFile("t.jar"));
        project.setArtifacts(java.util.Set.of(compile, test));

        ArtifactClassifier.Classification main =
                ArtifactClassifier.classify(project, Map.of(), false);
        ArtifactClassifier.Classification testCp =
                ArtifactClassifier.classify(project, Map.of(), true);

        assertEquals(1, main.externalArtifacts().size());
        assertTrue(main.externalArtifacts().containsKey("g:compile-dep:1"));
        assertEquals(2, testCp.externalArtifacts().size());
    }

    private Artifact artifact(String g, String a, String v, String scope, File file) {
        DefaultArtifactHandler handler = new DefaultArtifactHandler("jar");
        handler.setAddedToClasspath(true);
        DefaultArtifact artifact = new DefaultArtifact(g, a, v, scope, "jar", null, handler);
        artifact.setFile(file);
        artifact.setResolved(file != null);
        return artifact;
    }

    private File jarFile(String name) throws Exception {
        File file = new File(temp, name);
        if (!file.exists()) {
            assertTrue(file.createNewFile());
        }
        return file;
    }
}
