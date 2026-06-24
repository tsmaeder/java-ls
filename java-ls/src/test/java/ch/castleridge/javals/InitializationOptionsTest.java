package ch.castleridge.javals;

import java.util.HashMap;
import java.util.Map;
import java.util.OptionalInt;

import org.eclipse.lsp4j.InitializeParams;
import org.junit.jupiter.api.Test;

import com.google.gson.JsonObject;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
    void indexClassFileContentsDefaultsTrueAndParsesBoolean() {
        assertTrue(indexClassFileContentsFrom(Map.of()));
        assertTrue(indexClassFileContentsFrom(Map.of("indexClassFileContents", true)));
        assertFalse(indexClassFileContentsFrom(Map.of("indexClassFileContents", false)));
        assertFalse(indexClassFileContentsFrom(Map.of("indexClassFileContents", "false")));
        assertTrue(indexClassFileContentsFrom(Map.of("indexClassFileContents", "true")));
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

    private static OptionalInt capFrom(Map<String, Object> options) {
        InitializeParams params = new InitializeParams();
        params.setInitializationOptions(options);
        return InitializationOptions.referencesCandidateCap(params);
    }

    private static boolean indexClassFileContentsFrom(Map<String, Object> options) {
        InitializeParams params = new InitializeParams();
        params.setInitializationOptions(options);
        return InitializationOptions.indexClassFileContents(params);
    }
}
