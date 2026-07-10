package ch.castleridge.javals.javac;

import java.net.URI;
import java.util.List;
import java.util.stream.Collectors;

import org.eclipse.lsp4j.CompletionItem;
import org.eclipse.lsp4j.CompletionItemKind;
import org.eclipse.lsp4j.InsertTextFormat;
import org.eclipse.lsp4j.TextEdit;
import org.junit.jupiter.api.Test;

import com.sun.source.tree.CompilationUnitTree;

import ch.castleridge.javals.indexing.index.Index;
import ch.castleridge.javals.indexing.model.ClassFileEntry;
import ch.castleridge.javals.indexing.model.ClassFileTypeEntry;
import ch.castleridge.javals.indexing.model.FieldEntry;
import ch.castleridge.javals.indexing.model.MethodEntry;
import ch.castleridge.javals.indexing.model.Type;
import ch.castleridge.javals.indexing.model.TypeRef;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises {@link CompletionProposer} against small, hand-built
 * {@link Index}es (mirroring {@code IndexCompileTest}'s pattern) rather
 * than a real JRT scan, so tests stay fast and deterministic.
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
                        w.val
                    }
                }
                """;
        List<String> lines = List.of(source.split("\n", -1));

        // No trailing ';' - the buffer is exactly as it looks mid-typing,
        // which is the state completion is actually triggered in. javac's
        // parser collapses this whole (semicolon-less) statement into a
        // bare ErroneousTree with no recoverable qualifier, so this also
        // exercises CompletionProposer's speculativeReparse fallback.
        WorkspaceCompiler.Result compiled = compile(source, index);
        long offset = offsetAfter(compiled.cu(), lines, "w.val");

        List<CompletionItem> items = CompletionProposer.propose(
                compiled, source, offset, index, classPathOf(SOURCE_URI));

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
                        w.get
                    }
                }
                """;
        List<String> lines = List.of(source.split("\n", -1));

        WorkspaceCompiler.Result compiled = compile(source, index);
        long offset = offsetAfter(compiled.cu(), lines, "w.get");

        List<CompletionItem> items = CompletionProposer.propose(
                compiled, source, offset, index, classPathOf(SOURCE_URI));

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
                        help
                    }
                }
                """;
        List<String> lines = List.of(source.split("\n", -1));

        WorkspaceCompiler.Result compiled = compile(source, index);
        long offset = offsetAfter(compiled.cu(), lines, "        help");

        List<CompletionItem> items = CompletionProposer.propose(
                compiled, source, offset, index, classPathOf(SOURCE_URI));

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
                        Constants.MA
                    }
                }
                """;
        List<String> lines = List.of(source.split("\n", -1));

        WorkspaceCompiler.Result compiled = compile(source, index);
        long offset = offsetAfter(compiled.cu(), lines, "Constants.MA");

        List<CompletionItem> items = CompletionProposer.propose(
                compiled, source, offset, index, classPathOf(SOURCE_URI));

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
        Index index = new Index();
        List<Throwable> failures = new ch.castleridge.javals.indexing.scan.Scanner().scanAll(List.of(jrt), index);
        assertTrue(failures.isEmpty(), () -> "JRT scan failures: " + failures);

        String source = """
                package com.example;

                public class Use {
                    void run() {
                        java.util.Ma
                    }
                }
                """;
        List<String> lines = List.of(source.split("\n", -1));

        WorkspaceCompiler.Result compiled = compile(source, index);
        long offset = offsetAfter(compiled.cu(), lines, "java.util.Ma");

        List<CompletionItem> items = CompletionProposer.propose(
                compiled, source, offset, index, ClasspathOrder.UNRESTRICTED);

        assertTrue(findByLabel(items, "Map") != null,
                () -> "expected Map package member, got: " + labels(items));
    }

    @Test
    void unimportedTypeCompletionOffersImportForBothTypeAndClassFileCandidates() throws Exception {
        Index index = baseIndex();
        String source = """
                package com.example;

                public class Use {
                    void run() {
                        Zeta
                    }
                }
                """;
        List<String> lines = List.of(source.split("\n", -1));

        WorkspaceCompiler.Result compiled = compile(source, index);
        long offset = offsetAfter(compiled.cu(), lines, "        Zeta");

        List<CompletionItem> items = CompletionProposer.propose(
                compiled, source, offset, index, classPathOf(SOURCE_URI));

        CompletionItem fromTypeEntry = findByLabel(items, "ZetaHelper");
        assertTrue(fromTypeEntry != null, () -> "expected ZetaHelper, got: " + labels(items));
        assertEquals("com.example.tools.ZetaHelper", fromTypeEntry.getDetail());
        assertImportEdit(fromTypeEntry, "import com.example.tools.ZetaHelper;\n");

        CompletionItem fromClassFileEntry = findByLabel(items, "ZetaTool");
        assertTrue(fromClassFileEntry != null, () -> "expected ZetaTool, got: " + labels(items));
        assertEquals("com.example.tools.ZetaTool", fromClassFileEntry.getDetail());
        assertImportEdit(fromClassFileEntry, "import com.example.tools.ZetaTool;\n");
    }

    @Test
    void unimportedTypeCompletionSkipsEditWhenAlreadyImported() throws Exception {
        Index index = baseIndex();
        String source = """
                package com.example;

                import com.example.tools.ZetaHelper;

                public class Use {
                    void run() {
                        Zeta
                    }
                }
                """;
        List<String> lines = List.of(source.split("\n", -1));

        WorkspaceCompiler.Result compiled = compile(source, index);
        long offset = offsetAfter(compiled.cu(), lines, "        Zeta");

        List<CompletionItem> items = CompletionProposer.propose(
                compiled, source, offset, index, classPathOf(SOURCE_URI));

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
                        Zeta
                    }
                }
                """;
        List<String> lines = List.of(source.split("\n", -1));

        // Compile against the unrestricted classpath (so the reference below
        // still compiles), but ask for completions with a ClasspathOrder that
        // only admits SOURCE_URI - ZetaRestricted must not be suggested.
        WorkspaceCompiler.Result compiled = compile(source, index);
        long offset = offsetAfter(compiled.cu(), lines, "        Zeta");

        List<CompletionItem> items = CompletionProposer.propose(
                compiled, source, offset, index, classPathOf(SOURCE_URI));

        assertTrue(findByLabel(items, "ZetaHelper") != null,
                () -> "expected in-classpath ZetaHelper, got: " + labels(items));
        assertFalse(items.stream().anyMatch(i -> "ZetaRestricted".equals(i.getLabel())),
                () -> "did not expect out-of-classpath ZetaRestricted, got: " + labels(items));
    }

    // ---- fixtures ----

    private static Index baseIndex() {
        Index index = new Index();
        index.add(objectType());
        index.add(stringType());
        index.add(widgetType());
        index.add(constantsType());
        index.add(classFileType(SOURCE_URI, "com/example/tools/ZetaHelper"));
        index.addClassFile(new ClassFileEntry(
                "index:///com/example/tools/ZetaTool.class", SOURCE_URI, "com/example/tools/ZetaTool"));
        return index;
    }

    private static ClassFileTypeEntry objectType() {
        return new ClassFileTypeEntry(
                "index:///java/lang/Object.class",
                SOURCE_URI,
                "java/lang/Object",
                0x0001,
                null,
                List.of(),
                List.of(),
                List.of(),
                List.of(ctor("java/lang/Object")),
                List.of(),
                List.of(),
                List.of());
    }

    private static ClassFileTypeEntry stringType() {
        return new ClassFileTypeEntry(
                "index:///java/lang/String.class",
                SOURCE_URI,
                "java/lang/String",
                0x0011 /* ACC_PUBLIC | ACC_FINAL */,
                TypeRef.resolved("java/lang/Object"),
                List.of(),
                List.of(),
                List.of(),
                List.of(ctor("java/lang/String")),
                List.of(),
                List.of(),
                List.of());
    }

    private static ClassFileTypeEntry widgetType() {
        return new ClassFileTypeEntry(
                "index:///com/example/Widget.class",
                SOURCE_URI,
                "com/example/Widget",
                0x0001,
                TypeRef.resolved("java/lang/Object"),
                List.of(),
                List.of(),
                List.of(new FieldEntry(
                        "index:///com/example/Widget.class#value",
                        "com/example/Widget",
                        0x0001 /* ACC_PUBLIC */,
                        "value",
                        Type.Primitive.INT,
                        List.of())),
                List.of(ctor("com/example/Widget"), new MethodEntry(
                        "index:///com/example/Widget.class#getName",
                        "com/example/Widget",
                        0x0001 /* ACC_PUBLIC */,
                        "getName",
                        TypeRef.resolved("java/lang/String"),
                        List.of(),
                        List.of(),
                        List.of())),
                List.of(),
                List.of(),
                List.of());
    }

    private static ClassFileTypeEntry constantsType() {
        return new ClassFileTypeEntry(
                "index:///com/example/Constants.class",
                SOURCE_URI,
                "com/example/Constants",
                0x0001,
                TypeRef.resolved("java/lang/Object"),
                List.of(),
                List.of(),
                List.of(new FieldEntry(
                        "index:///com/example/Constants.class#MAX",
                        "com/example/Constants",
                        0x0019 /* ACC_PUBLIC | ACC_STATIC | ACC_FINAL */,
                        "MAX",
                        Type.Primitive.INT,
                        List.of())),
                List.of(ctor("com/example/Constants")),
                List.of(),
                List.of(),
                List.of());
    }

    private static ClassFileTypeEntry classFileType(String sourceUri, String jvmOwnerName) {
        return new ClassFileTypeEntry(
                "index:///" + jvmOwnerName + ".class",
                sourceUri,
                jvmOwnerName,
                0x0001,
                TypeRef.resolved("java/lang/Object"),
                List.of(),
                List.of(),
                List.of(),
                List.of(ctor(jvmOwnerName)),
                List.of(),
                List.of(),
                List.of());
    }

    private static MethodEntry ctor(String jvmOwnerName) {
        return new MethodEntry(
                "index:///" + jvmOwnerName + "#<init>",
                jvmOwnerName,
                0x0001 /* ACC_PUBLIC */,
                "<init>",
                Type.Primitive.VOID,
                List.of(),
                List.of(),
                List.of());
    }

    // ---- helpers ----

    private static WorkspaceCompiler.Result compile(String source, Index index) {
        return WorkspaceCompiler.compile(
                URI.create("mem:///com/example/Use.java"), source, index, ClasspathOrder.UNRESTRICTED);
    }

    private static ClasspathOrder classPathOf(String... uris) {
        return new ClasspathOrder(
                List.of(uris).stream().map(UriClasspathEntry::of).collect(Collectors.toList()), false);
    }

    /** Offset right after the last character of {@code marker}'s first occurrence in {@code lines}. */
    private static long offsetAfter(CompilationUnitTree cu, List<String> lines, String marker) {
        for (int i = 0; i < lines.size(); i++) {
            int col = lines.get(i).indexOf(marker);
            if (col >= 0) {
                return cu.getLineMap().getPosition(i + 1, col + marker.length() + 1);
            }
        }
        throw new AssertionError("marker not found: " + marker);
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
