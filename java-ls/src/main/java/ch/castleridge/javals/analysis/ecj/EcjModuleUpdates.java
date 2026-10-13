/**
 * Copyright 2026 by Castle Ridge Software GmbH
 *
 * Author: Thomas Mäder, Castle Ridge Software
 *
 */
package ch.castleridge.javals.analysis.ecj;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.StringTokenizer;

import org.eclipse.jdt.internal.compiler.env.IUpdatableModule.AddExports;
import org.eclipse.jdt.internal.compiler.env.IUpdatableModule.AddReads;
import org.eclipse.jdt.internal.compiler.env.IUpdatableModule.UpdateKind;
import org.eclipse.jdt.internal.compiler.env.IUpdatableModule.UpdatesByKind;

/**
 * Parses {@code --add-exports} / {@code --add-reads} tokens from mbt
 * {@code javacOptions} into the per-module update map ECJ applies via
 * {@link org.eclipse.jdt.internal.compiler.env.IModuleAwareNameEnvironment#applyModuleUpdates}.
 *
 * <p>Value formats match javac / ECJ batch:
 * {@code module/package=target[,target…]} and {@code module=other}.
 * Malformed entries are skipped.
 */
final class EcjModuleUpdates {
    private EcjModuleUpdates() {}

    /**
     * @return module name → updates; never null (empty when nothing applicable)
     */
    static Map<String, UpdatesByKind> parse(List<String> javacOptions) {
        if (javacOptions == null || javacOptions.isEmpty()) {
            return Map.of();
        }
        Map<String, UpdatesByKind> updates = new HashMap<>();
        for (int i = 0; i < javacOptions.size(); i++) {
            String token = javacOptions.get(i);
            if (token == null || token.isBlank()) {
                continue;
            }
            String value;
            if (token.startsWith("--add-exports=")) {
                value = token.substring("--add-exports=".length());
            } else if (token.equals("--add-exports")) {
                if (i + 1 >= javacOptions.size()) {
                    continue;
                }
                value = javacOptions.get(++i);
            } else if (token.startsWith("--add-reads=")) {
                value = token.substring("--add-reads=".length());
                addReads(updates, value);
                continue;
            } else if (token.equals("--add-reads")) {
                if (i + 1 >= javacOptions.size()) {
                    continue;
                }
                addReads(updates, javacOptions.get(++i));
                continue;
            } else {
                continue;
            }
            addExports(updates, value);
        }
        return updates.isEmpty() ? Map.of() : Map.copyOf(updates);
    }

    private static void addExports(Map<String, UpdatesByKind> updates, String option) {
        if (option == null || option.isBlank()) {
            return;
        }
        // module/package=target[,target…] — same tokenization as ECJ ModuleFinder
        StringTokenizer tokenizer = new StringTokenizer(option, "/");
        if (!tokenizer.hasMoreTokens()) {
            return;
        }
        String source = tokenizer.nextToken("/");
        if (!tokenizer.hasMoreTokens()) {
            return;
        }
        String pack = tokenizer.nextToken("/=");
        if (pack == null || pack.isBlank()) {
            return;
        }
        ArrayList<String> targets = new ArrayList<>();
        while (tokenizer.hasMoreTokens()) {
            String target = tokenizer.nextToken("=,");
            if (target != null && !target.isBlank()) {
                targets.add(target);
            }
        }
        if (targets.isEmpty()) {
            return;
        }
        char[][] targetChars = new char[targets.size()][];
        for (int i = 0; i < targets.size(); i++) {
            targetChars[i] = targets.get(i).toCharArray();
        }
        forModule(updates, source).getList(UpdateKind.PACKAGE, true)
                .add(new AddExports(pack.toCharArray(), targetChars));
    }

    private static void addReads(Map<String, UpdatesByKind> updates, String option) {
        if (option == null || option.isBlank()) {
            return;
        }
        // module=other
        StringTokenizer tokenizer = new StringTokenizer(option, "=");
        if (!tokenizer.hasMoreTokens()) {
            return;
        }
        String source = tokenizer.nextToken();
        if (!tokenizer.hasMoreTokens()) {
            return;
        }
        String target = tokenizer.nextToken();
        if (source == null || source.isBlank() || target == null || target.isBlank()) {
            return;
        }
        forModule(updates, source).getList(UpdateKind.MODULE, true)
                .add(new AddReads(target.toCharArray()));
    }

    private static UpdatesByKind forModule(Map<String, UpdatesByKind> updates, String moduleName) {
        return updates.computeIfAbsent(moduleName, k -> new UpdatesByKind());
    }
}
