/**
 * Copyright 2026 by Castle Ridge Software GmbH
 */
package ch.castleridge.javals.mavenimporter.maven;

import java.io.File;
import java.util.List;
import java.util.Objects;

import org.apache.maven.project.MavenProject;
import org.apache.maven.repository.internal.MavenWorkspaceReader;
import org.eclipse.aether.artifact.Artifact;
import org.eclipse.aether.repository.WorkspaceRepository;

/**
 * Resolves workspace module artifacts from indexed {@link MavenProject}s instead of remote/local
 * repos — the same role Maven's {@code ReactorReader} plays during a normal build.
 */
public final class WorkspacePomReader implements MavenWorkspaceReader {

    private final WorkspacePomIndex index;
    private final WorkspaceRepository repository;

    public WorkspacePomReader(WorkspacePomIndex index) {
        this.index = Objects.requireNonNull(index, "index");
        this.repository = new WorkspaceRepository("workspace");
    }

    @Override
    public WorkspaceRepository getRepository() {
        return repository;
    }

    @Override
    public File findArtifact(Artifact artifact) {
        MavenProject project = index.get(artifact.getGroupId(), artifact.getArtifactId(), artifact.getVersion());
        if (project == null) {
            return null;
        }
        return find(project, artifact);
    }

    @Override
    public List<String> findVersions(Artifact artifact) {
        return index.versions(artifact.getGroupId(), artifact.getArtifactId());
    }

    @Override
    public org.apache.maven.model.Model findModel(Artifact artifact) {
        if (!"pom".equals(artifact.getExtension())) {
            return null;
        }
        MavenProject project = index.get(artifact.getGroupId(), artifact.getArtifactId(), artifact.getVersion());
        return project != null ? project.getModel() : null;
    }

    private static File find(MavenProject project, Artifact artifact) {
        if ("pom".equals(artifact.getExtension())) {
            return project.getFile();
        }

        if (artifact.getClassifier() != null && !artifact.getClassifier().isEmpty()) {
            if ("tests".equals(artifact.getClassifier()) || "test-jar".equals(artifact.getExtension())) {
                String testOutput = project.getBuild() != null ? project.getBuild().getTestOutputDirectory() : null;
                return testOutput != null ? new File(testOutput) : null;
            }
            return null;
        }

        if (project.getArtifact() != null
                && project.getArtifact().getFile() != null
                && project.getArtifact().getFile().isFile()) {
            return project.getArtifact().getFile();
        }

        String output = project.getBuild() != null ? project.getBuild().getOutputDirectory() : null;
        if (output != null) {
            return new File(output);
        }
        return project.getFile();
    }
}
