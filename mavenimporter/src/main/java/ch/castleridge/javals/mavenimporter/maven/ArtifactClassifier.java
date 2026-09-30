/**
 * Copyright 2026 by Castle Ridge Software GmbH
 */
package ch.castleridge.javals.mavenimporter.maven;

import java.io.File;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import org.apache.maven.artifact.Artifact;
import org.apache.maven.project.MavenProject;

/**
 * Classifies resolved artifacts as reactor modules ({@code dependsOn}) vs external jars.
 */
public final class ArtifactClassifier {

    public record Classification(
            Set<String> dependsOnMainIds,
            Set<String> dependsOnTestIds,
            Map<String, Artifact> externalArtifacts) {}

    private ArtifactClassifier() {}

    public static Classification classify(MavenProject project, Map<String, MavenProject> reactorByGav, boolean testClasspath) {
        Set<String> dependsOnMain = new LinkedHashSet<>();
        Set<String> dependsOnTest = new LinkedHashSet<>();
        Map<String, Artifact> external = new LinkedHashMap<>();

        for (Artifact artifact : project.getArtifacts()) {
            if (!testClasspath && !isMainScope(artifact.getScope())) {
                continue;
            }
            if (artifact.getArtifactHandler() != null && !artifact.getArtifactHandler().isAddedToClasspath()) {
                continue;
            }

            String gav = ReactorImporter.gavKey(artifact.getGroupId(), artifact.getArtifactId(), artifact.getVersion());
            MavenProject reactorProject = reactorByGav.get(gav);
            if (reactorProject != null) {
                String mainId = gav;
                if ("tests".equals(artifact.getClassifier()) || "test-jar".equals(artifact.getType())) {
                    dependsOnTest.add(mainId + ":test");
                } else {
                    dependsOnMain.add(mainId);
                }
                continue;
            }

            File file = artifact.getFile();
            if (file == null || !file.isFile()) {
                continue;
            }
            String id = coordinateId(artifact);
            external.putIfAbsent(id, artifact);
        }

        return new Classification(dependsOnMain, dependsOnTest, external);
    }

    static boolean isMainScope(String scope) {
        if (scope == null || scope.isEmpty() || Artifact.SCOPE_COMPILE.equals(scope)) {
            return true;
        }
        return Artifact.SCOPE_PROVIDED.equals(scope) || Artifact.SCOPE_SYSTEM.equals(scope);
    }

    static String coordinateId(Artifact artifact) {
        StringBuilder id = new StringBuilder();
        id.append(artifact.getGroupId()).append(':').append(artifact.getArtifactId()).append(':').append(artifact.getVersion());
        if (artifact.getClassifier() != null && !artifact.getClassifier().isEmpty()) {
            id.append(':').append(artifact.getClassifier());
        }
        return id.toString();
    }
}
