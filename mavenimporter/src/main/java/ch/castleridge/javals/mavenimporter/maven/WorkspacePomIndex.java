/**
 * Copyright 2026 by Castle Ridge Software GmbH
 */
package ch.castleridge.javals.mavenimporter.maven;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.maven.project.MavenProject;

/**
 * Effective GAV → {@link MavenProject} for every module discovered in the workspace.
 */
public final class WorkspacePomIndex {

    private final Map<String, MavenProject> byGav = new LinkedHashMap<>();
    private final Map<String, List<String>> versionsByGa = new LinkedHashMap<>();

    public void add(MavenProject project) {
        if (project == null) {
            return;
        }
        String gav = ReactorImporter.gavKey(project);
        byGav.put(gav, project);

        String ga = project.getGroupId() + ":" + project.getArtifactId();
        versionsByGa.computeIfAbsent(ga, key -> new ArrayList<>());
        List<String> versions = versionsByGa.get(ga);
        if (!versions.contains(project.getVersion())) {
            versions.add(project.getVersion());
        }
    }

    public MavenProject get(String groupId, String artifactId, String version) {
        return byGav.get(ReactorImporter.gavKey(groupId, artifactId, version));
    }

    public MavenProject getByGav(String gav) {
        return byGav.get(gav);
    }

    public List<String> versions(String groupId, String artifactId) {
        List<String> versions = versionsByGa.get(groupId + ":" + artifactId);
        return versions == null ? List.of() : Collections.unmodifiableList(versions);
    }

    /**
     * Projects that produce classpath namespaces (excludes {@code packaging=pom}).
     */
    public Map<String, MavenProject> classpathByGav() {
        Map<String, MavenProject> classpath = new LinkedHashMap<>();
        for (Map.Entry<String, MavenProject> entry : byGav.entrySet()) {
            if (!"pom".equals(entry.getValue().getPackaging())) {
                classpath.put(entry.getKey(), entry.getValue());
            }
        }
        return Collections.unmodifiableMap(classpath);
    }

    public Set<MavenProject> allProjects() {
        return Collections.unmodifiableSet(new LinkedHashSet<>(byGav.values()));
    }

    public boolean isEmpty() {
        return byGav.isEmpty();
    }
}
