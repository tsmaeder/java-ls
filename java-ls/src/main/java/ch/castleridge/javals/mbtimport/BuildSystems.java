/**
 * Copyright 2026 by Castle Ridge Software GmbH
 *
 * Author: Thomas Mäder, Castle Ridge Software
 *
 */
package ch.castleridge.javals.mbtimport;

import java.nio.file.Path;
import java.util.List;

/**
 * Supported build systems and the fragment file name each writes under {@code .javals/}.
 */
public final class BuildSystems {

    public static final String MAVEN = "maven";

    private static final List<String> ALL = List.of(MAVEN);

    private BuildSystems() {}

    /** Ordered list of build-system ids (currently only {@link #MAVEN}). */
    public static List<String> all() {
        return ALL;
    }

    /** Fragment path: {@code <workspace>/.javals/mbt.json.<system>}. */
    public static Path fragmentPath(Path workspace, String system) {
        return workspace.resolve(".javals").resolve("mbt.json." + system);
    }

    /** Merged path: {@code <workspace>/.javals/mbt.json}. */
    public static Path mergedPath(Path workspace) {
        return workspace.resolve(".javals").resolve("mbt.json");
    }
}
