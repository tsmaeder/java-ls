# java-ls

A prototype Java language server that indexes sources and class files without resolving against a classpath, then uses that index (plus a per-file compile classpath) for LSP features such as go-to-definition, references, and diagnostics.

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
| `referencesCandidateCap` | number | uncapped (`≤ 0` or omitted) | Max candidate files scanned for find-references after bloom filtering; open documents and the origin file are always included |
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

