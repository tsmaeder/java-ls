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
