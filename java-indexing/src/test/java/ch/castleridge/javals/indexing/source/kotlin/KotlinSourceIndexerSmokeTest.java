/**
 * Copyright 2026 by Castle Ridge Software GmbH
 *
 * Author: Thomas Mäder, Castle Ridge Software
 *
 */
package ch.castleridge.javals.indexing.source.kotlin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

import ch.castleridge.javals.indexing.bloom.BloomEntry;
import ch.castleridge.javals.indexing.index.InMemoryIndex;
import ch.castleridge.javals.indexing.index.Index;
import ch.castleridge.javals.indexing.model.FieldEntry;
import ch.castleridge.javals.indexing.model.MethodEntry;
import ch.castleridge.javals.indexing.model.SourceTypeEntry;
import ch.castleridge.javals.indexing.model.TypeDeclKind;
import ch.castleridge.javals.indexing.model.TypeEntry;

class KotlinSourceIndexerSmokeTest {

    private static final String SOURCE_URI = "mem:///";

    @Test
    void indexesClassInterfaceObjectAndNested() {
        Index index = new InMemoryIndex();
        KotlinSourceIndexer.index(
                "demo/Types.kt",
                SOURCE_URI,
                """
                package demo

                class Greeter(val name: String) {
                    fun greet(): String = "hi $name"
                    class Nested
                    companion object {
                        fun create(): Greeter = Greeter("x")
                    }
                }

                interface Marker

                object Singleton {
                    fun ping() {}
                }
                """,
                index);

        SourceTypeEntry greeter = source(index, "demo/Greeter");
        assertEquals(TypeDeclKind.CLASS, greeter.declKind());
        assertTrue(fieldNames(greeter).contains("name"));
        assertTrue(methodNames(greeter).contains("greet"));
        assertTrue(methodNames(greeter).contains("<init>"));
        assertTrue(Arrays.asList(greeter.innerTypeJvmNames()).contains("demo/Greeter$Nested"));
        assertTrue(Arrays.asList(greeter.innerTypeJvmNames()).contains("demo/Greeter$Companion"));

        assertNotNull(source(index, "demo/Greeter$Nested"));
        assertNotNull(source(index, "demo/Greeter$Companion"));

        SourceTypeEntry marker = source(index, "demo/Marker");
        assertEquals(TypeDeclKind.INTERFACE, marker.declKind());

        SourceTypeEntry singleton = source(index, "demo/Singleton");
        assertTrue(fieldNames(singleton).contains("INSTANCE"));
        assertTrue(methodNames(singleton).contains("ping"));
    }

    @Test
    void indexesFileFacadeForTopLevelFunctions() {
        Index index = new InMemoryIndex();
        KotlinSourceIndexer.index(
                "demo/Helpers.kt",
                SOURCE_URI,
                """
                package demo

                fun helper(x: Int): Int = x + 1

                val answer: Int = 42
                """,
                index);

        SourceTypeEntry facade = source(index, "demo/HelpersKt");
        assertEquals(TypeDeclKind.CLASS, facade.declKind());
        assertTrue(methodNames(facade).contains("helper"));
        assertTrue(fieldNames(facade).contains("answer"));
    }

    @Test
    void registersIdentifierBloomFilter() {
        Index index = new InMemoryIndex();
        KotlinSourceIndexer.index(
                "demo/Refs.kt",
                SOURCE_URI,
                """
                package demo

                class Refs {
                    fun uses(other: Greeter) {
                        other.greet()
                    }
                }
                """,
                index);

        BloomEntry bloom = index.bloomFilters().stream()
                .filter(b -> "demo/Refs.kt".equals(b.resourcePath()))
                .findFirst()
                .orElse(null);
        assertNotNull(bloom);
        assertTrue(bloom.filter().mightContain("Greeter"));
        assertTrue(bloom.filter().mightContain("greet"));
        assertTrue(bloom.filter().mightContain("Refs"));
        assertFalse(bloom.filter().mightContain("DefinitelyNotPresent_xyz"));
    }

    private static SourceTypeEntry source(Index index, String jvmName) {
        TypeEntry entry = index.getAll(jvmName).stream().findFirst().orElse(null);
        assertNotNull(entry, "missing type " + jvmName);
        assertTrue(entry instanceof SourceTypeEntry, jvmName + " should be SourceTypeEntry");
        return (SourceTypeEntry) entry;
    }

    private static Set<String> fieldNames(TypeEntry entry) {
        return Arrays.stream(entry.fields()).map(FieldEntry::name).collect(Collectors.toSet());
    }

    private static Set<String> methodNames(TypeEntry entry) {
        return Arrays.stream(entry.methods()).map(MethodEntry::name).collect(Collectors.toSet());
    }
}
