/**
 * Copyright 2026 by Castle Ridge Software GmbH
 */
package ch.castleridge.javals.mavenimporter.maven;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.maven.artifact.Artifact;
import org.apache.maven.model.Plugin;
import org.apache.maven.project.MavenProject;
import org.codehaus.plexus.util.xml.Xpp3Dom;
import org.eclipse.aether.RepositorySystemSession;

import ch.castleridge.javals.mavenimporter.maven.generated.GeneratedSourceRule;
import ch.castleridge.javals.mavenimporter.maven.generated.GeneratedSourceRoots;
import ch.castleridge.javals.mavenimporter.maven.generated.GeneratedSourceRules;
import ch.castleridge.javals.mavenimporter.mbt.MbtDependencyModule;
import ch.castleridge.javals.mavenimporter.mbt.MbtDocument;
import ch.castleridge.javals.mavenimporter.mbt.MbtNamespace;

/**
 * Maps Maven projects in a reactor to Metals-dialect mbt namespaces.
 */
public final class ProjectMapper {

    private final SourcesResolver sourcesResolver;
    private final List<GeneratedSourceRule> generatedSourceRules;

    public ProjectMapper(SourcesResolver sourcesResolver) {
        this(sourcesResolver, GeneratedSourceRules.all());
    }

    public ProjectMapper(SourcesResolver sourcesResolver, List<GeneratedSourceRule> generatedSourceRules) {
        this.sourcesResolver = sourcesResolver;
        this.generatedSourceRules =
                generatedSourceRules == null ? GeneratedSourceRules.all() : List.copyOf(generatedSourceRules);
    }

    public MbtDocument map(
            List<MavenProject> projects,
            Map<String, MavenProject> workspaceByGav,
            RepositorySystemSession repoSession) {
        MbtDocument document = new MbtDocument();

        for (MavenProject project : projects) {
            if (project == null || "pom".equals(project.getPackaging())) {
                continue;
            }

            String mainId = ReactorImporter.gavKey(project);
            String testId = mainId + ":test";

            ArtifactClassifier.Classification mainClass =
                    ArtifactClassifier.classify(project, workspaceByGav, false);
            ArtifactClassifier.Classification testClass =
                    ArtifactClassifier.classify(project, workspaceByGav, true);

            MbtNamespace mainNs = newNamespace(project, false);
            mainNs.dependsOn.addAll(orderedUnique(mainClass.dependsOnMainIds(), mainClass.dependsOnTestIds()));
            addExternalModules(document, mainNs, mainClass.externalArtifacts(), project, repoSession);

            MbtNamespace testNs = newNamespace(project, true);
            Set<String> testDepends = new LinkedHashSet<>();
            testDepends.add(mainId);
            testDepends.addAll(testClass.dependsOnMainIds());
            testDepends.addAll(testClass.dependsOnTestIds());
            testNs.dependsOn.addAll(testDepends);
            addExternalModules(document, testNs, testClass.externalArtifacts(), project, repoSession);

            document.namespaces.put(mainId, mainNs);
            document.namespaces.put(testId, testNs);
        }
        return document;
    }

    private static List<String> orderedUnique(Set<String> first, Set<String> second) {
        LinkedHashSet<String> ordered = new LinkedHashSet<>(first);
        ordered.addAll(second);
        return new ArrayList<>(ordered);
    }

    private void addExternalModules(
            MbtDocument document,
            MbtNamespace namespace,
            Map<String, Artifact> external,
            MavenProject project,
            RepositorySystemSession repoSession) {
        for (Map.Entry<String, Artifact> entry : external.entrySet()) {
            String id = entry.getKey();
            Artifact artifact = entry.getValue();
            namespace.dependencyModules.add(id);

            boolean alreadyPresent = document.dependencyModules.stream().anyMatch(m -> id.equals(m.id));
            if (alreadyPresent) {
                continue;
            }

            String jarUri = toFileUri(artifact.getFile());
            String sourcesUri = null;
            if (repoSession != null) {
                File sourcesJar = sourcesResolver.resolveSourcesJar(artifact, project, repoSession);
                if (sourcesJar != null) {
                    sourcesUri = toFileUri(sourcesJar);
                }
            }
            document.dependencyModules.add(new MbtDependencyModule(id, jarUri, sourcesUri));
        }
    }

    private MbtNamespace newNamespace(MavenProject project, boolean test) {
        MbtNamespace ns = new MbtNamespace();
        LinkedHashSet<String> sources = new LinkedHashSet<>();
        List<String> roots = test ? project.getTestCompileSourceRoots() : project.getCompileSourceRoots();
        for (String root : roots) {
            File dir = new File(root);
            if (dir.isDirectory()) {
                sources.add(dir.getAbsolutePath());
            }
        }
        sources.addAll(GeneratedSourceRoots.collect(project, test, generatedSourceRules));
        ns.sources.addAll(sources);
        ns.javacOptions.addAll(javacOptions(project));
        ns.javaHome = System.getProperty("java.home");
        ns.projectPath = project.getBasedir().getAbsolutePath();
        String classes = test
                ? project.getBuild().getTestOutputDirectory()
                : project.getBuild().getOutputDirectory();
        if (classes != null) {
            ns.classDirectories.add(new File(classes).getAbsolutePath());
        }
        return ns;
    }

    static List<String> javacOptions(MavenProject project) {
        List<String> options = new ArrayList<>();
        Plugin compiler = project.getPlugin("org.apache.maven.plugins:maven-compiler-plugin");
        Xpp3Dom config = null;
        if (compiler != null && compiler.getConfiguration() instanceof Xpp3Dom dom) {
            config = dom;
        }

        String release = firstNonBlank(childValue(config, "release"), project.getProperties().getProperty("maven.compiler.release"));
        if (release != null) {
            options.add("-release");
            options.add(release);
        } else {
            String source = firstNonBlank(childValue(config, "source"), project.getProperties().getProperty("maven.compiler.source"));
            String target = firstNonBlank(childValue(config, "target"), project.getProperties().getProperty("maven.compiler.target"));
            if (source != null) {
                options.add("-source");
                options.add(source);
            }
            if (target != null) {
                options.add("-target");
                options.add(target);
            }
        }

        if (config != null) {
            Xpp3Dom compilerArgs = config.getChild("compilerArgs");
            if (compilerArgs != null) {
                for (Xpp3Dom arg : compilerArgs.getChildren()) {
                    if (arg.getValue() != null && !arg.getValue().isBlank()) {
                        options.add(arg.getValue().trim());
                    }
                }
            }
            Xpp3Dom singleArg = config.getChild("compilerArgument");
            if (singleArg != null && singleArg.getValue() != null && !singleArg.getValue().isBlank()) {
                options.add(singleArg.getValue().trim());
            }
        }
        return options;
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return null;
    }

    private static String childValue(Xpp3Dom config, String name) {
        if (config == null) {
            return null;
        }
        Xpp3Dom child = config.getChild(name);
        return child != null ? child.getValue() : null;
    }

    static String toFileUri(File file) {
        return file.toURI().toString();
    }
}
