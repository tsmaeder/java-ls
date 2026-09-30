/**
 * Copyright 2026 by Castle Ridge Software GmbH
 */
package ch.castleridge.javals.mbtimport;

import java.nio.file.Path;
import java.util.function.BiConsumer;

import org.eclipse.lsp4j.MessageType;

/**
 * Runs build-system importers for a workspace and merges fragments into {@code mbt.json}.
 */
@FunctionalInterface
public interface WorkspaceBuildImport {

    void importAndMerge(Path workspace, BiConsumer<MessageType, String> log);
}
