/**
 * Copyright 2026 by Castle Ridge Software GmbH
 *
 * Author: Thomas Mäder, Castle Ridge Software
 *
 */
package ch.castleridge.javals.mavenimporter.maven;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.maven.project.DependencyResolutionException;
import org.apache.maven.project.MavenProject;
import org.apache.maven.project.ProjectBuildingResult;
import org.eclipse.aether.RepositorySystemSession;

import ch.castleridge.javals.mavenimporter.ReactorLoop;
import ch.castleridge.javals.mavenimporter.maven.generated.GeneratedSourceRule;
import ch.castleridge.javals.mavenimporter.maven.generated.GeneratedSourceRules;
import ch.castleridge.javals.mavenimporter.mbt.MbtDocument;

/**
 * Indexes all workspace reactors, resolves classpaths via {@link WorkspacePomReader}, then maps to
 * mbt.
 */
public final class WorkspaceImporter {

    private final MavenSession session;
    private final List<GeneratedSourceRule> generatedSourceRules;

    public WorkspaceImporter(MavenSession session) {
        this(session, GeneratedSourceRules.all());
    }

    public WorkspaceImporter(MavenSession session, List<GeneratedSourceRule> generatedSourceRules) {
        this.session = session;
        this.generatedSourceRules =
                generatedSourceRules == null ? GeneratedSourceRules.all() : List.copyOf(generatedSourceRules);
    }

    public MbtDocument importWorkspace(Path workspaceRoot, Set<Path> todo) throws Exception {
        ProjectMapper mapper =
                new ProjectMapper(new SourcesResolver(session.aether()), generatedSourceRules, workspaceRoot);
        WorkspacePomIndex index = new WorkspacePomIndex();
        List<MavenProject> projects = new ArrayList<>();

        ReactorLoop.drain(todo, root -> {
            List<ProjectBuildingResult> results = session.buildReactorModels(root);
            Set<Path> reactorPoms = new LinkedHashSet<>();
            for (ProjectBuildingResult result : results) {
                MavenProject project = result.getProject();
                if (project == null) {
                    continue;
                }
                index.add(project);
                projects.add(project);
                if (project.getFile() != null) {
                    reactorPoms.add(project.getFile().toPath().toAbsolutePath().normalize());
                }
            }
            return reactorPoms;
        });

        RepositorySystemSession repoSession = session.openResolveSession(index);
        for (MavenProject project : projects) {
            if ("pom".equals(project.getPackaging())) {
                continue;
            }
            try {
                session.resolveDependencies(project, repoSession);
            } catch (DependencyResolutionException e) {
                // Artifacts may still be partially populated; continue so workspace dependsOn works.
                // Hard failures with empty classpaths surface as missing dependencyModules.
                if (project.getArtifacts() == null || project.getArtifacts().isEmpty()) {
                    throw e;
                }
            }
        }

        Map<String, MavenProject> workspaceByGav = index.classpathByGav();
        return mapper.map(projects, workspaceByGav, repoSession);
    }
}
