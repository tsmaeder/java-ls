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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.eclipse.lsp4j.DidChangeConfigurationParams;
import org.eclipse.lsp4j.InitializeParams;
import org.junit.jupiter.api.Test;

import com.google.gson.JsonObject;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
        assertFalse(textService.referenceSearchScope().inJars());
        assertFalse(textService.referenceSearchScope().inJdk());
    }

    @Test
    void referencesScopeDefaultsToWorkspaceOnly() {
        InitializationOptions.References defaults = InitializationOptions.references(new InitializeParams());
        assertFalse(defaults.inJars());
        assertFalse(defaults.inJdk());
    }

    @Test
    void referencesScopeReadsNestedFlags() {
        Map<String, Object> options = new HashMap<>();
        options.put("references", Map.of("inJars", true, "inJdk", "false"));
        InitializeParams params = new InitializeParams();
        params.setInitializationOptions(options);
        InitializationOptions.References references = InitializationOptions.references(params);
        assertTrue(references.inJars());
        assertFalse(references.inJdk());

        JsonObject json = new JsonObject();
        JsonObject referencesJson = new JsonObject();
        referencesJson.addProperty("inJars", false);
        referencesJson.addProperty("inJdk", true);
        json.add("references", referencesJson);
        params.setInitializationOptions(json);
        references = InitializationOptions.references(params);
        assertFalse(references.inJars());
        assertTrue(references.inJdk());
    }

    @Test
    void parseReferencesIsSharedSubtreeParser() {
        InitializationOptions.References fromMap =
                InitializationOptions.parseReferences(Map.of("inJars", true, "inJdk", false));
        assertTrue(fromMap.inJars());
        assertFalse(fromMap.inJdk());

        JsonObject json = new JsonObject();
        json.addProperty("inJars", false);
        json.addProperty("inJdk", true);
        InitializationOptions.References fromJson = InitializationOptions.parseReferences(json);
        assertFalse(fromJson.inJars());
        assertTrue(fromJson.inJdk());

        assertEquals(InitializationOptions.References.DEFAULT, InitializationOptions.parseReferences(null));
    }

    @Test
    void referencesFromOptionsEmptyWhenKeyAbsent() {
        assertTrue(InitializationOptions.referencesFromOptions(Map.of()).isEmpty());
        assertTrue(InitializationOptions.referencesFromOptions(new JsonObject()).isEmpty());

        Optional<InitializationOptions.References> present =
                InitializationOptions.referencesFromOptions(
                        Map.of("references", Map.of("inJars", true, "inJdk", true)));
        assertTrue(present.isPresent());
        assertTrue(present.get().inJars());
        assertTrue(present.get().inJdk());
    }

    @Test
    void optionsRootFromDidChangeSettingsUnwrapsJavals() {
        Map<String, Object> nested = Map.of("references", Map.of("inJars", true, "inJdk", false));
        Object root = InitializationOptions.optionsRootFromDidChangeSettings(Map.of("javals", nested));
        assertSame(nested, root);

        Map<String, Object> flat = Map.of("references", Map.of("inJars", false, "inJdk", true));
        assertSame(flat, InitializationOptions.optionsRootFromDidChangeSettings(flat));
    }

    @Test
    void initializeAppliesReferencesScope() throws Exception {
        JavaLanguageServer server = new JavaLanguageServer();
        InitializeParams params = new InitializeParams();
        params.setInitializationOptions(Map.of("references", Map.of("inJars", true, "inJdk", true)));

        server.initialize(params).get();

        JavaTextDocumentService textService = (JavaTextDocumentService) server.getTextDocumentService();
        assertTrue(textService.referenceSearchScope().inJars());
        assertTrue(textService.referenceSearchScope().inJdk());
    }

    @Test
    void didChangeConfigurationUpdatesReferencesScopeViaSharedApply() throws Exception {
        JavaLanguageServer server = new JavaLanguageServer();
        server.initialize(new InitializeParams()).get();

        JavaTextDocumentService textService = (JavaTextDocumentService) server.getTextDocumentService();
        assertFalse(textService.referenceSearchScope().inJars());
        assertFalse(textService.referenceSearchScope().inJdk());

        DidChangeConfigurationParams change = new DidChangeConfigurationParams();
        change.setSettings(Map.of(
                "javals",
                Map.of(
                        "references", Map.of("inJars", true, "inJdk", true),
                        "referencesCandidateCap", 7)));
        server.getWorkspaceService().didChangeConfiguration(change);

        assertTrue(textService.referenceSearchScope().inJars());
        assertTrue(textService.referenceSearchScope().inJdk());
        assertEquals(7, textService.referencesCandidateCap());
    }

    @Test
    void didChangeConfigurationSkipsAbsentReferencesKey() throws Exception {
        JavaLanguageServer server = new JavaLanguageServer();
        InitializeParams params = new InitializeParams();
        params.setInitializationOptions(Map.of("references", Map.of("inJars", true, "inJdk", true)));
        server.initialize(params).get();

        JavaTextDocumentService textService = (JavaTextDocumentService) server.getTextDocumentService();
        assertTrue(textService.referenceSearchScope().inJars());

        DidChangeConfigurationParams change = new DidChangeConfigurationParams();
        change.setSettings(Map.of("javals", Map.of("referencesCandidateCap", 3)));
        server.getWorkspaceService().didChangeConfiguration(change);

        assertTrue(textService.referenceSearchScope().inJars());
        assertTrue(textService.referenceSearchScope().inJdk());
        assertEquals(3, textService.referencesCandidateCap());
    }

    @Test
    void backendDefaultsAndReadsNestedConfig() {
        InitializationOptions.Backend defaults = InitializationOptions.backend(new InitializeParams());
        assertEquals("javac", defaults.sourceIndexer());
        assertEquals("asm", defaults.classIndexer());
        assertEquals("javac", defaults.compiler());

        Map<String, Object> options = new HashMap<>();
        options.put("backend", Map.of(
                "sourceIndexer", "TURBINE",
                "classIndexer", "turbine",
                "compiler", "ECJ"));
        InitializeParams params = new InitializeParams();
        params.setInitializationOptions(options);
        InitializationOptions.Backend backend = InitializationOptions.backend(params);
        assertEquals("turbine", backend.sourceIndexer());
        assertEquals("turbine", backend.classIndexer());
        assertEquals("ecj", backend.compiler());

        JsonObject json = new JsonObject();
        JsonObject backendJson = new JsonObject();
        backendJson.addProperty("sourceIndexer", "javac");
        backendJson.addProperty("classIndexer", "asm");
        backendJson.addProperty("compiler", "ecj");
        json.add("backend", backendJson);
        params.setInitializationOptions(json);
        backend = InitializationOptions.backend(params);
        assertEquals("javac", backend.sourceIndexer());
        assertEquals("asm", backend.classIndexer());
        assertEquals("ecj", backend.compiler());
    }

    @Test
    void importerScriptsReadsMapAndJsonObject() {
        assertTrue(InitializationOptions.importerScripts(Map.of()).isEmpty());

        Map<String, Object> options = Map.of(
                "importers", Map.of("maven", "/custom/mavenimporter.jar", "gradle", "gradle-import.sh"));
        Map<String, String> scripts = InitializationOptions.importerScripts(options);
        assertEquals("/custom/mavenimporter.jar", scripts.get("maven"));
        assertEquals("gradle-import.sh", scripts.get("gradle"));

        JsonObject json = new JsonObject();
        JsonObject importers = new JsonObject();
        importers.addProperty("maven", "C:\\\\tools\\\\m.jar");
        json.add("importers", importers);
        InitializeParams params = new InitializeParams();
        params.setInitializationOptions(json);
        assertEquals("C:\\\\tools\\\\m.jar", InitializationOptions.importerScripts(params).get("maven"));
    }

    @Test
    void mavenGeneratedSourceRulesJsonSerializesNonEmptyArray() {
        assertTrue(InitializationOptions.mavenGeneratedSourceRulesJson(Map.of()).isEmpty());
        assertTrue(InitializationOptions.mavenGeneratedSourceRulesJson(
                        Map.of("maven", Map.of("generatedSourceRules", List.of())))
                .isEmpty());

        Map<String, Object> options = Map.of(
                "maven",
                Map.of(
                        "generatedSourceRules",
                        List.of(Map.of(
                                "pluginKey", "org.codehaus.modello:modello-maven-plugin",
                                "enabled", false))));
        Optional<String> json = InitializationOptions.mavenGeneratedSourceRulesJson(options);
        assertTrue(json.isPresent());
        assertTrue(json.get().contains("org.codehaus.modello:modello-maven-plugin"));
        assertTrue(json.get().contains("enabled"));

        JsonObject root = new JsonObject();
        JsonObject maven = new JsonObject();
        com.google.gson.JsonArray rules = new com.google.gson.JsonArray();
        JsonObject entry = new JsonObject();
        entry.addProperty("pluginKey", "org.antlr:antlr4-maven-plugin");
        entry.addProperty("scope", "main");
        entry.addProperty("configPath", "outputDirectory");
        rules.add(entry);
        maven.add("generatedSourceRules", rules);
        root.add("maven", maven);
        Optional<String> fromJson = InitializationOptions.mavenGeneratedSourceRulesJson(root);
        assertTrue(fromJson.isPresent());
        assertTrue(fromJson.get().contains("org.antlr:antlr4-maven-plugin"));
    }

    private static OptionalInt capFrom(Map<String, Object> options) {
        return InitializationOptions.referencesCandidateCap(options);
    }
}
