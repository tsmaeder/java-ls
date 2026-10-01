/**
 * Copyright 2026 by Castle Ridge Software GmbH
 *
 * Author: Thomas Mäder, Castle Ridge Software
 *
 */
package ch.castleridge.javals.mavenimporter.maven;

import java.io.File;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

import org.apache.maven.DefaultMaven;
import org.apache.maven.Maven;
import org.apache.maven.RepositoryUtils;
import org.apache.maven.artifact.Artifact;
import org.apache.maven.execution.DefaultMavenExecutionRequest;
import org.apache.maven.execution.MavenExecutionRequest;
import org.apache.maven.execution.MavenExecutionRequestPopulator;
import org.apache.maven.extension.internal.CoreExports;
import org.apache.maven.extension.internal.CoreExtensionEntry;
import org.apache.maven.project.DefaultDependencyResolutionRequest;
import org.apache.maven.project.DefaultProjectBuildingRequest;
import org.apache.maven.project.DependencyResolutionException;
import org.apache.maven.project.DependencyResolutionResult;
import org.apache.maven.project.MavenProject;
import org.apache.maven.project.ProjectBuilder;
import org.apache.maven.project.ProjectBuildingRequest;
import org.apache.maven.project.ProjectBuildingResult;
import org.apache.maven.project.ProjectDependenciesResolver;
import org.apache.maven.settings.Settings;
import org.apache.maven.settings.building.DefaultSettingsBuildingRequest;
import org.apache.maven.settings.building.SettingsBuilder;
import org.apache.maven.settings.building.SettingsBuildingRequest;
import org.codehaus.plexus.ContainerConfiguration;
import org.codehaus.plexus.DefaultContainerConfiguration;
import org.codehaus.plexus.DefaultPlexusContainer;
import org.codehaus.plexus.PlexusConstants;
import org.codehaus.plexus.classworlds.ClassWorld;
import org.codehaus.plexus.classworlds.realm.ClassRealm;
import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.RepositorySystemSession;
import org.eclipse.aether.repository.LocalRepositoryManager;
import org.slf4j.ILoggerFactory;
import org.slf4j.LoggerFactory;

import com.google.inject.AbstractModule;

/**
 * Embedded Maven Plexus container used to build reactors with {@link ProjectBuilder}.
 */
public final class MavenSession implements AutoCloseable {

    private final DefaultPlexusContainer container;
    private final ClassLoader previousContextClassLoader;
    private final ProjectBuilder projectBuilder;
    private final ProjectDependenciesResolver projectDependenciesResolver;
    private final MavenExecutionRequestPopulator requestPopulator;
    private final SettingsBuilder settingsBuilder;
    private final RepositorySystem aetherRepositorySystem;
    private final DefaultMaven defaultMaven;

    private RepositorySystemSession lastRepositorySession;

    public MavenSession() throws Exception {
        previousContextClassLoader = Thread.currentThread().getContextClassLoader();

        ClassWorld classWorld = new ClassWorld("plexus.core", previousContextClassLoader);
        ClassRealm containerRealm = classWorld.getClassRealm("plexus.core");

        ContainerConfiguration cc = new DefaultContainerConfiguration()
                .setClassWorld(classWorld)
                .setRealm(containerRealm)
                .setClassPathScanning(PlexusConstants.SCANNING_INDEX)
                .setAutoWiring(true)
                .setJSR250Lifecycle(true)
                .setName("mavenimporter");

        CoreExtensionEntry coreEntry = CoreExtensionEntry.discoverFrom(containerRealm);
        Set<String> exportedArtifacts = new HashSet<>(coreEntry.getExportedArtifacts());
        Set<String> exportedPackages = new HashSet<>(coreEntry.getExportedPackages());
        CoreExports exports = new CoreExports(containerRealm, exportedArtifacts, exportedPackages);

        ILoggerFactory loggerFactory = LoggerFactory.getILoggerFactory();
        container = new DefaultPlexusContainer(cc, new AbstractModule() {
            @Override
            protected void configure() {
                bind(ILoggerFactory.class).toInstance(loggerFactory);
                bind(CoreExports.class).toInstance(exports);
            }
        });
        container.setLookupRealm(null);
        Thread.currentThread().setContextClassLoader(container.getContainerRealm());

        projectBuilder = container.lookup(ProjectBuilder.class);
        projectDependenciesResolver = container.lookup(ProjectDependenciesResolver.class);
        requestPopulator = container.lookup(MavenExecutionRequestPopulator.class);
        settingsBuilder = container.lookup(SettingsBuilder.class);
        aetherRepositorySystem = container.lookup(RepositorySystem.class);
        defaultMaven = (DefaultMaven) container.lookup(Maven.class);
    }

