package ch.castleridge.javals.indexing.scan;

import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;

import ch.castleridge.javals.indexing.index.Index;
import ch.castleridge.javals.indexing.model.MethodEntry;
import ch.castleridge.javals.indexing.model.TypeEntry;
import ch.castleridge.javals.indexing.model.TypeRef;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JrtScanTest {

    @Test
    void scanJavaBaseFindsWellKnownTypes() {
        Index index = new Index();
        Scanner scanner = new Scanner();
        List<Throwable> failures = scanner.scanAll(List.of(new JrtInput(Path.of(System.getProperty("java.home")))), index);
        assertTrue(failures.isEmpty(), () -> "unexpected failures: " + failures);

        TypeEntry object = index.get("java/lang/Object");
        assertNotNull(object, "java.base should index java/lang/Object");
        assertEquals("java/lang/Object", object.jvmName());

        TypeEntry list = index.get("java/util/List");
        assertNotNull(list, "java.base should index java/util/List");
        assertTrue(list.methods().stream().anyMatch(m -> m.name().equals("size")),
                "java/util/List should carry a size() method entry");
        assertTrue(list.methods().stream().anyMatch(m -> m.name().equals("of")),
                "java/util/List should carry one of its static of() factories");
    }

    @Test
    void formalTypeParametersAreExtractedFromClassSignatures() {
        Index index = new Index();
        Scanner scanner = new Scanner();
        scanner.scanAll(List.of(new JrtInput(Path.of(System.getProperty("java.home")))), index);

        TypeEntry list = index.get("java/util/List");
        assertNotNull(list);
        assertEquals(1, list.typeParams().size(), "List<E> has one type parameter");
        assertEquals("E", list.typeParams().get(0).name());

        TypeEntry map = index.get("java/util/Map");
        assertNotNull(map);
        assertEquals(2, map.typeParams().size(), "Map<K,V> has two type parameters");
        assertEquals("K", map.typeParams().get(0).name());
        assertEquals("V", map.typeParams().get(1).name());

        TypeEntry function = index.get("java/util/function/Function");
        assertNotNull(function);
        assertEquals(2, function.typeParams().size(), "Function<T,R> has two type parameters");
        assertEquals("T", function.typeParams().get(0).name());
        assertEquals("R", function.typeParams().get(1).name());

        TypeEntry object = index.get("java/lang/Object");
        assertNotNull(object);
        assertTrue(object.typeParams().isEmpty(), "Object is not generic");
    }

    @Test
    void packageInfoAndModuleInfoAreFiltered() {
        Index index = new Index();
        Scanner scanner = new Scanner();
        List<Throwable> failures = scanner.scanAll(List.of(new JrtInput(Path.of(System.getProperty("java.home")))), index);
        assertTrue(failures.isEmpty(), () -> "unexpected failures: " + failures);

        for (TypeEntry e : index.all()) {
            String jvm = e.jvmName();
            assertTrue(!jvm.endsWith("/module-info") && !jvm.equals("module-info"),
                    "module-info should not be indexed: " + jvm);
            assertTrue(!jvm.endsWith("/package-info") && !jvm.equals("package-info"),
                    "package-info should not be indexed: " + jvm);
        }
    }

    @Test
    void completableFutureImplementsParameterizedCompletionStage() {
        Index index = new Index();
        Scanner scanner = new Scanner();
        List<Throwable> failures = scanner.scanAll(List.of(new JrtInput(Path.of(System.getProperty("java.home")))), index);
        assertTrue(failures.isEmpty(), () -> "unexpected failures: " + failures);

        TypeEntry cf = index.get("java/util/concurrent/CompletableFuture");
        assertNotNull(cf, "java.base should index CompletableFuture");
        assertEquals(1, cf.typeParams().size());
        String typeParam = cf.typeParams().get(0).name();

        boolean hasParameterizedCompletionStage = cf.interfaceRefs().stream().anyMatch(ref -> {
            if (!(ref instanceof TypeRef.Parameterized p)) return false;
            if (!(p.raw() instanceof TypeRef.Resolved r)) return false;
            if (!r.jvmBinaryName().equals("java/util/concurrent/CompletionStage")) return false;
            if (p.typeArgs().size() != 1) return false;
            return p.typeArgs().get(0) instanceof TypeRef.TypeVariable tv
                    && tv.name().equals(typeParam);
        });
        assertTrue(hasParameterizedCompletionStage,
                "CompletableFuture should implement CompletionStage<" + typeParam + ">");
    }

    @Test
    void listPackageReturnsPackageMembers() {
        Index index = new Index();
        Scanner scanner = new Scanner();
        scanner.scanAll(List.of(new JrtInput(Path.of(System.getProperty("java.home")))), index);

        List<TypeEntry> javaUtil = index.listPackage("java/util", false);
        assertTrue(javaUtil.stream().anyMatch(e -> e.jvmName().equals("java/util/ArrayList")),
                "java/util should contain ArrayList");
        assertTrue(javaUtil.stream().anyMatch(e -> e.jvmName().equals("java/util/HashMap")),
                "java/util should contain HashMap");

        // Bytecode-sourced methods must carry fully resolved TypeRefs.
        TypeEntry arrayList = index.get("java/util/ArrayList");
        assertNotNull(arrayList);
        MethodEntry size = arrayList.methods().stream()
                .filter(m -> m.name().equals("size"))
                .findFirst().orElseThrow();
        assertEquals(TypeRef.Primitive.INT, size.returnType());
        assertTrue(size.paramTypes().isEmpty(), "size() takes no parameters");
    }
}
