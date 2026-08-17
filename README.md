# java-ls

java-ls is a proof-of-concept language server that is based on the idea of keeping an index of type declarations in source- and
classfiles in workspace and using that index to feed type analysis in the compiler. Computing this index is completely
dependency-free, meaning we can look at every source or class file in parallel and updating the index for a source file only affects the single
index entry for that file. This index can be made quite reasonable in size (400mb total heap for the Trino source workspace)
and grows linearly with workspace size (sources and jars). The index could also be precomputed and read from a file.

When we implement language server features like "go-to-definition" or "type hierarchy", we need to fully resolve the types in
the concerned source files. We do this analysis at runtime, but modify the compiler to directly build the resolved type objects
from the index instead of reading them from source or class files. It turns out this is really fast. The type analysis uses
the correct compile classpath for each file when resolving types, so references will be precise even with classpath shadowing.

java-ls can support different indexer and LSP feature set implementations. We currently support both javac as well as the Eclipse
compiler. The Eclipse compiler is generally around 50% faster at both indexing and analysis.

We also keep a Bloom filter index for each indexed file. When we need to do cross-referencing (like "find all references" to a
method), we compute a set of candidate files from the Bloom index then analyze all candidates using the compiler+index. Computing
the references to java.lang.String in the Trino source is at least twice as fast as in any other tested language server. Although
source analysis produces lots of objects, they are only retained while the source file is being analyzed. So the maximum heap requirement is small.

java-ls currently supports "go to declaration", "find references", "super types" and "sub types". A simple implementation of
code completion is also present, but very early days.
The code in this repo is very largely written using AI, so there are probably lots of improvements in cleanliness and style
to be made.

java-ls does not currently have its own build file importer: instead we rely on the "mbt.json" format that has recently been
introduced as part of the [Metals V2](https://metals-lsp.org/) effort. In order to create an mbt.json file for use with java-ls,
you can simply open the workspace with metals V2 through the [metals-vscode](https://github.com/scalameta/metals-vscode/) extension
from your favourite extension marketplace. The mbt.json file describes the structure of the workspace in a general format that
contains the minimum necessary information to interpret the code correctly.

## A Note of Thanks

This code has been created as a test bed for trying out different ideas during a year-long project on behalf of [Cursor AI](https://cursor.com/) to improve Java support for large code bases. I would like to extend my thanks to Cursor for letting me open-source this
code under MIT license to make it available to others.

Design notes live in [doc/java-ls.md](doc/java-ls.md).

## Prerequisites

- **JDK 25** (`JAVA_HOME` or `java` on `PATH`)
- **Maven 3.9+**

The server uses internal `jdk.compiler` APIs, so a full JDK is required (not a JRE).

## Modules

| Module | Role |
| --- | --- |
| `java-indexing` | Context-free indexing of `.java` / `.class` / jars into an in-memory declaration index |
| `java-ls` | LSP server (stdio) that loads the index and analyzes open files |

## Build

From the repository root:

```bash
mvn package
```

To build only the language server and its dependencies:

```bash
mvn -pl java-ls -am package
```

That produces the shaded runnable jar:

```text
java-ls/target/java-ls.jar
```

Run tests with:

```bash
mvn test
```

## Run the language server

The server speaks LSP over **stdio**. Entry point: `ch.castleridge.javals.App`.

```bash
java \
  --add-exports jdk.compiler/com.sun.tools.javac.main=ALL-UNNAMED \
  --add-exports jdk.compiler/com.sun.tools.javac.api=ALL-UNNAMED \
  --add-exports jdk.compiler/com.sun.tools.javac.code=ALL-UNNAMED \
  --add-exports jdk.compiler/com.sun.tools.javac.comp=ALL-UNNAMED \
  --add-exports jdk.compiler/com.sun.tools.javac.file=ALL-UNNAMED \
  --add-exports jdk.compiler/com.sun.tools.javac.jvm=ALL-UNNAMED \
  --add-exports jdk.compiler/com.sun.tools.javac.util=ALL-UNNAMED \
  --add-opens jdk.compiler/com.sun.tools.javac.code=ALL-UNNAMED \
  --add-opens jdk.compiler/com.sun.tools.javac.jvm=ALL-UNNAMED \
  --add-opens jdk.compiler/com.sun.tools.javac.tree=ALL-UNNAMED \
  -jar java-ls/target/java-ls.jar
```

Clients normally launch this process for you (see below).

### Workspace indexing

On `initialize`, the server looks for an `mbt.json` under the workspace folders. That file describes sources, jars, and classpath layout used to build the index. Without it, indexing is disabled and most semantic features will not work.

## Configuration

Settings are passed by the client in LSP `initialize` → `initializationOptions`. Example:

```json
{
  "workspacePath": "/path/to/workspace",
  "referencesCandidateCap": 500,
  "backend": {
    "indexer": "javac",
    "compiler": "javac"
  }
}
```

| Option | Type | Default | Description |
| --- | --- | --- | --- |
| `workspacePath` | string | first workspace folder (else parent of `mbt.json`) | Root used to resolve relative paths from `mbt.json` |
| `referencesCandidateCap` | number | uncapped (`≤ 0` or omitted) | Max candidate files scanned for find-references after Bloom filtering; open documents and the origin file are always included |
| `backend.indexer` | `"javac"` \| `"ecj"` | `"javac"` | Compiler used when indexing sources |
| `backend.compiler` | `"javac"` \| `"ecj"` | `"javac"` | Compiler used when analyzing open files (diagnostics, navigation, etc.) |

In [vscode-javals](https://github.com/tsmaeder/vscode-javals), `backend.indexer` / `backend.compiler` map to `javals.backend.indexer` and `javals.backend.compiler`.

## Use with VS Code / Cursor ([vscode-javals](https://github.com/tsmaeder/vscode-javals))

The intended editor client is the sibling **[vscode-javals](https://github.com/tsmaeder/vscode-javals)** extension. It starts `java-ls.jar` over stdio and wires Java LSP features into VS Code or Cursor.

Expected layout for local development:

```text
<parent>/
  java-ls/                 ← this repository
    java-ls/target/java-ls.jar
  vscode-javals/           ← VS Code extension
```

1. Build this project (`mvn -pl java-ls -am package`).
2. In `vscode-javals`, run `npm ci` and `npm run compile`.
3. Open `vscode-javals` in VS Code / Cursor and press **F5** (Extension Development Host).
4. Open a Java workspace that contains `mbt.json`. Opening a `.java` file starts the server.

With default `javals.serverMode: auto`, the extension prefers the shaded jar from this Maven project when present; otherwise it falls back to a jar bundled under `vscode-javals/server/`.

After changing server code, rebuild with Maven and run **JavaLS: Restart Language Server** from the command palette. See the [vscode-javals README](https://github.com/tsmaeder/vscode-javals) for settings (`javals.javaHome`, backends, debug attach, and so on).

## Indexing CLI (optional)

`java-indexing` also ships a shaded CLI for standalone scans (useful for timing / memory experiments):

```bash
mvn -pl java-indexing package
java -jar java-indexing/target/java-indexing-1.0-SNAPSHOT-cli.jar --mbt path/to/mbt.json
```

Other inputs: `--dir <path>`, `--jar <path>`, `--jrt [module]`.