    /**
     * Builds the reactor rooted at {@code pom} without resolving project dependencies.
     */
    public List<ProjectBuildingResult> buildReactorModels(Path pom) throws Exception {
        File pomFile = pom.toAbsolutePath().normalize().toFile();
        File basedir = pomFile.getParentFile();
        System.setProperty("maven.multiModuleProjectDirectory", basedir.getAbsolutePath());

        MavenExecutionRequest execRequest = newDefaultExecutionRequest(pomFile, basedir);
        RepositorySystemSession repoSession = defaultMaven.newRepositorySession(execRequest);

        ProjectBuildingRequest buildingRequest = new DefaultProjectBuildingRequest();
        buildingRequest.setRepositorySession(repoSession);
        buildingRequest.setResolveDependencies(false);
        buildingRequest.setProcessPlugins(true);
        buildingRequest.setLocalRepository(execRequest.getLocalRepository());
        buildingRequest.setRemoteRepositories(execRequest.getRemoteRepositories());
        buildingRequest.setPluginArtifactRepositories(execRequest.getPluginArtifactRepositories());
        buildingRequest.setActiveProfileIds(execRequest.getActiveProfiles());
        buildingRequest.setInactiveProfileIds(execRequest.getInactiveProfiles());
        buildingRequest.setSystemProperties(execRequest.getSystemProperties());
        buildingRequest.setUserProperties(execRequest.getUserProperties());

        List<ProjectBuildingResult> results =
                projectBuilder.build(Collections.singletonList(pomFile), true, buildingRequest);
        lastRepositorySession = repoSession;
        return results;
    }

    /**
     * Opens a repository session that prefers {@code index} for workspace artifacts.
     */
    public RepositorySystemSession openResolveSession(WorkspacePomIndex index) throws Exception {
        MavenExecutionRequest execRequest = newDefaultExecutionRequest(null, new File("."));
        execRequest.setWorkspaceReader(new WorkspacePomReader(index));
        RepositorySystemSession repoSession = defaultMaven.newRepositorySession(execRequest);
        lastRepositorySession = repoSession;
        return repoSession;
    }

    /**
     * Resolves the transitive classpath for {@code project} and sets {@code project.artifacts}.
     * Mirrors {@code DefaultProjectBuilder}'s dependency-resolution step.
     */
    public DependencyResolutionResult resolveDependencies(MavenProject project, RepositorySystemSession repoSession)
            throws DependencyResolutionException {
        DefaultDependencyResolutionRequest resolutionRequest =
                new DefaultDependencyResolutionRequest(project, repoSession);
        DependencyResolutionResult result;
        try {
            result = projectDependenciesResolver.resolve(resolutionRequest);
        } catch (DependencyResolutionException e) {
            result = e.getResult();
            applyResolvedArtifacts(project, result, repoSession);
            throw e;
        }
        applyResolvedArtifacts(project, result, repoSession);
        return result;
    }

    private static void applyResolvedArtifacts(
            MavenProject project, DependencyResolutionResult result, RepositorySystemSession repoSession) {
        Set<Artifact> artifacts = new LinkedHashSet<>();
        if (result != null && result.getDependencyGraph() != null) {
            List<String> trail = Collections.singletonList(project.getArtifact().getId());
            RepositoryUtils.toArtifacts(
                    artifacts, result.getDependencyGraph().getChildren(), trail, null);
            LocalRepositoryManager lrm = repoSession.getLocalRepositoryManager();
            for (Artifact artifact : artifacts) {
                if (!artifact.isResolved()) {
                    String path = lrm.getPathForLocalArtifact(RepositoryUtils.toArtifact(artifact));
                    artifact.setFile(new File(lrm.getRepository().getBasedir(), path));
                }
            }
        }
        project.setResolvedArtifacts(artifacts);
        project.setArtifacts(artifacts);
    }

    public RepositorySystemSession lastRepositorySession() {
        return lastRepositorySession;
    }

    public RepositorySystem aether() {
        return aetherRepositorySystem;
    }

    private MavenExecutionRequest newDefaultExecutionRequest(File pomFile, File basedir) throws Exception {
        MavenExecutionRequest request = new DefaultMavenExecutionRequest();
        if (pomFile != null) {
            request.setPom(pomFile);
        }
        request.setBaseDirectory(basedir);
        request.setInteractiveMode(false);
        request.setSystemProperties(systemAndEnvProperties());
        request.setUserProperties(new Properties());

        Settings settings = buildSettings(request);
        requestPopulator.populateFromSettings(request, settings);
        requestPopulator.populateDefaults(request);
        return request;
    }

    private Settings buildSettings(MavenExecutionRequest request) throws Exception {
        SettingsBuildingRequest settingsRequest = new DefaultSettingsBuildingRequest();
        File userSettings = new File(System.getProperty("user.home"), ".m2/settings.xml");
        if (userSettings.isFile()) {
            settingsRequest.setUserSettingsFile(userSettings);
        }
        settingsRequest.setSystemProperties(request.getSystemProperties());
        settingsRequest.setUserProperties(request.getUserProperties());
        return settingsBuilder.build(settingsRequest).getEffectiveSettings();
    }

    private static Properties systemAndEnvProperties() {
        Properties props = new Properties();
        props.putAll(System.getProperties());
        boolean caseSensitive = !isWindows();
        for (Map.Entry<String, String> entry : System.getenv().entrySet()) {
            String key = "env." + (caseSensitive ? entry.getKey() : entry.getKey().toUpperCase(Locale.ENGLISH));
            props.setProperty(key, entry.getValue());
        }
        return props;
    }

    private static boolean isWindows() {
        String os = System.getProperty("os.name");
        return os != null && os.toLowerCase(Locale.ENGLISH).contains("win");
    }

    @Override
    public void close() {
        try {
            container.dispose();
        } finally {
            Thread.currentThread().setContextClassLoader(previousContextClassLoader);
        }
    }
}
