/**
 * Copyright 2026 by Castle Ridge Software GmbH
 *
 * Author: Thomas Mäder, Castle Ridge Software
 *
 */
package ch.castleridge.javals.mbtimport;

import java.nio.file.Path;

/**
 * Build-system fragment paths under {@code .javals/}.
 *
 * <p>Which systems run is determined by the {@code importers} preference map
 * (see {@link BuildSystemImporter}), not by a fixed registry here.
 */
public final class BuildSystems {

    /** Default / shipped Maven importer id (also used for generated-source-rules). */
    public static final String MAVEN = "maven";

    private BuildSystems() {}

    /** Fragment path: {@code <workspace>/.javals/mbt.json.<system>}. */
    public static Path fragmentPath(Path workspace, String system) {
        return workspace.resolve(".javals").resolve("mbt.json." + system);
    }

    /** Merged path: {@code <workspace>/.javals/mbt.json}. */
    public static Path mergedPath(Path workspace) {
        return workspace.resolve(".javals").resolve("mbt.json");
    }
}
