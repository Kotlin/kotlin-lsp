# Kotlin by JetBrains

[![Kotlin Alpha](https://img.shields.io/badge/project-alpha-kotlin.svg?colorA=555555&colorB=DB3683&label=&logo=kotlin&logoColor=ffffff&logoWidth=10)](https://kotlinlang.org/docs/components-stability.html)
[![JetBrains official project](https://img.shields.io/badge/project-official-green.svg?colorA=303033&colorB=ff8a2c&label=JetBrains)](https://confluence.jetbrains.com/display/ALL/JetBrains+on+GitHub)
[![License](https://img.shields.io/badge/License-JetBrains_Free_Plugin_License-brightgreen)](https://www.jetbrains.com/legal/docs/terms/jetbrains-free-plugin-license)

Official Kotlin support for Visual Studio Code and an implementation of the [Language Server Protocol](https://github.com/Microsoft/language-server-protocol)
for the Kotlin language.

## Extension status

**This extension is currently in the Alpha state.**

The server is based on [IntelliJ IDEA](https://github.com/JetBrains/intellij-community) and the [IntelliJ IDEA Kotlin Plugin](https://github.com/JetBrains/intellij-community/tree/master/plugins/kotlin)
implementation.

The core implementation and overall architecture are mostly settled and stable.
Upcoming releases will focus on feature completeness and stability of the existing functionality.

## Quick Start

1. Install the extension in Visual Studio Code.
2. Open a folder with a Kotlin JVM Gradle or Maven project.
3. Open any `.kt` file to activate the extension and language server.
   - **Important:** If you already have a "Kotlin by JetBrains" extension installed from GitHub, a dialog will offer to uninstall it. 
   Accept it and reload the window — the new `jetbrains.kotlin-server` extension cannot properly activate while the old `jetbrains.kotlin` 
   extension is present.
4. Wait for project import and indexing to complete.

After that, you can start using the extension's features!

## Supported Features

* Up-to-date Kotlin language versions support
* IntelliJ-powered code completion and signature help
* IntelliJ-powered diagnostics, inspections, and quick fixes for Kotlin and `kotlinx` libraries
* Build system support for JVM projects: Gradle, Maven, experimental Android Gradle Plugin support
  * Support for Kotlin Multiplatform (KMP) projects is coming in the future releases.
* Semantic highlighting
* Inlay hints for types and parameter names
* Navigation: definition, references, type definition, and implementation
* Call Hierarchy
* Document and workspace symbols
* Documentation navigation and hover support
* Organize imports
* Rename refactoring
* Code formatting
* Code Folding
* File templates for creating new Kotlin files

## Configuration

The extension provides a number of VS Code configuration options to customize its behavior, including:

* `jetbrains.kotlin.hints.*` to configure inlay hints
* `jetbrains.templates.kotlin.*` to customize Kotlin file templates
* `intellij.buildTool` to prefer a specific project importer (e.g., `maven` or `gradle`)

## Feedback and Issues

The best way to provide feedback or report an issue is to file a bug in [GitHub issues](https://github.com/Kotlin/kotlin-lsp/issues/new).

## License

See [JetBrains Free Plugin License Agreement](https://www.jetbrains.com/legal/docs/terms/jetbrains-free-plugin-license).
