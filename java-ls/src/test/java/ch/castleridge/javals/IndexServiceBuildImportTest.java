/**
 * Copyright 2026 by Castle Ridge Software GmbH
 */
package ch.castleridge.javals;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import org.eclipse.lsp4j.InitializeParams;
import org.eclipse.lsp4j.WorkspaceFolder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import ch.castleridge.javals.mbtimport.BuildSystems;
import ch.castleridge.javals.mbtimport.MbtFragmentMerge;
import ch.castleridge.javals.mbtimport.WorkspaceBuildImport;

class IndexServiceBuildImportTest {

    @TempDir
    Path temp;

    @Test
    void skipsImporterWhenRootMbtJsonPresent() throws Exception {
        Path workspace = temp.resolve("ws");
        Files.createDirectories(workspace);
        Files.writeString(workspace.resolve("mbt.json"), """
                {"namespaces":{},"dependencyModules":[]}
                """);

        AtomicBoolean imported = new AtomicBoolean(false);
        WorkspaceBuildImport stub = (ws, log) -> imported.set(true);

        IndexService service = new IndexService(null);
        service.setBuildSystemImporter(stub);

        InitializeParams params = new InitializeParams();
        WorkspaceFolder folder = new WorkspaceFolder();
        folder.setUri(workspace.toUri().toString());
        params.setWorkspaceFolders(List.of(folder));

        service.initialize(params).join();
        assertFalse(imported.get());
    }

    @Test
    void runsImporterWhenNoRootMbtJson() throws Exception {
        Path workspace = temp.resolve("ws");
        Files.createDirectories(workspace);

        AtomicBoolean imported = new AtomicBoolean(false);
        WorkspaceBuildImport stub = (ws, log) -> {
            imported.set(true);
            try {
                Path fragment = BuildSystems.fragmentPath(ws, "maven");
                Files.createDirectories(fragment.getParent());
                Files.writeString(fragment, """
                        {"namespaces":{},"dependencyModules":[]}
                        """);
                MbtFragmentMerge.mergeIfNeeded(ws, BuildSystems.all(), log);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        };

        IndexService service = new IndexService(null);
        service.setBuildSystemImporter(stub);

        InitializeParams params = new InitializeParams();
        WorkspaceFolder folder = new WorkspaceFolder();
        folder.setUri(workspace.toUri().toString());
        params.setWorkspaceFolders(List.of(folder));

        service.initialize(params).join();
        assertTrue(imported.get());
        assertTrue(Files.isRegularFile(BuildSystems.mergedPath(workspace)));
    }

    @Test
    void hasRootMbtJsonDetectsRootFileOnly() throws Exception {
        Path workspace = temp.resolve("ws");
        Files.createDirectories(workspace.resolve(".metals"));
        Files.writeString(workspace.resolve(".metals/mbt.json"), "{}");
        assertFalse(IndexService.hasRootMbtJson(List.of(workspace)));

        Files.writeString(workspace.resolve("mbt.json"), "{}");
        assertTrue(IndexService.hasRootMbtJson(List.of(workspace)));
    }
}
