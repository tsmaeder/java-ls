/**
 * Copyright 2026 by Castle Ridge Software GmbH
 *
 * Author: Thomas Mäder, Castle Ridge Software
 *
 */
package ch.castleridge.javals.mavenimporter.mbt;

import java.util.ArrayList;
import java.util.List;

/**
 * One namespace (build target) in Metals-dialect {@code mbt.json}.
 */
public final class MbtNamespace {
    public List<String> sources = new ArrayList<>();
    public List<String> javacOptions = new ArrayList<>();
    public List<String> dependencyModules = new ArrayList<>();
    public String javaHome;
    public List<String> dependsOn = new ArrayList<>();
    public List<String> classDirectories = new ArrayList<>();
    public String projectPath;
}
