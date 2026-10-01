/**
 * Copyright 2026 by Castle Ridge Software GmbH
 */
package ch.castleridge.javals.mavenimporter.maven.generated;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Parses user overrides for {@link GeneratedSourceRules} and merges them with the seed registry.
 *
 * <p>Entries with {@code enabled: false} disable matching built-ins (match {@code pluginKey};
 * optionally narrow with {@code scope}, {@code goals}, and/or {@code configPath}). Enabled
 * entries with a full rule body are appended after surviving built-ins.
 */
public final class GeneratedSourceRuleConfig {

    /**
     * One user override. Null optional fields mean the JSON key was absent (do not use for
     * disable matching / do not require for additions).
     */
    public record Entry(
            String pluginKey,
            List<String> goals,
            GeneratedSourceScope scope,
            String configPath,
            Boolean list,
            String defaultPath,
            Boolean required,
            boolean enabled) {

        public Entry {
            Objects.requireNonNull(pluginKey, "pluginKey");
            goals = goals == null ? null : List.copyOf(goals);
        }
    }

    private GeneratedSourceRuleConfig() {}

    /** Merges seed rules with {@code overrides}: apply disables, then append additions. */
    public static List<GeneratedSourceRule> resolve(List<Entry> overrides) {
        List<GeneratedSourceRule> rules = new ArrayList<>(GeneratedSourceRules.all());
        if (overrides == null || overrides.isEmpty()) {
            return List.copyOf(rules);
        }

        List<Entry> disables = new ArrayList<>();
        List<Entry> additions = new ArrayList<>();
        for (Entry entry : overrides) {
            if (entry == null) {
                continue;
            }
            if (!entry.enabled()) {
                disables.add(entry);
            } else {
                additions.add(entry);
            }
        }

        if (!disables.isEmpty()) {
            rules.removeIf(rule -> disables.stream().anyMatch(d -> matches(d, rule)));
        }

        for (Entry addition : additions) {
            GeneratedSourceRule rule = toRule(addition);
            if (rule != null) {
                rules.add(rule);
            } else {
                System.err.println(
                        "Skipping incomplete generatedSourceRules entry for plugin "
                                + addition.pluginKey()
                                + " (need scope and configPath)");
            }
        }
        return List.copyOf(rules);
    }

    /** Reads a JSON array of override objects from {@code file}. */
    public static List<Entry> parseFile(Path file) throws IOException {
        String json = Files.readString(file, StandardCharsets.UTF_8);
        return parseJson(json);
    }

    /** Parses a JSON array of override objects. */
    public static List<Entry> parseJson(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        JsonElement root = JsonParser.parseString(json);
        if (!root.isJsonArray()) {
            throw new IllegalArgumentException("generated-source-rules must be a JSON array");
        }
        return parseArray(root.getAsJsonArray());
    }

    static List<Entry> parseArray(JsonArray array) {
        List<Entry> entries = new ArrayList<>();
        for (JsonElement element : array) {
            if (element == null || !element.isJsonObject()) {
                continue;
            }
            Entry entry = parseObject(element.getAsJsonObject());
            if (entry != null) {
                entries.add(entry);
            }
        }
        return List.copyOf(entries);
    }

    private static Entry parseObject(JsonObject obj) {
        String pluginKey = readString(obj, "pluginKey");
        if (pluginKey == null || pluginKey.isBlank()) {
            System.err.println("Skipping generatedSourceRules entry without pluginKey");
            return null;
        }
        List<String> goals = obj.has("goals") ? readStringList(obj.get("goals")) : null;
        GeneratedSourceScope scope = obj.has("scope") ? parseScope(readString(obj, "scope")) : null;
        String configPath = obj.has("configPath") ? readString(obj, "configPath") : null;
        Boolean list = obj.has("list") ? readBoolean(obj.get("list")) : null;
        String defaultPath = obj.has("defaultPath") ? readString(obj, "defaultPath") : null;
        Boolean required = obj.has("required") ? readBoolean(obj.get("required")) : null;
        boolean enabled = !obj.has("enabled") || Boolean.TRUE.equals(readBoolean(obj.get("enabled")));
        return new Entry(pluginKey.trim(), goals, scope, configPath, list, defaultPath, required, enabled);
    }

    static boolean matches(Entry matcher, GeneratedSourceRule rule) {
        if (!matcher.pluginKey().equals(rule.pluginKey())) {
            return false;
        }
        if (matcher.scope() != null && matcher.scope() != rule.scope()) {
            return false;
        }
        if (matcher.configPath() != null && !matcher.configPath().equals(rule.configPath())) {
            return false;
        }
        if (matcher.goals() != null && !matcher.goals().equals(rule.goals())) {
            return false;
        }
        return true;
    }

    static GeneratedSourceRule toRule(Entry entry) {
        if (entry.scope() == null || entry.configPath() == null || entry.configPath().isBlank()) {
            return null;
        }
        List<String> goals = entry.goals() == null ? List.of() : entry.goals();
        boolean list = entry.list() != null && entry.list();
        boolean required = entry.required() != null && entry.required();
        return new GeneratedSourceRule(
                entry.pluginKey(),
                goals,
                entry.scope(),
                entry.configPath().trim(),
                list,
                entry.defaultPath(),
                required);
    }

    private static GeneratedSourceScope parseScope(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        return switch (raw.trim().toLowerCase(Locale.ROOT)) {
            case "main" -> GeneratedSourceScope.MAIN;
            case "test" -> GeneratedSourceScope.TEST;
            default -> throw new IllegalArgumentException("Unknown generated source scope: " + raw);
        };
    }

    private static String readString(JsonObject obj, String key) {
        JsonElement el = obj.get(key);
        if (el == null || el.isJsonNull() || !el.isJsonPrimitive()) {
            return null;
        }
        return el.getAsString();
    }

    private static List<String> readStringList(JsonElement el) {
        if (el == null || el.isJsonNull() || !el.isJsonArray()) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        for (JsonElement child : el.getAsJsonArray()) {
            if (child != null && child.isJsonPrimitive()) {
                String s = child.getAsString();
                if (s != null && !s.isBlank()) {
                    values.add(s.trim());
                }
            }
        }
        return List.copyOf(values);
    }

    private static Boolean readBoolean(JsonElement el) {
        if (el == null || el.isJsonNull()) {
            return null;
        }
        if (el.isJsonPrimitive() && el.getAsJsonPrimitive().isBoolean()) {
            return el.getAsBoolean();
        }
        if (el.isJsonPrimitive() && el.getAsJsonPrimitive().isString()) {
            String s = el.getAsString();
            if ("true".equalsIgnoreCase(s)) {
                return true;
            }
            if ("false".equalsIgnoreCase(s)) {
                return false;
            }
        }
        return null;
    }
}
