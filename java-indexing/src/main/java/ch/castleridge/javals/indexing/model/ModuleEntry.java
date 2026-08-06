package ch.castleridge.javals.indexing.model;

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
 *
 * <p>{@link #resourcePath()} is compact relative to {@link #sourceUri()} when
 * possible; prefer {@link #resourceUri()} for the full location.
 */
public record ModuleEntry(
        String resourcePath,
        String sourceUri,
        String name,
        String version,
        int flags,
        Requires[] requires,
        Exports[] exports,
        Opens[] opens,
        String[] uses,
        Provides[] provides,
        String[] packages,
        String mainClass) {

    public ModuleEntry {
        if (name == null || name.isEmpty()) {
            throw new IllegalArgumentException("module name must be non-empty");
        }
        resourcePath = ResourceUris.compact(resourcePath, sourceUri);
        requires = EmptyArrays.orEmpty(requires, EmptyArrays.REQUIRES);
        exports = EmptyArrays.orEmpty(exports, EmptyArrays.EXPORTS);
        opens = EmptyArrays.orEmpty(opens, EmptyArrays.OPENS);
        uses = EmptyArrays.orEmpty(uses, EmptyArrays.STRING);
        provides = EmptyArrays.orEmpty(provides, EmptyArrays.PROVIDES);
        packages = EmptyArrays.orEmpty(packages, EmptyArrays.STRING);
    }

    public String resourceUri() {
        return ResourceUris.resolve(sourceUri, resourcePath);
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
    public record Exports(String packageJvm, String[] toModules, int flags) {
        public Exports {
            if (packageJvm == null) {
                throw new IllegalArgumentException("exports.packageJvm must not be null");
            }
            toModules = EmptyArrays.orEmpty(toModules, EmptyArrays.STRING);
        }
    }

    /** An {@code opens} directive: {@code opens <package> [to <module>...]}. */
    public record Opens(String packageJvm, String[] toModules, int flags) {
        public Opens {
            if (packageJvm == null) {
                throw new IllegalArgumentException("opens.packageJvm must not be null");
            }
            toModules = EmptyArrays.orEmpty(toModules, EmptyArrays.STRING);
        }
    }

    /** A {@code provides} directive: {@code provides <service> with <impl>...}. */
    public record Provides(String serviceJvm, String[] implJvms) {
        public Provides {
            if (serviceJvm == null || serviceJvm.isEmpty()) {
                throw new IllegalArgumentException("provides.serviceJvm must be non-empty");
            }
            implJvms = EmptyArrays.orEmpty(implJvms, EmptyArrays.STRING);
        }
    }
}
