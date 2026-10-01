/**
 * Copyright 2026 by Castle Ridge Software GmbH
 */
package ch.castleridge.javals;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

import org.eclipse.lsp4j.InitializeParams;
import org.eclipse.lsp4j.WorkspaceFolder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import ch.castleridge.javals.mbtimport.BuildSystems;
import ch.castleridge.javals.mbtimport.MbtFragmentMerge;
import ch.castleridge.javals.mbtimport.WorkspaceBuildImport;

class WorkspaceBootstrapTest {

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

        WorkspaceBootstrap bootstrap = new WorkspaceBootstrap();
        bootstrap.setBuildSystemImporter(stub);

        InitializeParams params = new InitializeParams();
        WorkspaceFolder folder = new WorkspaceFolder();
        folder.setUri(workspace.toUri().toString());
        params.setWorkspaceFolders(List.of(folder));

        assertTrue(bootstrap.prepare(params, (t, m) -> {}).isPresent());
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

        WorkspaceBootstrap bootstrap = new WorkspaceBootstrap();
        bootstrap.setBuildSystemImporter(stub);

        InitializeParams params = new InitializeParams();
        WorkspaceFolder folder = new WorkspaceFolder();
        folder.setUri(workspace.toUri().toString());
        params.setWorkspaceFolders(List.of(folder));

        assertTrue(bootstrap.prepare(params, (t, m) -> {}).isPresent());
        assertTrue(imported.get());
        assertTrue(Files.isRegularFile(BuildSystems.mergedPath(workspace)));
    }

    @Test
    void hasRootMbtJsonDetectsRootFileOnly() throws Exception {
        Path workspace = temp.resolve("ws");
        Files.createDirectories(workspace.resolve(".javals"));
        Files.writeString(workspace.resolve(".javals/mbt.json"), "{}");
        assertFalse(WorkspaceBootstrap.hasRootMbtJson(List.of(workspace)));

        Files.writeString(workspace.resolve("mbt.json"), "{}");
        assertTrue(WorkspaceBootstrap.hasRootMbtJson(List.of(workspace)));
    }

    @Test
    void findsJavalsMbtJson() throws Exception {
        Path workspace = temp.resolve("ws");
        Path mbt = workspace.resolve(".javals/mbt.json");
        Files.createDirectories(mbt.getParent());
        Files.writeString(mbt, "{\"namespaces\":{},\"dependencyModules\":[]}");

        Optional<WorkspaceBootstrap.PreparedWorkspace> prepared = prepareWithNoopImporter(workspace);
        assertTrue(prepared.isPresent());
        assertEquals(mbt.toAbsolutePath().normalize(), prepared.get().mbtJson().toAbsolutePath().normalize());
    }

    @Test
    void fallsBackToMetalsMbtJson() throws Exception {
        Path workspace = temp.resolve("ws");
        Path mbt = workspace.resolve(".metals/mbt.json");
        Files.createDirectories(mbt.getParent());
        Files.writeString(mbt, "{\"namespaces\":{},\"dependencyModules\":[]}");

        Optional<WorkspaceBootstrap.PreparedWorkspace> prepared = prepareWithNoopImporter(workspace);
        assertTrue(prepared.isPresent());
        assertEquals(mbt.toAbsolutePath().normalize(), prepared.get().mbtJson().toAbsolutePath().normalize());
    }

    @Test
    void prefersJavalsOverMetalsMbtJson() throws Exception {
        Path workspace = temp.resolve("ws");
        Path javals = workspace.resolve(".javals/mbt.json");
        Path metals = workspace.resolve(".metals/mbt.json");
        Files.createDirectories(javals.getParent());
        Files.createDirectories(metals.getParent());
        Files.writeString(javals, "{\"namespaces\":{},\"dependencyModules\":[]}");
        Files.writeString(metals, "{\"namespaces\":{},\"dependencyModules\":[]}");

        Optional<WorkspaceBootstrap.PreparedWorkspace> prepared = prepareWithNoopImporter(workspace);
        assertTrue(prepared.isPresent());
        assertEquals(javals.toAbsolutePath().normalize(), prepared.get().mbtJson().toAbsolutePath().normalize());
    }

    private Optional<WorkspaceBootstrap.PreparedWorkspace> prepareWithNoopImporter(Path workspace) {
        WorkspaceBootstrap bootstrap = new WorkspaceBootstrap();
        bootstrap.setBuildSystemImporter((ws, log) -> {});

        InitializeParams params = new InitializeParams();
        WorkspaceFolder folder = new WorkspaceFolder();
        folder.setUri(workspace.toUri().toString());
        params.setWorkspaceFolders(List.of(folder));

        return bootstrap.prepare(params, (t, m) -> {});
    }
}
