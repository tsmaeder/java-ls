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
 * java -jar mavenimporter.jar &lt;directory&gt; [--output &lt;file&gt;] [--force]
 * </pre>
 */
public final class Main {

    private static final String DEFAULT_OUTPUT = ".metals/mbt.json.maven";

    private Main() {}

    public static void main(String[] args) {
        int code = run(args);
        if (code != 0) {
            System.exit(code);
        }
    }

    static int run(String[] args) {
        ParsedArgs parsed = parseArgs(args);
        if (parsed == null) {
            return 1;
        }

        Path directory = Path.of(parsed.directory).toAbsolutePath().normalize();
        if (!Files.isDirectory(directory)) {
            System.err.println("Not a readable directory: " + directory);
            return 1;
        }

        Path output = resolveOutput(directory, parsed.output);

        try {
            Set<Path> todo = PomScanner.scan(directory);
            if (todo.isEmpty()) {
                if (Files.isRegularFile(output)) {
                    Files.delete(output);
                    System.out.println("No pom.xml files found under " + directory + "; removed stale " + output);
                } else {
                    System.out.println("No pom.xml files found under " + directory);
                }
                return 0;
            }

            if (!parsed.force && OutputUpToDate.isUpToDate(output, todo)) {
                System.out.println("Up to date: " + output);
                return 0;
            }

            try (MavenSession session = new MavenSession()) {
                ReactorImporter importer = new ReactorImporter(session);
                MbtAggregator aggregator = ReactorLoop.run(todo, importer);
                Path written = MbtJsonWriter.write(output, aggregator.toDocument());
                System.out.println("Wrote " + written);
            }
            return 0;
        } catch (Exception e) {
            System.err.println("Maven import failed: " + e.getMessage());
            e.printStackTrace(System.err);
            return 1;
        }
    }

    /**
     * Resolves the output path. Relative paths are resolved against {@code directory}.
     * When {@code outputArg} is null, defaults to {@code <directory>/.metals/mbt.json.maven}.
     */
    static Path resolveOutput(Path directory, String outputArg) {
        Path output = Path.of(outputArg != null ? outputArg : DEFAULT_OUTPUT);
        if (!output.isAbsolute()) {
            output = directory.resolve(output);
        }
        return output.toAbsolutePath().normalize();
    }

    private static ParsedArgs parseArgs(String[] args) {
        String directory = null;
        String output = null;
        boolean force = false;

        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if ("--output".equals(arg) || "-o".equals(arg)) {
                if (i + 1 >= args.length) {
                    System.err.println("Missing value for " + arg);
                    printUsage();
                    return null;
                }
                output = args[++i];
            } else if ("--force".equals(arg) || "-f".equals(arg)) {
                force = true;
            } else if (arg.startsWith("-")) {
                System.err.println("Unknown option: " + arg);
                printUsage();
                return null;
            } else if (directory == null) {
                directory = arg;
            } else {
                System.err.println("Unexpected argument: " + arg);
                printUsage();
                return null;
            }
        }

        if (directory == null) {
            printUsage();
            return null;
        }

        return new ParsedArgs(directory, output, force);
    }

    private static void printUsage() {
        System.err.println("Usage: java -jar mavenimporter.jar <directory> [--output <file>] [--force]");
    }

    private record ParsedArgs(String directory, String output, boolean force) {}
}
