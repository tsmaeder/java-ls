/**
 * Copyright 2026 by Castle Ridge Software GmbH
 *
 * Author: Thomas Mäder, Castle Ridge Software
 *
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
 * shell command line and appends {@code <workspace> --output <fragment>} (and
 * optionally {@code --generated-source-rules <file>}).
 */
public final class ImporterCommand {

    private ImporterCommand() {}

    /**
     * @param script preference string (jar path or shell command line); must be non-blank
     */
    public static List<String> build(String script, Path workspace, Path output) {
        return build(script, workspace, output, null);
    }

    /**
     * @param script preference string (jar path or shell command line); must be non-blank
     * @param generatedSourceRules optional JSON rules file; when non-null, appends
     *        {@code --generated-source-rules <path>}
     */
    public static List<String> build(String script, Path workspace, Path output, Path generatedSourceRules) {
        String trimmed = stripQuotes(script.trim());
        Path ws = workspace.toAbsolutePath().normalize();
        Path out = output.toAbsolutePath().normalize();
        Path rules = generatedSourceRules == null
                ? null
                : generatedSourceRules.toAbsolutePath().normalize();
        if (isJar(trimmed)) {
            List<String> command = new ArrayList<>(rules == null ? 6 : 8);
            command.add(javaExecutable());
            command.add("-jar");
            command.add(trimmed);
            command.add(ws.toString());
            command.add("--output");
            command.add(out.toString());
            appendGeneratedSourceRules(command, rules);
            return command;
        }
        return shellCommand(trimmed, ws, out, rules);
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

    private static List<String> shellCommand(String script, Path workspace, Path output, Path rules) {
        if (isWindows()) {
            StringBuilder line = new StringBuilder(script)
                    .append(' ')
                    .append(quoteForCmd(workspace.toString()))
                    .append(" --output ")
                    .append(quoteForCmd(output.toString()));
            if (rules != null) {
                line.append(" --generated-source-rules ").append(quoteForCmd(rules.toString()));
            }
            return List.of("cmd.exe", "/S", "/C", line.toString());
        }
        // preference is the command line; "$@" receives workspace / --output / path [/ rules]
        List<String> command = new ArrayList<>();
        command.add("/bin/sh");
        command.add("-c");
        command.add(script + " \"$@\"");
        command.add("importer");
        command.add(workspace.toString());
        command.add("--output");
        command.add(output.toString());
        appendGeneratedSourceRules(command, rules);
        return command;
    }

    private static void appendGeneratedSourceRules(List<String> command, Path rules) {
        if (rules == null) {
            return;
        }
        command.add("--generated-source-rules");
        command.add(rules.toString());
    }

    private static String quoteForCmd(String value) {
        return '"' + value.replace("\"", "\\\"") + '"';
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }
}
