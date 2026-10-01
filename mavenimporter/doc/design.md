# Maven Importer: Maven Workspaces to mbt.json.maven

## tl;dr

`mavenimporter` is an executable shaded jar that scans a directory for Maven projects, resolves
each reactor with an embedded Maven instance, and writes a Metals-compatible fragment
`.metals/mbt.json.maven`. java-ls runs this jar on startup (when no root `mbt.json` exists),
merges build-system fragments into `.metals/mbt.json`, and indexes from that file.

## Purpose

The language server does not read build files. It only understands
[`mbt.json`](https://github.com/scalameta/metals/blob/main-v2/docs/build-tools/mbt.json.md): a
workspace description with external dependency jars and *namespaces* (compilable units). For Maven
workspaces we need a reliable way to produce that file from `pom.xml` trees.

`mavenimporter` is the Maven producer. It packages as `mavenimporter.jar` (shaded, main class
`ch.castleridge.javals.mavenimporter.Main`) and is copied next to the language server artifact at
build time. Given a directory, it finds every `pom.xml`, imports each distinct Maven reactor once,
and emits one fragment (default: `.metals/mbt.json.maven` under that directory). java-ls owns
combining fragments from all supported build systems into `.metals/mbt.json`.

The target shape follows the Metals V2
[`mbt.schema.json`](https://github.com/scalameta/metals/blob/main-v2/docs/build-tools/mbt.schema.json)
and matches the practical dialect already used in this repository’s `.metals/mbt.json`
(`javacOptions`, absolute source/`javaHome` paths, `file:` URIs for jars).

## CLI

```text
java -jar mavenimporter.jar <directory> [--output <file>] [--force]
```

- **Input:** one directory—the workspace root to scan recursively for `pom.xml` files.
- **Output:** defaults to `<directory>/.metals/mbt.json.maven` (creates parent directories as
  needed). Clients may override with `--output` / `-o`. A relative output path is resolved against
  the input directory; an absolute path is used as-is. java-ls always passes this default so the
  fragment can be merged with other build systems into `.metals/mbt.json`.
- **Force:** `--force` / `-f` skips the up-to-date check and always re-imports and rewrites the
  output.
- **Exit status:** `0` when there are no `pom.xml` files (and any stale output at the chosen path
  is deleted). Non-zero if the directory is unreadable or if Maven fails to resolve a chosen
  reactor root.

## Algorithm

A workspace directory may contain several independent Maven trees (sibling projects, or nested
`pom.xml` files that are not modules of a parent aggregator). The importer works in two phases:
index every reactor’s effective models, then resolve classpaths against a workspace
`WorkspaceReader` so SNAPSHOT (and other) workspace modules are not fetched from `~/.m2`.

1. Recursively collect every `pom.xml` under the input directory into a todo set.
2. If the set is empty: delete the output file when it exists as a regular file, print that no
   poms were found, and exit `0`.
3. Unless `--force` was given: if the output file already exists and **no** scanned pom has a
   last-modified time *strictly after* the output’s last-modified time, print that the output is
   up to date and exit without resolving or rewriting. Missing output always triggers a full
   import. Equal timestamps count as up to date.
4. **Index pass:** Drain the todo set reactor-by-reactor (shortest path first). For each reactor
   root, build models with `ProjectBuilder` (`resolveDependencies=false`, `processPlugins=true`).
   Record every `MavenProject` by effective GAV in a workspace index. Remove that reactor’s poms
   from the todo set.
5. **Resolve pass:** Install a `WorkspaceReader` over the index on the repository session. For each
   non-`pom` project, resolve the transitive classpath with `ProjectDependenciesResolver` (no second
   model build). Workspace GAVs resolve from the indexed poms / output dirs instead of remotes.
6. **Map:** Convert all projects to one aggregated fragment. Dependencies whose GAV is in the
   workspace index become `dependsOn` (including across sibling reactors); other artifacts become
   top-level `dependencyModules`.

```text
scan poms → empty? ──yes──► delete stale output if any; exit 0
                ↓ no
         (!force && output fresh)? ──yes──► exit (keep existing fragment)
                ↓ no / missing / --force
         drain reactors (models only) → workspace GAV index
                ↓
         WorkspaceReader + ProjectDependenciesResolver
                ↓
         map namespaces / dependsOn / dependencyModules
                ↓
      write .metals/mbt.json.maven
```

Shortest-path-first plus reactor removal is what makes multi-reactor workspaces work during the
index pass: aggregators consume their modules in one go; any remaining shortest pom starts a new
reactor until nothing is left.

## Maven → mbt mapping

### Namespaces (build targets)

Main and test are **two different** namespaces. Each Maven project in a reactor yields:

| Maven scope | Namespace id | `sources` | `dependsOn` | `dependencyModules` |
| --- | --- | --- | --- | --- |
| main (`compile`, `provided`, `system`) | `groupId:artifactId:version` | main source roots | ids of workspace modules this project depends on (their main namespace ids) | transitive resolved jar ids for the main classpath |
| test | `groupId:artifactId:version:test` | test source roots | own main id, plus any workspace modules depended on with test scope | transitive jar ids for the test classpath (includes main dependencies) |

Namespace ids use no `:main` suffix; test is marked with `:test` only.

### Top-level `dependencyModules`

The file’s top-level `dependencyModules` array is the union of all resolved *external* artifacts
across every namespace: each entry has a stable `id` (Maven coordinates, treated as opaque by
consumers), a `jar` `file:` URI, and an optional `sources` `file:` URI when a sources jar is
available. Ids are unique in the array.

### Intra-workspace `dependsOn`

When a dependency’s GAV matches another project anywhere in the scanned workspace (same reactor or
a sibling tree), it is not listed as an external jar in that namespace’s `dependencyModules`.
Instead its namespace id is listed in `dependsOn`, so the language server can put that module’s
sources on the classpath the same way Metals wires cross-module relationships today.

### Fields emitted per namespace

Aligned with the Metals schema and existing `.metals/mbt.json` emission:

- `sources` — absolute paths to source roots (conventional compile roots that exist on
  disk, plus plugin-generated roots from the registry below—even when those directories
  do not exist yet)
- `javacOptions` — Java compiler options for the project
- `dependencyModules` — ids into the top-level array (external jars only)
- `javaHome` — JDK used for this project when known (toolchain / Maven Java home)
- `dependsOn` — other namespace ids in the same `mbt.json`
- optionally `classDirectories` / `projectPath` when useful for tooling parity with Metals

### Generated source roots

`ProjectBuilder` with `processPlugins=true` configures plugins but does **not** run
`generate-sources` / compiler annotation processing. Roots that those mojos would add via
`MavenProject.addCompileSourceRoot` therefore never appear in
`getCompileSourceRoots()`. The importer does not execute the lifecycle either: instead it
keeps a static registry of rules that map known plugins to a POM configuration path and a
default directory.

For each non-`pom` project, after collecting existing conventional source roots, the
importer walks that registry:

1. Look up the plugin on the effective model (`MavenProject.getPlugin("groupId:artifactId")`).
2. If the rule lists goals, only executions whose goals intersect apply (e.g. build-helper
   `add-source`); otherwise plugin-level config applies, and any execution that sets the
   config path is merged in as well.
3. Read the configured value under `<configuration>` using the rule’s slash-separated path
   (execution config overrides plugin-level). If absent, use the rule’s default when one
   exists; if the rule requires an explicit value and none is set, skip.
4. Interpolate `${project.build.directory}` / `${project.basedir}`, resolve relative paths
   against the module basedir, and append absolute paths to the namespace `sources` list
   (deduplicated). Plugin-derived paths are always emitted even when the directory is
   missing, so a later `mvn generate-sources` / compile can populate them and java-ls
   watchers can pick them up. (`IndexService` already skips non-directories at index time.)

**Rule shape** (see `GeneratedSourceRule` / `GeneratedSourceRules`):

- `pluginKey` — `groupId:artifactId`
- `goals` — optional; empty means plugin-level (plus executions that set the path)
- `scope` — main or test namespace
- `configPath` — e.g. `outputDirectory`, `generatedSourcesDirectory`, `sources/source`
- `list` — single value vs repeated child elements
- `defaultPath` — property-style default, or none
- `required` — skip when neither config nor default yields a path

**Seed plugins (v1):**

| Plugin | Config | Default | Scope |
| --- | --- | --- | --- |
| `org.codehaus.modello:modello-maven-plugin` | `outputDirectory` | `${project.build.directory}/generated-sources/modello` | main |
| `org.apache.maven.plugins:maven-compiler-plugin` | `generatedSourcesDirectory` | `…/generated-sources/annotations` | main |
| same | `generatedTestSourcesDirectory` | `…/generated-test-sources/test-annotations` | test |
| `org.codehaus.mojo:build-helper-maven-plugin` (`add-source`) | `sources/source` (list) | none | main |
| same (`add-test-source`) | `sources/source` (list) | none | test |

To support another generator (antlr, jaxb, protobuf, …), add a `GeneratedSourceRule` with
that plugin’s coordinates, config element, and documented default — or supply the same shape
via language-server `maven.generatedSourceRules` (VS Code: `javals.maven.generatedSourceRules`).
Entries with `"enabled": false` disable matching seed rules (`pluginKey` required; optional
`scope` / `goals` / `configPath` narrow the match). The server writes the array to
`.metals/generated-source-rules.json` and passes `--generated-source-rules` to the importer.
No importer lifecycle changes are required.

### Embedded Maven responsibilities

The embedder indexes the reactor graph with `ProjectBuilder` (models only), installs a workspace
`WorkspaceReader`, then uses `ProjectDependenciesResolver` for transitive compile/test classpaths.
It surfaces source roots, compiler options, and the effective Java home. Exact Embedder APIs are an
implementation detail; the contract is “same picture as a Maven build would see,” with workspace
modules preferred over the local repository.

## Scope

**In scope**

- Maven → one Metals-shaped fragment `.metals/mbt.json.maven`
- main and test as separate namespaces
- transitive external jars per target
- reactor-internal and cross-reactor workspace links via `dependsOn`
- workspace `WorkspaceReader` so SNAPSHOT modules resolve from disk
- multiple independent reactors under one directory
- packaging next to java-ls for startup invocation
- generated source roots from a plugin-config registry (modello, compiler APT,
  build-helper, …) without executing those mojos

**Out of scope**

- Gradle, Bazel, or other build systems (those get their own importers; java-ls merges fragments)
- Aligning java-indexing’s Gson DTOs (`MbtTargetInfo` fields `compilerOptions` / `classes`) with
  Metals names (`javacOptions` / `classDirectories`). The importer emits the Metals dialect;
  consumer field-name drift is a follow-up in java-indexing / java-ls.
- Running generate-sources / annotation-processing mojos, or inventing roots for plugins not
  listed in the registry

## References

- Metals: [mbt.json overview](https://github.com/scalameta/metals/blob/main-v2/docs/build-tools/mbt.json.md),
  [schema](https://github.com/scalameta/metals/blob/main-v2/docs/build-tools/mbt.schema.json)
- Local notes: [`java-indexing/doc/mbt-json.md`](../../java-indexing/doc/mbt-json.md)
- Consumer: java-ls runs registered importers, merges `.metals/mbt.json.<system>` into
  `.metals/mbt.json`, then `IndexService` loads `<workspace>/mbt.json` or
  `<workspace>/.metals/mbt.json` and builds a `ClasspathOrder` per namespace from `sources`,
  `dependsOn`, and `dependencyModules`
