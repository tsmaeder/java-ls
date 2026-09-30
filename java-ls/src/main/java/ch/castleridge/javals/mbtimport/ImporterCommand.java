/**
 * Copyright 2026 by Castle Ridge Software GmbH
 */
package ch.castleridge.javals.mbtimport;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Builds the process argv for an importer preference string.
 *
 * <p>If the preference looks like a jar ({@code .jar} suffix), runs it with the
 * current JVM's {@code java} executable. Otherwise treats the preference as a
 * shell command line and appends {@code <workspace> --output <fragment>}.
 */
public final class ImporterCommand {

    private ImporterCommand() {}

    /**
     * @param script preference string (jar path or shell command line); must be non-blank
     */
    public static List<String> build(String script, Path workspace, Path output) {
        String trimmed = stripQuotes(script.trim());
        Path ws = workspace.toAbsolutePath().normalize();
        Path out = output.toAbsolutePath().normalize();
        if (isJar(trimmed)) {
            List<String> command = new ArrayList<>(6);
            command.add(javaExecutable());
            command.add("-jar");
            command.add(trimmed);
            command.add(ws.toString());
            command.add("--output");
            command.add(out.toString());
            return command;
        }
        return shellCommand(trimmed, ws, out);
    }

    static boolean isJar(String script) {
        String s = stripQuotes(script.trim());
        return s.toLowerCase(Locale.ROOT).endsWith(".jar");
    }

    static String stripQuotes(String s) {
        if (s.length() >= 2) {
            char first = s.charAt(0);
            char last = s.charAt(s.length() - 1);
            if ((first == '"' && last == '"') || (first == '\'' && last == '\'')) {
                return s.substring(1, s.length() - 1);
            }
        }
        return s;
    }

    static String javaExecutable() {
        String home = System.getProperty("java.home");
        boolean windows = isWindows();
        return Path.of(home, "bin", windows ? "java.exe" : "java").toString();
    }

    private static List<String> shellCommand(String script, Path workspace, Path output) {
        if (isWindows()) {
            String line = script + " " + quoteForCmd(workspace.toString())
                    + " --output " + quoteForCmd(output.toString());
            return List.of("cmd.exe", "/S", "/C", line);
        }
        // preference is the command line; "$@" receives workspace / --output / path
        return List.of(
                "/bin/sh",
                "-c",
                script + " \"$@\"",
                "importer",
                workspace.toString(),
                "--output",
                output.toString());
    }

    private static String quoteForCmd(String value) {
        return '"' + value.replace("\"", "\\\"") + '"';
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }
}
