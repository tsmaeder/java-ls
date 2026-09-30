/**
 * Copyright 2026 by Castle Ridge Software GmbH
 */
package ch.castleridge.javals.mavenimporter.mbt;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

/**
 * Writes a Metals-dialect {@code mbt.json} under {@code <directory>/.metals/}.
 */
public final class MbtJsonWriter {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    private MbtJsonWriter() {}

    public static Path write(Path workspaceDirectory, MbtDocument document) throws IOException {
        Path metalsDir = workspaceDirectory.resolve(".metals");
        Files.createDirectories(metalsDir);
        Path output = metalsDir.resolve("mbt.json");
        Files.writeString(output, GSON.toJson(document) + "\n", StandardCharsets.UTF_8);
        return output;
    }
}
