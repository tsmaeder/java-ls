/**
 * Copyright 2026 by Castle Ridge Software GmbH
 *
 * Author: Thomas Mäder, Castle Ridge Software
 *
 */
package ch.castleridge.javals.mavenimporter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import ch.castleridge.javals.mavenimporter.mbt.MbtAggregator;
import ch.castleridge.javals.mavenimporter.mbt.MbtDocument;
import ch.castleridge.javals.mavenimporter.mbt.MbtNamespace;

class ReactorLoopTest {

    @Test
    void drainsTodoUsingReactorPoms() throws Exception {
        Path root = Path.of("/ws/pom.xml").toAbsolutePath().normalize();
        Path child = Path.of("/ws/mod/pom.xml").toAbsolutePath().normalize();
        Path orphan = Path.of("/ws/other/pom.xml").toAbsolutePath().normalize();

        Set<Path> todo = new LinkedHashSet<>();
        todo.add(root);
        todo.add(child);
        todo.add(orphan);

        AtomicInteger imports = new AtomicInteger();
        MbtAggregator aggregator = ReactorLoop.run(todo, pom -> {
            imports.incrementAndGet();
            MbtDocument doc = new MbtDocument();
            MbtNamespace ns = new MbtNamespace();
            ns.sources.add(pom.toString());
            doc.namespaces.put("ns-" + imports.get(), ns);
            if (pom.equals(root)) {
                return new ReactorLoop.ImportResult(doc, Set.of(root, child));
            }
            return new ReactorLoop.ImportResult(doc, Set.of(pom));
        });

        assertEquals(2, imports.get());
        assertTrue(todo.isEmpty());
        assertEquals(2, aggregator.toDocument().namespaces.size());
        assertTrue(aggregator.toDocument().namespaces.containsKey("ns-1"));
        assertTrue(aggregator.toDocument().namespaces.containsKey("ns-2"));
    }
}
