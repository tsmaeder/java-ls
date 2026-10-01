/**
 * Copyright 2026 by Castle Ridge Software GmbH
 */
package ch.castleridge.javals;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.BiConsumer;

import org.eclipse.lsp4j.InitializeParams;
import org.eclipse.lsp4j.MessageType;
import org.eclipse.lsp4j.WorkspaceFolder;

import ch.castleridge.javals.mbtimport.BuildSystemImporter;
import ch.castleridge.javals.mbtimport.WorkspaceBuildImport;

/**
 * Ensures an {@code mbt.json} is available for indexing: when no root-level
 * {@code mbt.json} exists, runs registered build-system importers and merges
 * fragments into {@code .metals/mbt.json}, then locates the file to load.
 */
public final class WorkspaceBootstrap {

    private volatile WorkspaceBuildImport buildSystemImporter;

    /** Package-visible for tests that stub importer invocation. */
    void setBuildSystemImporter(WorkspaceBuildImport buildSystemImporter) {
        this.buildSystemImporter = buildSystemImporter;
    }

    /**
     * Optionally runs build-system importers, then locates {@code mbt.json}
     * under the workspace roots. Returns empty when no mbt file is found.
     */
    public Optional<PreparedWorkspace> prepare(InitializeParams params, BiConsumer<MessageType, String> log) {
        BiConsumer<MessageType, String> logger = log == null ? (t, m) -> {} : log;
        List<Path> roots = workspaceRoots(params);
        Path workspacePath = resolveWorkspacePath(params, roots, null);
        if (!hasRootMbtJson(roots)) {
            WorkspaceBuildImport importer = resolveBuildSystemImporter(params);
            if (workspacePath != null) {
                importer.importAndMerge(workspacePath, logger);
            }
        }
        Path mbt = findMbtJson(roots);
        if (mbt == null) {
            logger.accept(MessageType.Info, "No mbt.json found in workspace roots; index disabled");
            return Optional.empty();
        }
        Path ws = workspacePath != null
                ? workspacePath
                : resolveWorkspacePath(params, roots, mbt);
        logger.accept(MessageType.Info, "Loading mbt.json: " + mbt);
        return Optional.of(new PreparedWorkspace(mbt, ws));
    }

    private WorkspaceBuildImport resolveBuildSystemImporter(InitializeParams params) {
        if (buildSystemImporter != null) {
            return buildSystemImporter;
        }
        return new BuildSystemImporter(
                BuildSystemImporter.resolveScripts(InitializationOptions.importerScripts(params)),
                InitializationOptions.mavenGeneratedSourceRulesJson(params).orElse(null));
    }

    static boolean hasRootMbtJson(List<Path> roots) {
        for (Path root : roots) {
            if (Files.isRegularFile(root.resolve("mbt.json"))) {
                return true;
            }
        }
        return false;
    }

    private static Path resolveWorkspacePath(InitializeParams params, List<Path> roots, Path mbt) {
        Path fromOptions = workspacePathFromInitializationOptions(params);
        if (fromOptions != null) {
            return fromOptions.toAbsolutePath().normalize();
        }
        if (!roots.isEmpty()) {
            return roots.get(0).toAbsolutePath().normalize();
        }
        if (mbt != null) {
            return mbt.toAbsolutePath().normalize().getParent();
        }
        return null;
    }

    private static Path workspacePathFromInitializationOptions(InitializeParams params) {
        return InitializationOptions.workspacePath(params)
                .map(ClientPaths::fromClientString)
                .orElse(null);
    }

    private static List<Path> workspaceRoots(InitializeParams params) {
        List<Path> roots = new ArrayList<>();
        if (params == null) return roots;
        List<WorkspaceFolder> folders = params.getWorkspaceFolders();
        if (folders != null) {
            for (WorkspaceFolder f : folders) {
                Path p = ClientPaths.fromClientString(f.getUri());
                if (p != null) roots.add(p);
            }
        }
        if (roots.isEmpty()) {
            @SuppressWarnings("deprecation")
            String rootUri = params.getRootUri();
            Path p = ClientPaths.fromClientString(rootUri);
            if (p != null) roots.add(p);
            if (p == null) {
                @SuppressWarnings("deprecation")
                String rootPath = params.getRootPath();
                if (rootPath != null && !rootPath.isBlank()) {
                    roots.add(Paths.get(rootPath));
                }
            }
        }
        return roots;
    }

    private static Path findMbtJson(List<Path> roots) {
        for (Path root : roots) {
            Path candidate = root.resolve("mbt.json");
            if (Files.isRegularFile(candidate)) return candidate;
            candidate = root.resolve(".metals", "mbt.json");
            if (Files.isRegularFile(candidate)) return candidate;
        }
        return null;
    }

    /** Result of preparing a workspace for indexing. */
    public record PreparedWorkspace(Path mbtJson, Path workspace) {}
}
