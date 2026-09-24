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
package ch.castleridge.javals.analysis.javac;

import ch.castleridge.javals.analysis.AnalysisSession;
import ch.castleridge.javals.classpath.ClasspathOrder;
import ch.castleridge.javals.classpath.UriClasspathEntry;

import java.util.List;
import java.util.stream.Collectors;

import org.eclipse.lsp4j.CompletionItem;
import org.eclipse.lsp4j.CompletionItemKind;
import org.eclipse.lsp4j.InsertTextFormat;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.TextEdit;
import org.junit.jupiter.api.Test;

import ch.castleridge.javals.indexing.index.Index;
import ch.castleridge.javals.indexing.index.InMemoryIndex;
import ch.castleridge.javals.indexing.model.ClassFileTypeEntry;
import ch.castleridge.javals.indexing.model.EmptyArrays;
import ch.castleridge.javals.indexing.model.FieldEntry;
import ch.castleridge.javals.indexing.model.MethodEntry;
import ch.castleridge.javals.indexing.model.Type;
import ch.castleridge.javals.indexing.model.TypeRef;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Completions from the shared session against small, hand-built indexes.
 */
class CompletionProposerTest {

    private static final String SOURCE_URI = "index:///test-classpath/";
    private static final String OTHER_SOURCE_URI = "index:///other-classpath/";

    @Test
    void fieldCompletionAfterDotListsFieldWithType() throws Exception {
        Index index = baseIndex();
        String source = """
                package com.example;

                public class Use {
                    void run() {
                        Widget w = new Widget();
                        w.val;
                    }
                }
                """;

        List<CompletionItem> items = complete(source, index, classPathOf(SOURCE_URI), "w.val");

        CompletionItem field = findByLabel(items, "value");
        assertTrue(field != null, () -> "expected 'value' field, got: " + labels(items));
        assertEquals(CompletionItemKind.Field, field.getKind());
        assertEquals("int", field.getDetail());
    }

    @Test
    void methodCompletionAfterDotProducesParenSnippet() throws Exception {
        Index index = baseIndex();
        String source = """
                package com.example;

                public class Use {
                    void run() {
                        Widget w = new Widget();
                        w.get;
                    }
                }
                """;

        List<CompletionItem> items = complete(source, index, classPathOf(SOURCE_URI), "w.get");

        CompletionItem method = findByLabel(items, "getName");
        assertTrue(method != null, () -> "expected 'getName' method, got: " + labels(items));
        assertEquals(CompletionItemKind.Method, method.getKind());
        assertEquals("java.lang.String getName()", method.getDetail());
        assertEquals("getName($0)", method.getInsertText());
        assertEquals(InsertTextFormat.Snippet, method.getInsertTextFormat());
    }

    @Test
    void unqualifiedCompletionIncludesLocalVariableAndEnclosingField() throws Exception {
        Index index = baseIndex();
        String source = """
                package com.example;

                public class Use {
                    private Widget helperField;

                    void run() {
                        Widget helperLocal = new Widget();
                        help;
                    }
                }
                """;

        List<CompletionItem> items = complete(source, index, classPathOf(SOURCE_URI), "        help");

        assertTrue(findByLabel(items, "helperField") != null,
                () -> "expected enclosing field, got: " + labels(items));
        assertTrue(findByLabel(items, "helperLocal") != null,
                () -> "expected local variable, got: " + labels(items));
    }

    @Test
    void staticMemberCompletionViaClassName() throws Exception {
        Index index = baseIndex();
        String source = """
                package com.example;

                public class Use {
                    void run() {
                        Constants.MA;
                    }
                }
                """;

        List<CompletionItem> items = complete(source, index, classPathOf(SOURCE_URI), "Constants.MA");

        CompletionItem field = findByLabel(items, "MAX");
        assertTrue(field != null, () -> "expected static field MAX, got: " + labels(items));
        assertEquals(CompletionItemKind.Field, field.getKind());
    }

    @Test
    void packageMemberCompletionViaFullyQualifiedPrefix() throws Exception {
        // Package-member lookup (as opposed to a direct type reference) goes
        // through the compiler's own module/package visibility machinery,
        // which our other tests' minimal synthetic types (registered with
        // no owning module) don't exercise realistically - so this one test
        // scans the real JDK, same setup as DefinitionElementResolverTest.
        String javaHome = System.getProperty("java.home");
        java.nio.file.Path jdk = java.nio.file.Path.of(javaHome);
        org.junit.jupiter.api.Assumptions.assumeTrue(java.nio.file.Files.exists(jdk), "JDK not present");

        ch.castleridge.javals.indexing.scan.JrtInput jrt = new ch.castleridge.javals.indexing.scan.JrtInput(jdk);
        Index index = new InMemoryIndex();
        List<Throwable> failures = new ch.castleridge.javals.indexing.scan.Scanner().scanAll(List.of(jrt), index);
        assertTrue(failures.isEmpty(), () -> "JRT scan failures: " + failures);

        String source = """
                package com.example;

                public class Use {
                    void run() {
                        java.util.Ma;
                    }
                }
                """;

        List<CompletionItem> items = complete(source, index, ClasspathOrder.UNRESTRICTED, "java.util.Ma");

        assertTrue(findByLabel(items, "Map") != null,
                () -> "expected Map package member, got: " + labels(items));
    }

