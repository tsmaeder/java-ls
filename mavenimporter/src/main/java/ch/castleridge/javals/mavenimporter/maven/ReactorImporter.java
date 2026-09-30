/**
 * Copyright 2026 by Castle Ridge Software GmbH
 */
package ch.castleridge.javals.mavenimporter.maven;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.maven.project.MavenProject;
import org.apache.maven.project.ProjectBuildingResult;
import org.eclipse.aether.RepositorySystemSession;

import ch.castleridge.javals.mavenimporter.ReactorLoop;
import ch.castleridge.javals.mavenimporter.mbt.MbtDocument;

/**
 * Imports one Maven reactor rooted at a pom into a partial {@link MbtDocument}.
 */
public final class ReactorImporter implements ReactorLoop.ReactorImporter {

    private final MavenSession session;
    private final ProjectMapper mapper;

    public ReactorImporter(MavenSession session) {
        this.session = session;
        this.mapper = new ProjectMapper(new SourcesResolver(session.aether()));
    }

    @Override
    public ReactorLoop.ImportResult importReactor(Path pom) throws Exception {
        List<ProjectBuildingResult> results = session.buildReactor(pom);
        Map<String, MavenProject> reactorByGav = new LinkedHashMap<>();
        Set<Path> reactorPoms = new LinkedHashSet<>();
        RepositorySystemSession repoSession = null;

        for (ProjectBuildingResult result : results) {
            MavenProject project = result.getProject();
            if (project == null) {
                continue;
            }
            if (repoSession == null
                    && project.getProjectBuildingRequest() != null
                    && project.getProjectBuildingRequest().getRepositorySession() != null) {
                repoSession = project.getProjectBuildingRequest().getRepositorySession();
            }
            if (project.getFile() != null) {
                reactorPoms.add(project.getFile().toPath().toAbsolutePath().normalize());
            }
            if (!"pom".equals(project.getPackaging())) {
                reactorByGav.put(gavKey(project), project);
            }
        }

        MbtDocument document = mapper.map(
                results,
                reactorByGav,
                repoSession != null ? repoSession : session.lastRepositorySession());
        return new ReactorLoop.ImportResult(document, reactorPoms);
    }

    static String gavKey(MavenProject project) {
        return project.getGroupId() + ":" + project.getArtifactId() + ":" + project.getVersion();
    }

    static String gavKey(String groupId, String artifactId, String version) {
        return groupId + ":" + artifactId + ":" + version;
    }
}
