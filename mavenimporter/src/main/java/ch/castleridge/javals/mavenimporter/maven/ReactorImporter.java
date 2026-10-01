/**
 * Copyright 2026 by Castle Ridge Software GmbH
 */
package ch.castleridge.javals.mavenimporter.maven;

import org.apache.maven.project.MavenProject;

/**
 * Shared Maven GAV key formatting.
 */
public final class ReactorImporter {

    private ReactorImporter() {}

    public static String gavKey(MavenProject project) {
        return gavKey(project.getGroupId(), project.getArtifactId(), project.getVersion());
    }

    public static String gavKey(String groupId, String artifactId, String version) {
        return groupId + ":" + artifactId + ":" + version;
    }
}
