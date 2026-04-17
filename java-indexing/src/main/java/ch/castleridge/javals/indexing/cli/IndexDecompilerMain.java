package ch.castleridge.javals.indexing.cli;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import ch.castleridge.javals.indexing.index.Index;
import ch.castleridge.javals.indexing.scan.DirInput;
import ch.castleridge.javals.indexing.scan.InputSource;
import ch.castleridge.javals.indexing.scan.JarInput;
import ch.castleridge.javals.indexing.scan.JrtInput;
import ch.castleridge.javals.indexing.scan.Scanner;

/**
 * Minimal CLI driver around {@link Scanner}. Accepted tokens:
 *
 * <pre>
 *   --dir &lt;path&gt;       index a directory recursively
 *   --jar &lt;path&gt;       index a jar file
 *   --jrt [module]    index jrt:/ (every module if no name is given)
 * </pre>
 *
 * After the scan finishes, prints the number of indexed types, any walker
 * errors, and the wall-clock time.
 */
public final class IndexDecompilerMain {

    public static void main(String[] args) {
        List<InputSource> sources = parseArgs(args);
        if (sources.isEmpty()) {
            System.err.println("Usage: --dir <path> | --jar <path> | --jrt [module]");
            System.exit(2);
        }

        Index index = new Index();
        Scanner scanner = new Scanner();
        long t0 = System.nanoTime();
        List<Throwable> failures = scanner.scanAll(sources, index);
        long elapsedMs = (System.nanoTime() - t0) / 1_000_000L;

        System.out.println("Indexed types: " + index.size());
        System.out.println("Elapsed: " + elapsedMs + " ms");
        if (!failures.isEmpty()) {
            System.out.println("Failures: " + failures.size());
            for (Throwable t : failures) {
                System.out.println("  " + t.getClass().getSimpleName() + ": " + t.getMessage());
            }
        }
    }

    private static List<InputSource> parseArgs(String[] args) {
        List<InputSource> sources = new ArrayList<>();
        int i = 0;
        while (i < args.length) {
            String a = args[i];
            switch (a) {
                case "--dir" -> {
                    if (i + 1 >= args.length) fail("--dir requires a path");
                    sources.add(new DirInput(Path.of(args[++i])));
                }
                case "--jar" -> {
                    if (i + 1 >= args.length) fail("--jar requires a path");
                    sources.add(new JarInput(Path.of(args[++i])));
                }
                case "--jrt" -> {
                    String module = JrtInput.ALL;
                    if (i + 1 < args.length && !args[i + 1].startsWith("--")) {
                        module = args[++i];
                    }
                    sources.add(new JrtInput(module));
                }
                default -> fail("Unknown argument: " + a);
            }
            i++;
        }
        return sources;
    }

    private static void fail(String msg) {
        System.err.println(msg);
        System.exit(2);
    }
}
