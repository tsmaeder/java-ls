/**
 * Copyright 2026 by Castle Ridge Software GmbH
 *
 * Author: Thomas Mäder, Castle Ridge Software
 *
 */
package ch.castleridge.javals.indexing.treesitter;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Build-time helper: copies or downloads the {@code tree_sitter_javals}
 * executable into a natives directory, one subdirectory per platform.
 *
 * <p>By default downloads every published platform zip from the pinned GitHub
 * release ({@code tree_sitter_javals-{os-arch}.zip}, each containing a
 * {@code {os-arch}/} folder with the executable). Pass
 * {@code -Dtreesitter.useLocal=true} to copy the host executable from sibling
 * {@code ../tree-sitter-javals} instead.
 */
public final class FetchTreeSitterNatives {

    private static final List<OsArch> PUBLISHED_PLATFORMS = List.of(
            new OsArch("windows", "x64", ".exe"),
            new OsArch("linux", "x64", ""),
            new OsArch("macos", "arm64", ""));

    private FetchTreeSitterNatives() {}

    public static void main(String[] args) throws Exception {
        Path outDir = Path.of(required("treesitter.natives.dir")).toAbsolutePath().normalize();
        String releaseTag = System.getProperty("treesitter.javals.tag", "v0.1.2");
        boolean useLocal = Boolean.parseBoolean(System.getProperty("treesitter.useLocal", "false"));

        Files.createDirectories(outDir);
        clearDirectory(outDir);

        if (useLocal) {
            copyFromLocal(outDir);
            return;
        }

        HttpClient client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(30))
                .build();

        for (OsArch oa : PUBLISHED_PLATFORMS) {
            String zipName = "tree_sitter_javals-" + oa.assetStem() + ".zip";
            Path zipPath = outDir.resolve(zipName);
            download(client, releaseUrl(releaseTag, zipName), zipPath);
            unzip(zipPath, outDir);
            Files.deleteIfExists(zipPath);

            Path exe = outDir.resolve(oa.assetStem()).resolve("tree_sitter_javals" + oa.exeExt());
            if (!Files.isRegularFile(exe)) {
                throw new IOException("Zip " + zipName + " did not contain " + exe);
            }
            markExecutable(exe);
        }
        System.out.println("Downloaded tree_sitter_javals for all platforms into " + outDir);
    }

    private static void copyFromLocal(Path outDir) throws IOException {
        Path repoRoot = Path.of(required("treesitter.repo.root")).toAbsolutePath().normalize();
        Path sibling = repoRoot.resolveSibling("tree-sitter-javals");
        if (!Files.isDirectory(sibling)) {
            throw new IllegalStateException(
                    "treesitter.useLocal=true but sibling not found: " + sibling);
        }

        OsArch host = hostPlatform();
        Path platformDir = outDir.resolve(host.assetStem());
        Files.createDirectories(platformDir);

        String exeName = "tree_sitter_javals" + host.exeExt();
        Path foundExe = findSiblingArtifact(sibling, exeName);
        if (foundExe == null) {
            throw new IllegalStateException(
                    "treesitter.useLocal=true but executable missing under " + sibling
                            + " (need " + exeName + ")");
        }
        Path dest = platformDir.resolve(exeName);
        copy(foundExe, dest);
        markExecutable(dest);
        System.out.println("Copied tree_sitter_javals from " + sibling
                + " into " + platformDir);
    }

    private static void markExecutable(Path exe) {
        // no-op on Windows; needed after download/copy on Unix
        exe.toFile().setExecutable(true, false);
    }

    private static OsArch hostPlatform() {
        String osName = System.getProperty("os.name", "").toLowerCase();
        String arch = System.getProperty("os.arch", "").toLowerCase();
        boolean x64 = arch.equals("amd64") || arch.equals("x86_64") || arch.equals("x64");
        boolean arm64 = arch.equals("aarch64") || arch.equals("arm64");

        if (osName.contains("win") && x64) {
            return PUBLISHED_PLATFORMS.get(0);
        }
        if (osName.contains("linux") && x64) {
            return PUBLISHED_PLATFORMS.get(1);
        }
        if ((osName.contains("mac") || osName.contains("darwin")) && arm64) {
            return PUBLISHED_PLATFORMS.get(2);
        }
        throw new IllegalStateException(
                "Unsupported host platform for local tree_sitter_javals copy: os.name="
                        + System.getProperty("os.name") + " os.arch="
                        + System.getProperty("os.arch"));
    }

    private static String required(String key) {
        String v = System.getProperty(key);
        if (v == null || v.isBlank()) {
            throw new IllegalArgumentException("Missing system property: " + key);
        }
        return v;
    }

    private static Path findSiblingArtifact(Path siblingRoot, String fileName) {
        Path[] candidates = {
                siblingRoot.resolve("build/Release").resolve(fileName),
                siblingRoot.resolve("build/Debug").resolve(fileName),
                siblingRoot.resolve("build").resolve(fileName),
        };
        for (Path p : candidates) {
            if (Files.isRegularFile(p)) return p;
        }
        return null;
    }

    private static void copy(Path from, Path to) throws IOException {
        Files.copy(from, to, StandardCopyOption.REPLACE_EXISTING);
    }

    private static void clearDirectory(Path dir) throws IOException {
        if (!Files.isDirectory(dir)) {
            return;
        }
        try (var stream = Files.walk(dir)) {
            stream.sorted(java.util.Comparator.reverseOrder())
                    .filter(p -> !p.equals(dir))
                    .forEach(p -> {
                        try {
                            Files.deleteIfExists(p);
                        } catch (IOException e) {
                            throw new RuntimeException("Failed to delete " + p, e);
                        }
                    });
        }
    }

    private static String releaseUrl(String tag, String asset) {
        return "https://github.com/tsmaeder/tree-sitter-javals/releases/download/"
                + tag + "/" + asset;
    }

    private static void download(HttpClient client, String url, Path dest)
            throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofMinutes(2))
                .GET()
                .build();
        HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() != 200) {
            throw new IOException("Download failed HTTP " + response.statusCode() + " for " + url);
        }
        try (InputStream in = response.body()) {
            Files.copy(in, dest, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /** Unzips {@code zip} into {@code destDir}, rejecting path-traversal entries. */
    private static void unzip(Path zip, Path destDir) throws IOException {
        Path destRoot = destDir.toAbsolutePath().normalize();
        try (InputStream fileIn = Files.newInputStream(zip);
                ZipInputStream zin = new ZipInputStream(fileIn)) {
            ZipEntry entry;
            while ((entry = zin.getNextEntry()) != null) {
                Path out = destRoot.resolve(entry.getName()).normalize();
                if (!out.startsWith(destRoot)) {
                    throw new IOException("Zip entry escapes destination: " + entry.getName());
                }
                if (entry.isDirectory()) {
                    Files.createDirectories(out);
                } else {
                    Files.createDirectories(out.getParent());
                    Files.copy(zin, out, StandardCopyOption.REPLACE_EXISTING);
                }
                zin.closeEntry();
            }
        }
    }

    private record OsArch(String os, String arch, String exeExt) {
        String assetStem() {
            return os + "-" + arch;
        }
    }
}
