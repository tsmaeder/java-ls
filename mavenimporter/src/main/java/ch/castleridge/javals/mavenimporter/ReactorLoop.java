/**
 * Copyright 2026 by Castle Ridge Software GmbH
 */
package ch.castleridge.javals.mavenimporter;

import java.nio.file.Path;
import java.util.Set;

import ch.castleridge.javals.mavenimporter.mbt.MbtAggregator;
import ch.castleridge.javals.mavenimporter.mbt.MbtDocument;

/**
 * Drains a todo set of poms reactor-by-reactor, shortest path first.
 */
public final class ReactorLoop {

    @FunctionalInterface
    public interface ReactorImporter {
        /**
         * Imports one reactor rooted at {@code pom} and returns the partial mbt document plus
         * every pom.xml that belongs to that reactor (so they can be removed from the todo set).
         */
        ImportResult importReactor(Path pom) throws Exception;
    }

    public record ImportResult(MbtDocument document, Set<Path> reactorPoms) {}

    private ReactorLoop() {}

    public static MbtAggregator run(Set<Path> todo, ReactorImporter importer) throws Exception {
        MbtAggregator aggregator = new MbtAggregator();
        while (!todo.isEmpty()) {
            Path next = PomScanner.takeShortest(todo);
            if (next == null) {
                break;
            }
            ImportResult result = importer.importReactor(next);
            aggregator.add(result.document());
            for (Path reactorPom : result.reactorPoms()) {
                todo.remove(reactorPom.toAbsolutePath().normalize());
            }
            // Always remove the chosen root even if the importer forgot it.
            todo.remove(next.toAbsolutePath().normalize());
        }
        return aggregator;
    }
}
