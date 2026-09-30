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
`pom.xml` files that are not modules of a parent aggregator). The importer therefore works from a
*todo* set of poms and drains it reactor by reactor:

1. Recursively collect every `pom.xml` under the input directory into a todo set.
2. If the set is empty: delete the output file when it exists as a regular file, print that no
   poms were found, and exit `0`.
3. Unless `--force` was given: if the output file already exists and **no** scanned pom has a
   last-modified time *strictly after* the output’s last-modified time, print that the output is
   up to date and exit without resolving or rewriting. Missing output always triggers a full
   import. Equal timestamps count as up to date.
4. Order the set by **path element count** ascending (number of path segments, not string length).
   Shorter paths are preferred so aggregator / parent reactors are imported before nested orphans.
5. While the todo set is non-empty:
   - Take the pom with the currently shortest path.
   - Load that pom into an embedded Maven session as the reactor root.
   - Walk every project in the reactor and extract namespace and dependency information (see
     mapping below).
   - Remove from the todo set every `pom.xml` that belongs to that reactor, so nested modules are
     not imported again as separate roots.
6. When the todo set is empty, write one aggregated fragment, deduplicating top-level
   `dependencyModules` by id.

```text
scan poms → empty? ──yes──► delete stale output if any; exit 0
                ↓ no
         (!force && output fresh)? ──yes──► exit (keep existing fragment)
                ↓ no / missing / --force
         sort by path segment count
                ↓
         take shortest pom
                ↓
      embedded Maven reactor
                ↓
   extract namespaces + deps
                ↓
   remove reactor poms from todo
                ↓
         todo empty? ──no──┐
                │          │
               yes         │
                ↓          │
      write .metals/mbt.json.maven
```

Shortest-path-first plus reactor removal is what makes multi-reactor workspaces work: aggregators
consume their modules in one pass; any remaining shortest pom starts a new session until nothing
is left.

## Maven → mbt mapping

### Namespaces (build targets)

Main and test are **two different** namespaces. Each Maven project in a reactor yields:

| Maven scope | Namespace id | `sources` | `dependsOn` | `dependencyModules` |
| --- | --- | --- | --- | --- |
| main (`compile`, `provided`, `system`) | `groupId:artifactId:version` | main source roots | ids of reactor modules this project depends on (their main namespace ids) | transitive resolved jar ids for the main classpath |
| test | `groupId:artifactId:version:test` | test source roots | own main id, plus any reactor modules depended on with test scope | transitive jar ids for the test classpath (includes main dependencies) |

Namespace ids use no `:main` suffix; test is marked with `:test` only.

### Top-level `dependencyModules`

The file’s top-level `dependencyModules` array is the union of all resolved *external* artifacts
across every namespace: each entry has a stable `id` (Maven coordinates, treated as opaque by
consumers), a `jar` `file:` URI, and an optional `sources` `file:` URI when a sources jar is
available. Ids are unique in the array.

### Intra-workspace `dependsOn`

When a dependency resolves to another project in the same reactor, it is not listed as an external
jar in that namespace’s `dependencyModules`. Instead its namespace id is listed in `dependsOn`, so
the language server can put that module’s sources on the classpath the same way Metals wires
cross-module relationships today.

### Fields emitted per namespace

Aligned with the Metals schema and existing `.metals/mbt.json` emission:

- `sources` — absolute paths to source roots
- `javacOptions` — Java compiler options for the project
- `dependencyModules` — ids into the top-level array (external jars only)
- `javaHome` — JDK used for this project when known (toolchain / Maven Java home)
- `dependsOn` — other namespace ids in the same `mbt.json`
- optionally `classDirectories` / `projectPath` when useful for tooling parity with Metals

### Embedded Maven responsibilities

The embedder must resolve the reactor graph, compute the **transitive** compile and test
classpaths for every project, and surface source roots, compiler options, and the effective Java
home. Exact Maven Embedder APIs are an implementation detail; the contract is “same picture as a
Maven build would see for that reactor.”

## Scope

**In scope**

- Maven → one Metals-shaped fragment `.metals/mbt.json.maven`
- main and test as separate namespaces
- transitive external jars per target
- reactor-internal links via `dependsOn`
- multiple independent reactors under one directory
- packaging next to java-ls for startup invocation

**Out of scope**

- Gradle, Bazel, or other build systems (those get their own importers; java-ls merges fragments)
- Aligning java-indexing’s Gson DTOs (`MbtTargetInfo` fields `compilerOptions` / `classes`) with
  Metals names (`javacOptions` / `classDirectories`). The importer emits the Metals dialect;
  consumer field-name drift is a follow-up in java-indexing / java-ls.

## References

- Metals: [mbt.json overview](https://github.com/scalameta/metals/blob/main-v2/docs/build-tools/mbt.json.md),
  [schema](https://github.com/scalameta/metals/blob/main-v2/docs/build-tools/mbt.schema.json)
- Local notes: [`java-indexing/doc/mbt-json.md`](../../java-indexing/doc/mbt-json.md)
- Consumer: java-ls runs registered importers, merges `.metals/mbt.json.<system>` into
  `.metals/mbt.json`, then `IndexService` loads `<workspace>/mbt.json` or
  `<workspace>/.metals/mbt.json` and builds a `ClasspathOrder` per namespace from `sources`,
  `dependsOn`, and `dependencyModules`
