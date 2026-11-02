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
 * Build-time helper: copies or downloads {@code tree-sitter} and
 * {@code tree-sitter-kotlin} shared libraries into a natives directory.
 *
 * <p>By default downloads every published platform from the pinned GitHub
 * release. Pass {@code -Dtreesitter.useLocal=true} to copy host libs from
 * sibling {@code ../tree-sitter-javals} instead.
 */
public final class FetchTreeSitterNatives {

    private static final List<OsArch> PUBLISHED_PLATFORMS = List.of(
            new OsArch("windows", "x64", "dll"),
            new OsArch("linux", "x64", "so"),
            new OsArch("macos", "arm64", "dylib"));

    private FetchTreeSitterNatives() {}

    public static void main(String[] args) throws Exception {
        Path outDir = Path.of(required("treesitter.natives.dir")).toAbsolutePath().normalize();
        String releaseTag = System.getProperty("treesitter.javals.tag", "v0.1.1");
        String runtimeTag = System.getProperty("treesitter.runtime.tag", "v0.27.1");
        String kotlinTag = System.getProperty("treesitter.kotlin.tag", "0.3.8");
        boolean useLocal = Boolean.parseBoolean(System.getProperty("treesitter.useLocal", "false"));

        Files.createDirectories(outDir);

        if (useLocal) {
            copyFromLocal(outDir);
            return;
        }

        HttpClient client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(30))
                .build();

        for (OsArch oa : PUBLISHED_PLATFORMS) {
            String runtimeMapped = mappedLibraryName("tree-sitter", oa);
            String kotlinMapped = mappedLibraryName("tree-sitter-kotlin", oa);
            download(client, releaseUrl(releaseTag,
                    "tree-sitter-" + runtimeTag + "-" + oa.assetStem() + "." + oa.ext()),
                    outDir.resolve(runtimeMapped));
            download(client, releaseUrl(releaseTag,
                    "tree-sitter-kotlin-" + kotlinTag + "-" + oa.assetStem() + "." + oa.ext()),
                    outDir.resolve(kotlinMapped));
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

        String runtimeMapped = System.mapLibraryName("tree-sitter");
        String kotlinMapped = System.mapLibraryName("tree-sitter-kotlin");
        Path foundRuntime = findSiblingLib(sibling, runtimeMapped);
        Path foundKotlin = findSiblingLib(sibling, kotlinMapped);
        if (foundRuntime == null || foundKotlin == null) {
            throw new IllegalStateException(
                    "treesitter.useLocal=true but libs missing under " + sibling
                            + " (need " + runtimeMapped + " and " + kotlinMapped + ")");
        }
        copy(foundRuntime, outDir.resolve(runtimeMapped));
        copy(foundKotlin, outDir.resolve(kotlinMapped));
        System.out.println("Copied tree-sitter natives from " + sibling);
    }

    private static String required(String key) {
        String v = System.getProperty(key);
        if (v == null || v.isBlank()) {
            throw new IllegalArgumentException("Missing system property: " + key);
        }
        return v;
    }

    private static Path findSiblingLib(Path siblingRoot, String mappedName) {
        Path[] candidates = {
                siblingRoot.resolve("build/Release").resolve(mappedName),
                siblingRoot.resolve("build/Debug").resolve(mappedName),
                siblingRoot.resolve("build").resolve(mappedName),
        };
        for (Path p : candidates) {
            if (Files.isRegularFile(p)) return p;
        }
        return null;
    }

    private static void copy(Path from, Path to) throws IOException {
        Files.copy(from, to, StandardCopyOption.REPLACE_EXISTING);
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

    private record OsArch(String os, String arch, String ext) {
        String assetStem() {
            return os + "-" + arch;
        }
    }
}
