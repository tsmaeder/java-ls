# Change Log

All notable changes to the "java-ls" language server will be documented in this file.

Check [Keep a Changelog](http://keepachangelog.com/) for recommendations on how to structure this file.

## [Unreleased] <tbd>

- Added Gradle importer as default importer (`gradle-extractor.jar`)
- Extracted Maven importer to sibling repo `maven-extractor`; default jar renamed to `maven-extractor.jar`
- Added Kotlin source indexing (`.kt` files are watched and indexed for Java type resolution)
- Indexed compile-time constant fields with folded values so constant references resolve correctly
- Indexed private nested types as stubs so they remain resolvable from the index
- Preserved existing `mbt.json` when an importer run finds no fragments
- Fixed source-jar lookup for self-attached dependencies
- Changed indexing order to JDK, then project sources, then dependency jars. This allows faster navigation in source

## 0.1.0 2026-10-01

- Added document symbol support (outline / breadcrumbs)
- Added call hierarchy support (prepare / incoming / outgoing)
- Added Javadoc hover (signature plus Markdown-rendered Javadoc)
- Added build-system importer scripts via `initializationOptions.importers` (Maven jar is the default)
- Added Maven generated-source roots with configurable `maven.generatedSourceRules`
- Changed metadata folder to `.javals`
- Aligned `mbt.json` with the Metals V2 spec
- Added `mbt.json` reload and input delta re-indexing
- Stopped publishing diagnostics for attached sources
- Added ECJ JPMS module support
