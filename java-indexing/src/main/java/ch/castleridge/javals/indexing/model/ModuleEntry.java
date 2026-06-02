package ch.castleridge.javals.indexing.model;

import java.util.List;

/**
 * A single indexed module declaration, mirroring the JVMS Module attribute
 * (JEP 261 / JLS §7.7).
 *
 * <p>Modules live outside the {@link TypeEntry} space because they aren't
 * types - a {@code module-info.class} carries an ACC_MODULE pseudo-class
 * whose only useful payload is the {@code Module} attribute. Storing them
 * via {@link TypeEntry} would force every consumer to special-case the
 * pseudo-class; {@link ModuleEntry} lets the file manager and the
 * symbol-side reader treat modules as a first-class lookup keyed by
 * module name.
 *
 * <p>{@link #flags()} is the raw {@code Module} access flag set
 * ({@code ACC_OPEN}, {@code ACC_SYNTHETIC}, {@code ACC_MANDATED}).
 */
public record ModuleEntry(
        String resourceUri,
        String sourceUri,
        String name,
        String version,
        int flags,
        List<Requires> requires,
        List<Exports> exports,
        List<Opens> opens,
        List<String> uses,
        List<Provides> provides,
        List<String> packages,
        String mainClass) {

    public ModuleEntry {
        if (name == null || name.isEmpty()) {
            throw new IllegalArgumentException("module name must be non-empty");
        }
        requires = requires == null ? List.of() : List.copyOf(requires);
        exports = exports == null ? List.of() : List.copyOf(exports);
        opens = opens == null ? List.of() : List.copyOf(opens);
        uses = uses == null ? List.of() : List.copyOf(uses);
        provides = provides == null ? List.of() : List.copyOf(provides);
        packages = packages == null ? List.of() : List.copyOf(packages);
    }

    public EntryKind kind() {
        return EntryKind.MODULE;
    }

    /** A {@code requires} directive: {@code requires [transitive] [static] <name>}. */
    public record Requires(String moduleName, int flags, String version) {
        public Requires {
            if (moduleName == null || moduleName.isEmpty()) {
                throw new IllegalArgumentException("requires.moduleName must be non-empty");
            }
        }
    }

    /** An {@code exports} directive: {@code exports <package> [to <module>...]}. */
    public record Exports(String packageJvm, List<String> toModules, int flags) {
        public Exports {
            if (packageJvm == null) {
                throw new IllegalArgumentException("exports.packageJvm must be non-null");
            }
            toModules = toModules == null ? List.of() : List.copyOf(toModules);
        }
    }

    /** An {@code opens} directive: {@code opens <package> [to <module>...]}. */
    public record Opens(String packageJvm, List<String> toModules, int flags) {
        public Opens {
            if (packageJvm == null) {
                throw new IllegalArgumentException("opens.packageJvm must be non-null");
            }
            toModules = toModules == null ? List.of() : List.copyOf(toModules);
        }
    }

    /** A {@code provides} directive: {@code provides <service> with <impl>...}. */
    public record Provides(String serviceJvm, List<String> implJvms) {
        public Provides {
            if (serviceJvm == null || serviceJvm.isEmpty()) {
                throw new IllegalArgumentException("provides.serviceJvm must be non-empty");
            }
            implJvms = implJvms == null ? List.of() : List.copyOf(implJvms);
        }
    }
}