    @Test
    void unimportedTypeCompletionOffersImportForSourceAndBytecodeTypeCandidates() throws Exception {
        Index index = baseIndex();
        String source = """
                package com.example;

                public class Use {
                    void run() {
                        Zeta;
                    }
                }
                """;

        List<CompletionItem> items = complete(source, index, classPathOf(SOURCE_URI), "        Zeta");

        CompletionItem fromTypeEntry = findByLabel(items, "ZetaHelper");
        assertTrue(fromTypeEntry != null, () -> "expected ZetaHelper, got: " + labels(items));
        assertEquals("com.example.tools.ZetaHelper", fromTypeEntry.getDetail());
        assertImportEdit(fromTypeEntry, "import com.example.tools.ZetaHelper;\n");

        CompletionItem fromBytecodeType = findByLabel(items, "ZetaTool");
        assertTrue(fromBytecodeType != null, () -> "expected ZetaTool, got: " + labels(items));
        assertEquals("com.example.tools.ZetaTool", fromBytecodeType.getDetail());
        assertImportEdit(fromBytecodeType, "import com.example.tools.ZetaTool;\n");
    }

    @Test
    void unimportedTypeCompletionSkipsEditWhenAlreadyImported() throws Exception {
        Index index = baseIndex();
        String source = """
                package com.example;

                import com.example.tools.ZetaHelper;

                public class Use {
                    void run() {
                        Zeta;
                    }
                }
                """;

        List<CompletionItem> items = complete(source, index, classPathOf(SOURCE_URI), "        Zeta");

        CompletionItem item = findByLabel(items, "ZetaHelper");
        assertTrue(item != null, () -> "expected ZetaHelper, got: " + labels(items));
        assertNull(item.getAdditionalTextEdits(),
                () -> "did not expect an import edit once already imported, got: " + item.getAdditionalTextEdits());
    }

    @Test
    void unimportedTypeCompletionRespectsClasspathVisibility() throws Exception {
        Index index = baseIndex();
        index.add(classFileType(OTHER_SOURCE_URI, "com/other/zone/ZetaRestricted"));

        String source = """
                package com.example;

                public class Use {
                    void run() {
                        Zeta;
                    }
                }
                """;

        List<CompletionItem> items = complete(source, index, classPathOf(SOURCE_URI), "        Zeta");

        assertTrue(findByLabel(items, "ZetaHelper") != null,
                () -> "expected in-classpath ZetaHelper, got: " + labels(items));
        assertFalse(items.stream().anyMatch(i -> "ZetaRestricted".equals(i.getLabel())),
                () -> "did not expect out-of-classpath ZetaRestricted, got: " + labels(items));
    }

    // ---- fixtures ----

    private static Index baseIndex() {
        Index index = new InMemoryIndex();
        index.add(objectType());
        index.add(stringType());
        index.add(widgetType());
        index.add(constantsType());
        index.add(classFileType(SOURCE_URI, "com/example/tools/ZetaHelper"));
        index.add(classFileType(SOURCE_URI, "com/example/tools/ZetaTool"));
        return index;
    }

    private static ClassFileTypeEntry objectType() {
        return new ClassFileTypeEntry(
                "index:///java/lang/Object.class",
                SOURCE_URI,
                "java/lang/Object",
                0x0001,
                null,
                EmptyArrays.TYPE,
                EmptyArrays.TYPE_PARAM,
                EmptyArrays.FIELD,
                new MethodEntry[]{ctor("java/lang/Object")},
                EmptyArrays.STRING,
                EmptyArrays.ANNOTATION_REF);
    }

    private static ClassFileTypeEntry stringType() {
        return new ClassFileTypeEntry(
                "index:///java/lang/String.class",
                SOURCE_URI,
                "java/lang/String",
                0x0011 /* ACC_PUBLIC | ACC_FINAL */,
                TypeRef.resolved("java/lang/Object"),
                EmptyArrays.TYPE,
                EmptyArrays.TYPE_PARAM,
                EmptyArrays.FIELD,
                new MethodEntry[]{ctor("java/lang/String")},
                EmptyArrays.STRING,
                EmptyArrays.ANNOTATION_REF);
    }

