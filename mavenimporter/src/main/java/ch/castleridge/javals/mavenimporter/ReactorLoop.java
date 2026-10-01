/**
 * Copyright 2026 by Castle Ridge Software GmbH
 *
 * Author: Thomas Mäder, Castle Ridge Software
 *
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
    public interface ReactorHandler {
        /**
         * Handles one reactor rooted at {@code pom} and returns every pom.xml that belongs to that
         * reactor (so they can be removed from the todo set).
         */
        Set<Path> handle(Path pom) throws Exception;
    }

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

    public static void drain(Set<Path> todo, ReactorHandler handler) throws Exception {
        while (!todo.isEmpty()) {
            Path next = PomScanner.takeShortest(todo);
            if (next == null) {
                break;
            }
            Set<Path> reactorPoms = handler.handle(next);
            for (Path reactorPom : reactorPoms) {
                todo.remove(reactorPom.toAbsolutePath().normalize());
            }
            // Always remove the chosen root even if the handler forgot it.
            todo.remove(next.toAbsolutePath().normalize());
        }
    }

    public static MbtAggregator run(Set<Path> todo, ReactorImporter importer) throws Exception {
        MbtAggregator aggregator = new MbtAggregator();
        drain(todo, pom -> {
            ImportResult result = importer.importReactor(pom);
            aggregator.add(result.document());
            return result.reactorPoms();
        });
        return aggregator;
    }
}
