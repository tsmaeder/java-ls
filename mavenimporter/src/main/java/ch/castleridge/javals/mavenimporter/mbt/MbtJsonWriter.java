/**
 * Copyright 2026 by Castle Ridge Software GmbH
 *
 * Author: Thomas Mäder, Castle Ridge Software
 *
 */
package ch.castleridge.javals.mavenimporter.mbt;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

/**
 * Writes a Metals-dialect {@code mbt.json} to a given path.
 */
public final class MbtJsonWriter {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    private MbtJsonWriter() {}

    /**
     * Writes {@code document} to {@code outputFile}, creating parent directories as needed.
     *
     * @return the normalized path written
     */
    public static Path write(Path outputFile, MbtDocument document) throws IOException {
        Path output = outputFile.toAbsolutePath().normalize();
        Path parent = output.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.writeString(output, GSON.toJson(document) + "\n", StandardCharsets.UTF_8);
        return output;
    }
}
