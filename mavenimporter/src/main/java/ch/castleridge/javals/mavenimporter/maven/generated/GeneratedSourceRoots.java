/**
 * Copyright 2026 by Castle Ridge Software GmbH
 */
package ch.castleridge.javals.mavenimporter.maven.generated;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.apache.maven.model.Plugin;
import org.apache.maven.model.PluginExecution;
import org.apache.maven.project.MavenProject;
import org.codehaus.plexus.util.xml.Xpp3Dom;

/**
 * Collects generated source directories from a project's effective plugins using
 * {@link GeneratedSourceRules}, without executing any mojos.
 */
public final class GeneratedSourceRoots {

    private GeneratedSourceRoots() {}

    /**
     * Returns absolute paths for plugin-generated roots for the main or test namespace.
     * Paths are emitted even when the directories do not yet exist.
     */
    public static List<String> collect(MavenProject project, boolean test) {
        if (project == null) {
            return List.of();
        }
        GeneratedSourceScope scope = test ? GeneratedSourceScope.TEST : GeneratedSourceScope.MAIN;
        LinkedHashSet<String> roots = new LinkedHashSet<>();
        for (GeneratedSourceRule rule : GeneratedSourceRules.all()) {
            if (rule.scope() != scope) {
                continue;
            }
            Plugin plugin = project.getPlugin(rule.pluginKey());
            if (plugin == null) {
                continue;
            }
            roots.addAll(resolvePlugin(project, plugin, rule));
        }
        return new ArrayList<>(roots);
    }

    private static List<String> resolvePlugin(MavenProject project, Plugin plugin, GeneratedSourceRule rule) {
        LinkedHashSet<String> roots = new LinkedHashSet<>();
        Xpp3Dom pluginConfig = asDom(plugin.getConfiguration());

        if (rule.hasGoals()) {
            for (PluginExecution execution : plugin.getExecutions()) {
                if (!goalsIntersect(execution.getGoals(), rule.goals())) {
                    continue;
                }
                roots.addAll(resolveConfig(project, asDom(execution.getConfiguration()), pluginConfig, rule));
            }
            return new ArrayList<>(roots);
        }

        roots.addAll(resolveConfig(project, null, pluginConfig, rule));
        for (PluginExecution execution : plugin.getExecutions()) {
            Xpp3Dom execConfig = asDom(execution.getConfiguration());
            if (configPathPresent(execConfig, rule)) {
                roots.addAll(resolveConfig(project, execConfig, pluginConfig, rule));
            }
        }
        return new ArrayList<>(roots);
    }

    private static List<String> resolveConfig(
            MavenProject project, Xpp3Dom primary, Xpp3Dom fallback, GeneratedSourceRule rule) {
        if (rule.list()) {
            List<String> values = readList(primary, rule.configPath());
            if (values.isEmpty()) {
                values = readList(fallback, rule.configPath());
            }
            if (values.isEmpty()) {
                if (rule.defaultPath() != null) {
                    String resolved = resolvePath(project, rule.defaultPath());
                    return resolved == null ? List.of() : List.of(resolved);
                }
                return List.of();
            }
            LinkedHashSet<String> resolved = new LinkedHashSet<>();
            for (String value : values) {
                String path = resolvePath(project, value);
                if (path != null) {
                    resolved.add(path);
                }
            }
            return new ArrayList<>(resolved);
        }

        String value = firstNonBlank(readSingle(primary, rule.configPath()), readSingle(fallback, rule.configPath()));
        if (value == null) {
            value = rule.defaultPath();
        }
        if (value == null || value.isBlank()) {
            return List.of();
        }
        String resolved = resolvePath(project, value);
        return resolved == null ? List.of() : List.of(resolved);
    }

    static String resolvePath(MavenProject project, String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String interpolated = interpolate(project, raw.trim());
        File file = new File(interpolated);
        if (!file.isAbsolute()) {
            File basedir = project.getBasedir();
            if (basedir != null) {
                file = new File(basedir, interpolated);
            }
        }
        return file.getAbsolutePath();
    }

    private static String interpolate(MavenProject project, String value) {
        String result = value;
        String buildDir = project.getBuild() != null ? project.getBuild().getDirectory() : null;
        if (buildDir != null) {
            result = result.replace("${project.build.directory}", buildDir);
        }
        File basedir = project.getBasedir();
        if (basedir != null) {
            String base = basedir.getAbsolutePath();
            result = result.replace("${project.basedir}", base);
            result = result.replace("${basedir}", base);
        }
        return result;
    }

    private static boolean goalsIntersect(List<String> executionGoals, List<String> ruleGoals) {
        if (executionGoals == null || executionGoals.isEmpty()) {
            return false;
        }
        Set<String> wanted = Set.copyOf(ruleGoals);
        for (String goal : executionGoals) {
            if (wanted.contains(goal)) {
                return true;
            }
        }
        return false;
    }

    private static boolean configPathPresent(Xpp3Dom config, GeneratedSourceRule rule) {
        if (config == null) {
            return false;
        }
        if (rule.list()) {
            return !readList(config, rule.configPath()).isEmpty();
        }
        String value = readSingle(config, rule.configPath());
        return value != null && !value.isBlank();
    }

    private static String readSingle(Xpp3Dom config, String configPath) {
        Xpp3Dom node = navigate(config, configPath);
        if (node == null) {
            return null;
        }
        String value = node.getValue();
        return value != null && !value.isBlank() ? value.trim() : null;
    }

    private static List<String> readList(Xpp3Dom config, String configPath) {
        if (config == null || configPath == null || configPath.isBlank()) {
            return List.of();
        }
        int slash = configPath.lastIndexOf('/');
        String parentPath;
        String childName;
        if (slash < 0) {
            parentPath = null;
            childName = configPath;
        } else {
            parentPath = configPath.substring(0, slash);
            childName = configPath.substring(slash + 1);
        }
        Xpp3Dom parent = parentPath == null ? config : navigate(config, parentPath);
        if (parent == null) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        for (Xpp3Dom child : parent.getChildren(childName)) {
            if (child.getValue() != null && !child.getValue().isBlank()) {
                values.add(child.getValue().trim());
            }
        }
        return values;
    }

    private static Xpp3Dom navigate(Xpp3Dom root, String path) {
        if (root == null || path == null || path.isBlank()) {
            return null;
        }
        Xpp3Dom current = root;
        for (String segment : path.split("/")) {
            if (segment.isEmpty()) {
                continue;
            }
            current = current.getChild(segment);
            if (current == null) {
                return null;
            }
        }
        return current;
    }

    private static Xpp3Dom asDom(Object configuration) {
        return configuration instanceof Xpp3Dom dom ? dom : null;
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return null;
    }
}
