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
package ch.castleridge.javals.indexing.source.javac;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

import org.junit.jupiter.api.Test;

import ch.castleridge.javals.indexing.bytecode.ClassFileIndexer;
import ch.castleridge.javals.indexing.index.Index;
import ch.castleridge.javals.indexing.index.InMemoryIndex;
import ch.castleridge.javals.indexing.model.MethodEntry;
import ch.castleridge.javals.indexing.model.Type;
import ch.castleridge.javals.indexing.model.TypeEntry;
import ch.castleridge.javals.indexing.model.TypeRef;
import ch.castleridge.javals.indexing.scan.DirInput;
import ch.castleridge.javals.indexing.scan.Scanner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VertxOverrideIndexingTest {

    private static Path vertxCoreSrc() {
        Path p = Path.of("../../test-projects/vert.x/vertx-core/src/main/java").toAbsolutePath().normalize();
        org.junit.jupiter.api.Assumptions.assumeTrue(Files.isDirectory(p));
        return p;
    }

    private static Path vertxCoreClasses() {
        Path p = Path.of("../../test-projects/vert.x/vertx-core/target/classes").toAbsolutePath().normalize();
        org.junit.jupiter.api.Assumptions.assumeTrue(Files.isDirectory(p));
        return p;
    }

    @Test
    void sourceIndexedVerticleBaseStartReturnsFutureWildcard() throws Exception {
        Index index = new InMemoryIndex();
        new Scanner().scanAll(java.util.List.of(new DirInput(vertxCoreSrc())), index);
        TypeEntry vb = ch.castleridge.javals.indexing.IndexTestUtils.get(index, "io/vertx/core/VerticleBase");
        assertNotNull(vb);
        MethodEntry start = method(vb, "start");
        assertInstanceOf(Type.Parameterized.class, start.returnType());
        Type.Parameterized future = (Type.Parameterized) start.returnType();
        assertInstanceOf(TypeRef.Unresolved.class, future.raw());
        assertEquals("Future", ((TypeRef.Unresolved) future.raw()).simpleName());
        assertEquals(1, future.typeArgs().length);
        assertInstanceOf(Type.Wildcard.class, future.typeArgs()[0]);
    }

    @Test
    void sourceIndexedConnectionBaseMetricsReturnsRawNetworkMetrics() throws Exception {
        Index index = new InMemoryIndex();
        new Scanner().scanAll(java.util.List.of(new DirInput(vertxCoreSrc())), index);
        TypeEntry cb = ch.castleridge.javals.indexing.IndexTestUtils.get(index, "io/vertx/core/net/impl/ConnectionBase");
        assertNotNull(cb);
        MethodEntry metrics = method(cb, "metrics");
        assertInstanceOf(TypeRef.Unresolved.class, metrics.returnType());
        assertEquals("NetworkMetrics", ((TypeRef.Unresolved) metrics.returnType()).simpleName());
    }

    @Test
    void sourceIndexedEventBusImplNextHandlerUsesRawHandlerHolderTypeArg() throws Exception {
        Index index = new InMemoryIndex();
        new Scanner().scanAll(java.util.List.of(new DirInput(vertxCoreSrc())), index);
        TypeEntry eb = ch.castleridge.javals.indexing.IndexTestUtils.get(index, "io/vertx/core/eventbus/impl/EventBusImpl");
        assertNotNull(eb);
        MethodEntry next = method(eb, "nextHandler");
        assertEquals(2, next.paramTypes().length);
        Type.Parameterized seq = assertInstanceOf(Type.Parameterized.class, next.paramTypes()[0]);
        assertEquals(1, seq.typeArgs().length);
        Type arg = seq.typeArgs()[0];
        assertTrue(arg instanceof TypeRef.Unresolved || arg instanceof TypeRef.Resolved,
                "HandlerHolder type arg should be a class ref, not a type variable; got " + arg);
        if (arg instanceof TypeRef.Unresolved u) {
            assertEquals("HandlerHolder", u.simpleName());
        }
    }

    @Test
    void bytecodeIndexedEventBusImplNextHandlerUsesRawHandlerHolderTypeArg() throws Exception {
        Path classFile = vertxCoreClasses().resolve("io/vertx/core/eventbus/impl/EventBusImpl.class");
        Index index = new InMemoryIndex();
        ClassFileIndexer.index(classFile.toUri().toString(), "index:///classes/", Files.readAllBytes(classFile), index);
        TypeEntry eb = ch.castleridge.javals.indexing.IndexTestUtils.get(index, "io/vertx/core/eventbus/impl/EventBusImpl");
        assertNotNull(eb);
        MethodEntry next = method(eb, "nextHandler");
        Type.Parameterized seq = assertInstanceOf(Type.Parameterized.class, next.paramTypes()[0]);
        Type arg = seq.typeArgs()[0];
        assertInstanceOf(TypeRef.Resolved.class, arg);
        assertEquals("io/vertx/core/eventbus/impl/HandlerHolder",
                ((TypeRef.Resolved) arg).jvmBinaryName());
    }

    private static MethodEntry method(TypeEntry owner, String name) {
        return Arrays.stream(owner.methods())
                .filter(m -> m.name().equals(name))
                .findFirst()
                .orElseThrow();
    }
}
