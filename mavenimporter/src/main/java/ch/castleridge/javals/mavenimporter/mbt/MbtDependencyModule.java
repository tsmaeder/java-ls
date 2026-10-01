/**
 * Copyright 2026 by Castle Ridge Software GmbH
 *
 * Author: Thomas Mäder, Castle Ridge Software
 *
 */
package ch.castleridge.javals.mavenimporter.mbt;

/**
 * One external dependency jar in the top-level {@code dependencyModules} array.
 */
public final class MbtDependencyModule {
    public String id;
    public String jar;
    public String sources;

    public MbtDependencyModule() {}

    public MbtDependencyModule(String id, String jar, String sources) {
        this.id = id;
        this.jar = jar;
        this.sources = sources;
    }
}
