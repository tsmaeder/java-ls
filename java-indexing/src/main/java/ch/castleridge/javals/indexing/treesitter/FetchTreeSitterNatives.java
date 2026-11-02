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

/**
 * Build-time helper: copies or downloads {@code tree-sitter} /
 * {@code tree-sitter-kotlin} shared libraries and the {@code tree_sitter_javals}
 * executable into a natives directory, one subdirectory per platform.
 *
 * <p>By default downloads every published platform from the pinned GitHub
 * release. Pass {@code -Dtreesitter.useLocal=true} to copy host artifacts from
 * sibling {@code ../tree-sitter-javals} instead.
 */
public final class FetchTreeSitterNatives {

    private static final List<OsArch> PUBLISHED_PLATFORMS = List.of(
            new OsArch("windows", "x64", "dll", ".exe"),
            new OsArch("linux", "x64", "so", ""),
            new OsArch("macos", "arm64", "dylib", ""));

    private FetchTreeSitterNatives() {}

    public static void main(String[] args) throws Exception {
        Path outDir = Path.of(required("treesitter.natives.dir")).toAbsolutePath().normalize();
        String releaseTag = System.getProperty("treesitter.javals.tag", "v0.1.1");
        String runtimeTag = System.getProperty("treesitter.runtime.tag", "v0.27.1");
        String kotlinTag = System.getProperty("treesitter.kotlin.tag", "0.3.8");
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
            Path platformDir = outDir.resolve(oa.assetStem());
            Files.createDirectories(platformDir);

            String runtimeMapped = mappedLibraryName("tree-sitter", oa);
            String kotlinMapped = mappedLibraryName("tree-sitter-kotlin", oa);
            String exeName = "tree_sitter_javals" + oa.exeExt();

            download(client, releaseUrl(releaseTag,
                    "tree-sitter-" + runtimeTag + "-" + oa.assetStem() + "." + oa.ext()),
                    platformDir.resolve(runtimeMapped));
            download(client, releaseUrl(releaseTag,
                    "tree-sitter-kotlin-" + kotlinTag + "-" + oa.assetStem() + "." + oa.ext()),
                    platformDir.resolve(kotlinMapped));
            download(client, releaseUrl(releaseTag,
                    "tree_sitter_javals-" + oa.assetStem() + oa.exeExt()),
                    platformDir.resolve(exeName));
        }
        System.out.println("Downloaded tree-sitter natives for all platforms into " + outDir);
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

        String runtimeMapped = mappedLibraryName("tree-sitter", host);
        String kotlinMapped = mappedLibraryName("tree-sitter-kotlin", host);
        String exeName = "tree_sitter_javals" + host.exeExt();

        Path foundRuntime = findSiblingArtifact(sibling, runtimeMapped);
        Path foundKotlin = findSiblingArtifact(sibling, kotlinMapped);
        Path foundExe = findSiblingArtifact(sibling, exeName);
        if (foundRuntime == null || foundKotlin == null || foundExe == null) {
            throw new IllegalStateException(
                    "treesitter.useLocal=true but artifacts missing under " + sibling
                            + " (need " + runtimeMapped + ", " + kotlinMapped
                            + ", and " + exeName + ")");
        }
        copy(foundRuntime, platformDir.resolve(runtimeMapped));
        copy(foundKotlin, platformDir.resolve(kotlinMapped));
        copy(foundExe, platformDir.resolve(exeName));
        System.out.println("Copied tree-sitter natives from " + sibling
                + " into " + platformDir);
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
                "Unsupported host platform for local tree-sitter copy: os.name="
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

    /** JNI-style library filename for a target OS (not the build host). */
    private static String mappedLibraryName(String name, OsArch oa) {
        return switch (oa.os()) {
            case "windows" -> name + "." + oa.ext();
            case "linux", "macos" -> "lib" + name + "." + oa.ext();
            default -> throw new IllegalStateException("Unsupported OS: " + oa.os());
        };
    }

    private record OsArch(String os, String arch, String ext, String exeExt) {
        String assetStem() {
            return os + "-" + arch;
        }
    }
}
