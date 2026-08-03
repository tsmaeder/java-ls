# Agents.md

## Mission

The point of this project is to prototype a java language server that works agains an index that can be precomputed without
resolving against a class path. I.e. indexing can be 100% parallelized.
When analyzing any given file, types are resolved using the index and a complete compile classpath control visibility of
entries in the index. Thus lack of visibility and type shadowing on the class path can be handled correctly.

## Rules

Do not preseve backwards compatibility. Remove obsolete paths instead of adding compatilibity layers, fallbacks or migrations.
