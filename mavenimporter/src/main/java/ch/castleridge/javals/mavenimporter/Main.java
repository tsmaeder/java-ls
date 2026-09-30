/**
 * Copyright 2026 by Castle Ridge Software GmbH
 */
package ch.castleridge.javals.mavenimporter;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import ch.castleridge.javals.mavenimporter.maven.MavenSession;
import ch.castleridge.javals.mavenimporter.maven.ReactorImporter;
import ch.castleridge.javals.mavenimporter.mbt.MbtAggregator;
import ch.castleridge.javals.mavenimporter.mbt.MbtJsonWriter;

/**
 * Entry point for the Maven project importer.
 *
 * <pre>
 * java -jar mavenimporter.jar &lt;directory&gt;
 * </pre>
 */
public final class Main {

    private Main() {}

    public static void main(String[] args) {
        int code = run(args);
        if (code != 0) {
            System.exit(code);
        }
    }

    static int run(String[] args) {
        if (args.length != 1) {
            System.err.println("Usage: java -jar mavenimporter.jar <directory>");
            return 1;
        }

        Path directory = Path.of(args[0]).toAbsolutePath().normalize();
        if (!Files.isDirectory(directory)) {
            System.err.println("Not a readable directory: " + directory);
            return 1;
        }

        try {
            Set<Path> todo = PomScanner.scan(directory);
            if (todo.isEmpty()) {
                System.err.println("No pom.xml files found under " + directory);
                return 1;
            }

            try (MavenSession session = new MavenSession()) {
                ReactorImporter importer = new ReactorImporter(session);
                MbtAggregator aggregator = ReactorLoop.run(todo, importer);
                Path written = MbtJsonWriter.write(directory, aggregator.toDocument());
                System.out.println("Wrote " + written);
            }
            return 0;
        } catch (Exception e) {
            System.err.println("Maven import failed: " + e.getMessage());
            e.printStackTrace(System.err);
            return 1;
        }
    }
}
