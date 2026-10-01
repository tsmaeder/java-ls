/**
 * Copyright 2026 by Castle Ridge Software GmbH
 *
 * Author: Thomas Mäder, Castle Ridge Software
 *
 */
package ch.castleridge.javals.mbtimport;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;

import org.eclipse.lsp4j.MessageType;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Merges {@code .javals/mbt.json.<system>} fragments into {@code .javals/mbt.json}.
 */
public final class MbtFragmentMerge {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private MbtFragmentMerge() {}

    /**
     * Applies merge rules for {@code workspace}:
     * <ul>
     *   <li>No fragments → delete merged {@code .javals/mbt.json} if present.</li>
     *   <li>Merged missing, or any fragment newer than merged → rewrite merged from fragments.</li>
     * </ul>
     */
    public static void mergeIfNeeded(Path workspace, List<String> systems, BiConsumer<MessageType, String> log)
            throws IOException {
        Path merged = BuildSystems.mergedPath(workspace);
        List<Path> fragments = existingFragments(workspace, systems);

        if (fragments.isEmpty()) {
            if (Files.isRegularFile(merged)) {
                Files.delete(merged);
                log.accept(MessageType.Info, "Removed " + merged + " (no build-system fragments)");
            }
            return;
        }

        if (!needsMerge(merged, fragments)) {
            log.accept(MessageType.Log, "Up to date: " + merged);
            return;
        }

        JsonObject document = mergeFragments(fragments);
        Path parent = merged.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        try (Writer writer = Files.newBufferedWriter(merged)) {
            GSON.toJson(document, writer);
            writer.write('\n');
        }
        log.accept(MessageType.Info, "Wrote " + merged + " from " + fragments.size() + " fragment(s)");
    }

    static List<Path> existingFragments(Path workspace, List<String> systems) {
        List<Path> out = new ArrayList<>();
        for (String system : systems) {
            Path fragment = BuildSystems.fragmentPath(workspace, system);
            if (Files.isRegularFile(fragment)) {
                out.add(fragment);
            }
        }
        return out;
    }

    static boolean needsMerge(Path merged, List<Path> fragments) throws IOException {
        if (!Files.isRegularFile(merged)) {
            return true;
        }
        long mergedMtime = Files.getLastModifiedTime(merged).toMillis();
        for (Path fragment : fragments) {
            if (Files.getLastModifiedTime(fragment).toMillis() > mergedMtime) {
                return true;
            }
        }
        return false;
    }

    /**
     * Unions {@code dependencyModules} by {@code id} (first wins) and merges {@code namespaces}
     * (later fragments overwrite the same id). Preserves Metals field names via JsonObject.
     */
    static JsonObject mergeFragments(List<Path> fragments) throws IOException {
        Map<String, JsonObject> dependencyModulesById = new LinkedHashMap<>();
        JsonObject namespaces = new JsonObject();

        for (Path fragment : fragments) {
            JsonObject doc = readDocument(fragment);
            JsonArray deps = doc.getAsJsonArray("dependencyModules");
            if (deps != null) {
                for (JsonElement el : deps) {
                    if (!el.isJsonObject()) {
                        continue;
                    }
                    JsonObject module = el.getAsJsonObject();
                    JsonElement idEl = module.get("id");
                    if (idEl == null || !idEl.isJsonPrimitive()) {
                        continue;
                    }
                    String id = idEl.getAsString();
                    dependencyModulesById.putIfAbsent(id, module);
                }
            }
            JsonObject ns = doc.getAsJsonObject("namespaces");
            if (ns != null) {
                for (Map.Entry<String, JsonElement> entry : ns.entrySet()) {
                    namespaces.add(entry.getKey(), entry.getValue());
                }
            }
        }

        JsonObject out = new JsonObject();
        out.add("namespaces", namespaces);
        JsonArray depArray = new JsonArray();
        for (JsonObject module : dependencyModulesById.values()) {
            depArray.add(module);
        }
        out.add("dependencyModules", depArray);
        return out;
    }

    private static JsonObject readDocument(Path file) throws IOException {
        try (Reader reader = Files.newBufferedReader(file)) {
            JsonElement parsed = JsonParser.parseReader(reader);
            if (parsed == null || !parsed.isJsonObject()) {
                return new JsonObject();
            }
            return parsed.getAsJsonObject();
        }
    }
}
