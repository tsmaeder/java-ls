/**
 * Copyright 2026 by Castle Ridge Software GmbH
 *
 * Author: Thomas Mäder, Castle Ridge Software
 *
 */
package ch.castleridge.javals.indexing.source.ecj;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

import ch.castleridge.javals.indexing.IndexTestUtils;
import ch.castleridge.javals.indexing.index.InMemoryIndex;
import ch.castleridge.javals.indexing.index.Index;
import ch.castleridge.javals.indexing.model.MethodEntry;
import ch.castleridge.javals.indexing.model.TypeEntry;

class EcjSourceIndexerVarargsTest {
    private static final String SOURCE_URI = "index:///source/";

    @Test
    void interfaceVarargsMethodSetsVarargsFlag() {
        Index index = new InMemoryIndex();
        EcjSourceIndexer.index(
                "mem:///ObjectFactory.java",
                SOURCE_URI,
                """
                        package org.gradle.api.model;

                        public interface ObjectFactory {
                            <T> T newInstance(Class<? extends T> type, Object... parameters);
                        }
                        """,
                index);

        TypeEntry entry = IndexTestUtils.get(index, "org/gradle/api/model/ObjectFactory");
        assertNotNull(entry);
        MethodEntry newInstance = Arrays.stream(entry.methods())
                .filter(m -> m.name().equals("newInstance"))
                .findFirst()
                .orElseThrow();
        assertTrue(newInstance.varargs(),
                "interface Object... parameter must set MethodEntry.varargs()");
    }

    @Test
    void annotatedVarargsParameterSetsVarargsFlag() {
        Index index = new InMemoryIndex();
        EcjSourceIndexer.index(
                "mem:///Instantiator.java",
                SOURCE_URI,
                """
                        package org.gradle.internal.reflect;

                        import org.jspecify.annotations.Nullable;

                        public interface Instantiator {
                            <T> T newInstance(Class<? extends T> type, @Nullable Object... parameters);
                        }
                        """,
                index);

        TypeEntry entry = IndexTestUtils.get(index, "org/gradle/internal/reflect/Instantiator");
        assertNotNull(entry);
        MethodEntry newInstance = Arrays.stream(entry.methods())
                .filter(m -> m.name().equals("newInstance"))
                .findFirst()
                .orElseThrow();
        assertTrue(newInstance.varargs(),
                "@Nullable Object... must set MethodEntry.varargs()");
    }

    static boolean gradleObjectFactoryPresent() {
        return Files.isRegularFile(Path.of(
                "..", "gradle", "subprojects", "core-api", "src", "main", "java",
                "org", "gradle", "api", "model", "ObjectFactory.java"));
    }

    @Test
    @EnabledIf("gradleObjectFactoryPresent")
    void realGradleObjectFactorySetsVarargsFlag() throws Exception {
        Path file = Path.of(
                "..", "gradle", "subprojects", "core-api", "src", "main", "java",
                "org", "gradle", "api", "model", "ObjectFactory.java").toAbsolutePath().normalize();
        Index index = new InMemoryIndex();
        EcjSourceIndexer.index(
                file.toUri().toString(),
                SOURCE_URI,
                Files.readString(file),
                index);

        TypeEntry entry = IndexTestUtils.get(index, "org/gradle/api/model/ObjectFactory");
        assertNotNull(entry);
        MethodEntry newInstance = Arrays.stream(entry.methods())
                .filter(m -> m.name().equals("newInstance"))
                .findFirst()
                .orElseThrow();
        assertTrue(newInstance.varargs(),
                "real ObjectFactory.newInstance must be indexed as varargs; methods="
                        + Arrays.toString(entry.methods()));
    }
}
