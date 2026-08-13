/**
 * Copyright 2026 by Anysphere Inc.
 * 
 * Licensed under the MIT License.
 * 
 * SPDX-License-Identifier: MIT
 *
 * Author: Thomas Mäder, Castle Ridge Software
 *
 */
package ch.castleridge.javals;

import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;

import org.eclipse.lsp4j.InitializeParams;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

/**
 * Reads java-ls settings from LSP {@link InitializeParams#getInitializationOptions()}.
 */
final class InitializationOptions {

    record Backend(String indexer, String compiler) {
        static final Backend DEFAULT = new Backend("javac", "javac");
    }

    private InitializationOptions() {}

    static OptionalInt referencesCandidateCap(InitializeParams params) {
        return readOptionalInt(optionsObject(params), "referencesCandidateCap");
    }

    static Optional<String> workspacePath(InitializeParams params) {
        Object options = params == null ? null : params.getInitializationOptions();
        if (options instanceof Map<?, ?> map) {
            Object v = map.get("workspacePath");
            return v instanceof String s && !s.isBlank() ? Optional.of(s) : Optional.empty();
        }
        if (options instanceof JsonObject json) {
            JsonElement el = json.get("workspacePath");
            if (el != null && el.isJsonPrimitive() && el.getAsJsonPrimitive().isString()) {
                String s = el.getAsString();
                return s == null || s.isBlank() ? Optional.empty() : Optional.of(s);
            }
        }
        return Optional.empty();
    }

    static Backend backend(InitializeParams params) {
        Object options = params == null ? null : params.getInitializationOptions();
        Object backend = null;
        if (options instanceof Map<?, ?> map) {
            backend = map.get("backend");
        } else if (options instanceof JsonObject json) {
            backend = json.get("backend");
        }
        if (backend == null) {
            return Backend.DEFAULT;
        }
        String indexer = Backend.DEFAULT.indexer();
        String compiler = Backend.DEFAULT.compiler();
        if (backend instanceof Map<?, ?> map) {
            indexer = readBackendName(map.get("indexer"), indexer);
            compiler = readBackendName(map.get("compiler"), compiler);
        } else if (backend instanceof JsonObject json) {
            indexer = readBackendName(json.get("indexer"), indexer);
            compiler = readBackendName(json.get("compiler"), compiler);
        }
        return new Backend(indexer, compiler);
    }

    private static Object optionsObject(InitializeParams params) {
        return params == null ? null : params.getInitializationOptions();
    }

    private static OptionalInt readOptionalInt(Object options, String key) {
        if (options instanceof Map<?, ?> map) {
            return readCap(map.get(key));
        }
        if (options instanceof JsonObject json) {
            return readCap(json.get(key));
        }
        return OptionalInt.empty();
    }

    private static String readBackendName(Object value, String defaultValue) {
        if (value == null) return defaultValue;
        if (value instanceof String s) {
            return normalizeBackend(s, defaultValue);
        }
        if (value instanceof JsonElement el) {
            if (!el.isJsonPrimitive()) return defaultValue;
            JsonPrimitive p = el.getAsJsonPrimitive();
            if (p.isString()) {
                return normalizeBackend(p.getAsString(), defaultValue);
            }
        }
        return defaultValue;
    }

    private static String normalizeBackend(String raw, String defaultValue) {
        if (raw == null || raw.isBlank()) return defaultValue;
        String n = raw.trim().toLowerCase(Locale.ROOT);
        return switch (n) {
            case "javac", "ecj" -> n;
            default -> defaultValue;
        };
    }

    private static OptionalInt readCap(Object value) {
        if (value == null) return OptionalInt.empty();
        if (value instanceof Number n) {
            return OptionalInt.of(n.intValue());
        }
        if (value instanceof String s) {
            try {
                return OptionalInt.of(Integer.parseInt(s.trim()));
            } catch (NumberFormatException e) {
                return OptionalInt.empty();
            }
        }
        if (value instanceof JsonElement el) {
            if (!el.isJsonPrimitive()) return OptionalInt.empty();
            JsonPrimitive primitive = el.getAsJsonPrimitive();
            if (primitive.isNumber()) {
                return OptionalInt.of(primitive.getAsInt());
            }
            if (primitive.isString()) {
                return readCap(primitive.getAsString());
            }
        }
        return OptionalInt.empty();
    }
}
