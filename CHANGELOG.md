# Change Log

All notable changes to the "java-ls" language server will be documented in this file.

Check [Keep a Changelog](http://keepachangelog.com/) for recommendations on how to structure this file.

## [Unreleased] <tbd>

- Added gradle importer as default importer

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
