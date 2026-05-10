package ch.castleridge.javals.indexing.scan;

import java.util.List;

import org.junit.jupiter.api.Test;

import ch.castleridge.javals.indexing.index.Index;
import ch.castleridge.javals.indexing.model.MethodEntry;
import ch.castleridge.javals.indexing.model.TypeEntry;
import ch.castleridge.javals.indexing.model.TypeRef;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JrtScanTest {

    @Test
    void scanJavaBaseFindsWellKnownTypes() {
        Index index = new Index();
        Scanner scanner = new Scanner();
        List<Throwable> failures = scanner.scanAll(List.of(new JrtInput("java.base")), index);
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
    void packageInfoAndModuleInfoAreFiltered() {
        Index index = new Index();
        Scanner scanner = new Scanner();
        List<Throwable> failures = scanner.scanAll(List.of(new JrtInput("java.base")), index);
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
    void listPackageReturnsPackageMembers() {
        Index index = new Index();
        Scanner scanner = new Scanner();
        scanner.scanAll(List.of(new JrtInput("java.base")), index);

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
