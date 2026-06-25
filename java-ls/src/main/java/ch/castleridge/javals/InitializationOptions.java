package ch.castleridge.javals;

import java.util.Map;
import java.util.OptionalInt;

import org.eclipse.lsp4j.InitializeParams;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

/**
 * Reads java-ls settings from LSP {@link InitializeParams#getInitializationOptions()}.
 */
final class InitializationOptions {

    private InitializationOptions() {}

    static OptionalInt referencesCandidateCap(InitializeParams params) {
        if (params == null) return OptionalInt.empty();
        Object options = params.getInitializationOptions();
        if (options instanceof Map<?, ?> map) {
            return readCap(map.get("referencesCandidateCap"));
        }
        if (options instanceof JsonObject json) {
            return readCap(json.get("referencesCandidateCap"));
        }
        return OptionalInt.empty();
    }

    /**
     * When {@code true} (default), {@code .class} files are fully parsed into
     * {@link ch.castleridge.javals.indexing.model.TypeEntry} records. When
     * {@code false}, only minimal {@link ch.castleridge.javals.indexing.model.ClassFileEntry}
     * records are stored and the file manager serves real jar/jrt bytes.
     */
    static boolean indexClassFileContents(InitializeParams params) {
        if (params == null) return true;
        Object options = params.getInitializationOptions();
        if (options instanceof Map<?, ?> map) {
            return readBoolean(map.get("indexClassFileContents"), true);
        }
        if (options instanceof JsonObject json) {
            return readBoolean(json.get("indexClassFileContents"), true);
        }
        return true;
    }

    /**
     * When {@code true} (default {@code false}), workspace directory sources
     * are indexed as {@link ch.castleridge.javals.indexing.model.PrunedSourceEntry}
     * API stubs instead of per-type {@link ch.castleridge.javals.indexing.model.TypeEntry}
     * records.
     */
    static boolean prunedSourceIndexing(InitializeParams params) {
        if (params == null) return false;
        Object options = params.getInitializationOptions();
        if (options instanceof Map<?, ?> map) {
            return readBoolean(map.get("prunedSourceIndexing"), false);
        }
        if (options instanceof JsonObject json) {
            return readBoolean(json.get("prunedSourceIndexing"), false);
        }
        return false;
    }

    private static boolean readBoolean(Object value, boolean defaultValue) {
        if (value == null) return defaultValue;
        if (value instanceof Boolean b) return b;
        if (value instanceof Number n) return n.intValue() != 0;
        if (value instanceof String s) {
            String trimmed = s.trim();
            if ("true".equalsIgnoreCase(trimmed) || "1".equals(trimmed)) return true;
            if ("false".equalsIgnoreCase(trimmed) || "0".equals(trimmed)) return false;
            return defaultValue;
        }
        if (value instanceof JsonElement el) {
            if (!el.isJsonPrimitive()) return defaultValue;
            JsonPrimitive primitive = el.getAsJsonPrimitive();
            if (primitive.isBoolean()) return primitive.getAsBoolean();
            if (primitive.isNumber()) return primitive.getAsInt() != 0;
            if (primitive.isString()) return readBoolean(primitive.getAsString(), defaultValue);
        }
        return defaultValue;
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
