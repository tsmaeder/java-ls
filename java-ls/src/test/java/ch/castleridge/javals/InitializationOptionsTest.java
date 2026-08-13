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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.eclipse.lsp4j.InitializeParams;
import org.junit.jupiter.api.Test;

import com.google.gson.JsonObject;

import java.util.HashMap;
import java.util.Map;
import java.util.OptionalInt;

class InitializationOptionsTest {

    @Test
    void referencesCandidateCapReadsNumericAndStringValues() {
        assertEquals(OptionalInt.of(1), capFrom(Map.of("referencesCandidateCap", 1)));
        assertEquals(OptionalInt.of(42), capFrom(Map.of("referencesCandidateCap", 42.0)));
        assertEquals(OptionalInt.of(3), capFrom(Map.of("referencesCandidateCap", "3")));
        assertTrue(capFrom(Map.of()).isEmpty());
        assertTrue(capFrom(Map.of("referencesCandidateCap", "nope")).isEmpty());
    }

    @Test
    void referencesCandidateCapReadsJsonObjectFromLspRoundTrip() {
        JsonObject json = new JsonObject();
        json.addProperty("referencesCandidateCap", 1);
        InitializeParams params = new InitializeParams();
        params.setInitializationOptions(json);
        assertEquals(OptionalInt.of(1), InitializationOptions.referencesCandidateCap(params));
    }

    @Test
    void initializeAppliesReferencesCandidateCap() throws Exception {
        JavaLanguageServer server = new JavaLanguageServer();
        InitializeParams params = new InitializeParams();
        Map<String, Object> options = new HashMap<>();
        options.put("referencesCandidateCap", 1);
        params.setInitializationOptions(options);

        server.initialize(params).get();

        JavaTextDocumentService textService = (JavaTextDocumentService) server.getTextDocumentService();
        assertEquals(1, textService.referencesCandidateCap());
    }

    @Test
    void backendDefaultsToJavacAndReadsNestedConfig() {
        assertEquals("javac", InitializationOptions.backend(new InitializeParams()).indexer());
        assertEquals("javac", InitializationOptions.backend(new InitializeParams()).compiler());

        Map<String, Object> options = new HashMap<>();
        options.put("backend", Map.of("indexer", "ecj", "compiler", "ECJ"));
        InitializeParams params = new InitializeParams();
        params.setInitializationOptions(options);
        InitializationOptions.Backend backend = InitializationOptions.backend(params);
        assertEquals("ecj", backend.indexer());
        assertEquals("ecj", backend.compiler());

        JsonObject json = new JsonObject();
        JsonObject backendJson = new JsonObject();
        backendJson.addProperty("indexer", "javac");
        backendJson.addProperty("compiler", "ecj");
        json.add("backend", backendJson);
        params.setInitializationOptions(json);
        backend = InitializationOptions.backend(params);
        assertEquals("javac", backend.indexer());
        assertEquals("ecj", backend.compiler());
    }

    private static OptionalInt capFrom(Map<String, Object> options) {
        InitializeParams params = new InitializeParams();
        params.setInitializationOptions(options);
        return InitializationOptions.referencesCandidateCap(params);
    }
}
