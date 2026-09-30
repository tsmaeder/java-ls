/**
 * Copyright 2026 by Castle Ridge Software GmbH
 */
package ch.castleridge.javals.mavenimporter.maven;

import java.io.File;

import org.apache.maven.artifact.Artifact;
import org.apache.maven.project.MavenProject;
import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.RepositorySystemSession;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.resolution.ArtifactRequest;
import org.eclipse.aether.resolution.ArtifactResult;

/**
 * Optionally resolves {@code -sources.jar} artifacts for external dependencies.
 */
public final class SourcesResolver {

    private final RepositorySystem repositorySystem;

    public SourcesResolver(RepositorySystem repositorySystem) {
        this.repositorySystem = repositorySystem;
    }

    public File resolveSourcesJar(Artifact artifact, MavenProject project, RepositorySystemSession session) {
        try {
            org.eclipse.aether.artifact.Artifact sources = new DefaultArtifact(
                    artifact.getGroupId(),
                    artifact.getArtifactId(),
                    "sources",
                    "jar",
                    artifact.getVersion());
            ArtifactRequest request = new ArtifactRequest();
            request.setArtifact(sources);
            request.setRepositories(project.getRemoteProjectRepositories());
            ArtifactResult result = repositorySystem.resolveArtifact(session, request);
            if (result.isResolved() && result.getArtifact() != null && result.getArtifact().getFile() != null) {
                return result.getArtifact().getFile();
            }
        } catch (Exception ignored) {
            // Sources jars are optional.
        }
        return null;
    }
}