    private static ClassFileTypeEntry widgetType() {
        return new ClassFileTypeEntry(
                "index:///com/example/Widget.class",
                SOURCE_URI,
                "com/example/Widget",
                0x0001,
                TypeRef.resolved("java/lang/Object"),
                EmptyArrays.TYPE,
                EmptyArrays.TYPE_PARAM,
                new FieldEntry[]{
                        new FieldEntry(
                                0x0001 /* ACC_PUBLIC */,
                                "value",
                                Type.Primitive.INT,
                                EmptyArrays.ANNOTATION_REF)},
                new MethodEntry[]{
                        ctor("com/example/Widget"),
                        new MethodEntry(
                                0x0001 /* ACC_PUBLIC */,
                                "getName",
                                TypeRef.resolved("java/lang/String"),
                                EmptyArrays.TYPE,
                                EmptyArrays.TYPE,
                                EmptyArrays.ANNOTATION_REF)},
                EmptyArrays.STRING,
                EmptyArrays.ANNOTATION_REF);
    }

    private static ClassFileTypeEntry constantsType() {
        return new ClassFileTypeEntry(
                "index:///com/example/Constants.class",
                SOURCE_URI,
                "com/example/Constants",
                0x0001,
                TypeRef.resolved("java/lang/Object"),
                EmptyArrays.TYPE,
                EmptyArrays.TYPE_PARAM,
                new FieldEntry[]{
                        new FieldEntry(
                                0x0019 /* ACC_PUBLIC | ACC_STATIC | ACC_FINAL */,
                                "MAX",
                                Type.Primitive.INT,
                                EmptyArrays.ANNOTATION_REF)},
                new MethodEntry[]{ctor("com/example/Constants")},
                EmptyArrays.STRING,
                EmptyArrays.ANNOTATION_REF);
    }

    private static ClassFileTypeEntry classFileType(String sourceUri, String jvmOwnerName) {
        return new ClassFileTypeEntry(
                "index:///" + jvmOwnerName + ".class",
                sourceUri,
                jvmOwnerName,
                0x0001,
                TypeRef.resolved("java/lang/Object"),
                EmptyArrays.TYPE,
                EmptyArrays.TYPE_PARAM,
                EmptyArrays.FIELD,
                new MethodEntry[]{ctor(jvmOwnerName)},
                EmptyArrays.STRING,
                EmptyArrays.ANNOTATION_REF);
    }

    private static MethodEntry ctor(String jvmOwnerName) {
        return new MethodEntry(
                0x0001 /* ACC_PUBLIC */,
                "<init>",
                Type.Primitive.VOID,
                EmptyArrays.TYPE,
                EmptyArrays.TYPE,
                EmptyArrays.ANNOTATION_REF);
    }

    // ---- helpers ----

    private static List<CompletionItem> complete(String source, Index index, ClasspathOrder classpath, String marker) {
        AnalysisSession session = new JavacWorkspaceCompiler().analyze(
                "mem:///com/example/Use.java", source, index, ClasspathOrder.UNRESTRICTED);
        return session.complete(source, positionAfter(source, marker), index, classpath);
    }

    private static Position positionAfter(String source, String marker) {
        int offset = source.indexOf(marker);
        if (offset < 0) throw new AssertionError("marker not found: " + marker);
        offset += marker.length();
        int line = 0;
        int column = 0;
        for (int i = 0; i < offset; i++) {
            if (source.charAt(i) == '\n') {
                line++;
                column = 0;
            } else if (source.charAt(i) != '\r') {
                column++;
            }
        }
        return new Position(line, column);
    }

    private static ClasspathOrder classPathOf(String... uris) {
        return new ClasspathOrder(
                List.of(uris).stream().map(UriClasspathEntry::of).collect(Collectors.toList()), false);
    }

    private static CompletionItem findByLabel(List<CompletionItem> items, String label) {
        return items.stream().filter(i -> label.equals(i.getLabel())).findFirst().orElse(null);
    }

    private static List<String> labels(List<CompletionItem> items) {
        return items.stream().map(CompletionItem::getLabel).collect(Collectors.toList());
    }

    private static void assertImportEdit(CompletionItem item, String expectedText) {
        List<TextEdit> edits = item.getAdditionalTextEdits();
        assertTrue(edits != null && edits.size() == 1,
                () -> "expected exactly one additional text edit, got: " + edits);
        assertEquals(expectedText, edits.get(0).getNewText());
    }
}
