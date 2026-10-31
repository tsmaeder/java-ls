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
import java.util.Locale;

/**
 * Build-time helper: copies or downloads {@code tree-sitter} and
 * {@code tree-sitter-kotlin} shared libraries into a natives directory.
 *
 * <p>Dev (SNAPSHOT + sibling {@code ../tree-sitter-javals}): copy from the
 * local CMake build output. Otherwise download from the pinned GitHub
 * release.
 */
public final class FetchTreeSitterNatives {

    private FetchTreeSitterNatives() {}

    public static void main(String[] args) throws Exception {
        Path outDir = Path.of(required("treesitter.natives.dir")).toAbsolutePath().normalize();
        Path repoRoot = Path.of(required("treesitter.repo.root")).toAbsolutePath().normalize();
        String projectVersion = required("treesitter.project.version");
        String releaseTag = System.getProperty("treesitter.javals.tag", "v0.1.1");
        String runtimeTag = System.getProperty("treesitter.runtime.tag", "v0.27.1");
        String kotlinTag = System.getProperty("treesitter.kotlin.tag", "0.3.8");

        Files.createDirectories(outDir);

        String runtimeMapped = System.mapLibraryName("tree-sitter");
        String kotlinMapped = System.mapLibraryName("tree-sitter-kotlin");
        Path runtimeOut = outDir.resolve(runtimeMapped);
        Path kotlinOut = outDir.resolve(kotlinMapped);

        // Sibling of the java-ls repo: ../tree-sitter-javals
        Path sibling = repoRoot.resolveSibling("tree-sitter-javals");
        boolean snapshot = projectVersion.endsWith("-SNAPSHOT");
        if (snapshot && Files.isDirectory(sibling)) {
            Path foundRuntime = findSiblingLib(sibling, runtimeMapped);
            Path foundKotlin = findSiblingLib(sibling, kotlinMapped);
            if (foundRuntime != null && foundKotlin != null) {
                copy(foundRuntime, runtimeOut);
                copy(foundKotlin, kotlinOut);
                System.out.println("Copied tree-sitter natives from " + sibling);
                return;
            }
            System.out.println("Sibling tree-sitter-javals present but libs missing; falling back to download");
        }

        OsArch oa = detectOsArch();
        download(releaseUrl(releaseTag, "tree-sitter-" + runtimeTag + "-" + oa.assetStem() + "." + oa.ext()),
                runtimeOut);
        download(releaseUrl(releaseTag, "tree-sitter-kotlin-" + kotlinTag + "-" + oa.assetStem() + "." + oa.ext()),
                kotlinOut);
        System.out.println("Downloaded tree-sitter natives into " + outDir);
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

    private static void download(String url, Path dest) throws IOException, InterruptedException {
        HttpClient client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(30))
                .build();
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

    private static OsArch detectOsArch() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        String osKey;
        String ext;
        if (os.contains("win")) {
            osKey = "windows";
            ext = "dll";
        } else if (os.contains("mac") || os.contains("darwin")) {
            osKey = "macos";
            ext = "dylib";
        } else if (os.contains("linux")) {
            osKey = "linux";
            ext = "so";
        } else {
            throw new IllegalStateException("Unsupported OS for tree-sitter natives: " + os);
        }
        String archKey;
        if (arch.equals("amd64") || arch.equals("x86_64")) {
            archKey = "x64";
        } else if (arch.equals("aarch64") || arch.equals("arm64")) {
            archKey = "arm64";
        } else {
            throw new IllegalStateException("Unsupported arch for tree-sitter natives: " + arch);
        }
        return new OsArch(osKey, archKey, ext);
    }

    private record OsArch(String os, String arch, String ext) {
        String assetStem() {
            return os + "-" + arch;
        }
    }
}
