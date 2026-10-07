# MBT-JSON

`mbt.json` describes a workspace for indexing and classpath construction. The shape follows the
Metals V2 schema:

- Overview: <https://github.com/scalameta/metals/blob/main-v2/docs/build-tools/mbt.json.md>
- Schema: <https://github.com/scalameta/metals/blob/main-v2/docs/build-tools/mbt.schema.json>

Local consumers:

- `java-indexing` — `MbtJson.read` / `MbtJson.toInputSources` (and `--mbt` on `IndexDecompilerMain`)
- `java-ls` — `IndexService` builds a `ClasspathOrder` per namespace from the same file
- `mavenimporter` — emits Metals-schema fragments (default `.javals/mbt.json.maven`)

Field names match the schema (`javacOptions`, `sources`, `dependencyModules`, `dependsOn`,
`javaHome`, optional `scalacOptions`). `uncheckedSources` is a Metals-specific extension and is
intentionally ignored (not read or emitted). Source roots are workspace-relative with forward
slashes; dependency jars use `file:` URIs; `javaHome` is a filesystem path.
