Language Server for Kotlin
========

[![Kotlin Alpha](https://kotl.in/badges/alpha.svg)](https://kotlinlang.org/docs/components-stability.html)
[![JetBrains official project](https://jb.gg/badges/official.svg)](https://confluence.jetbrains.com/display/ALL/JetBrains+on+GitHub)
[![GitHub license](https://img.shields.io/github/license/kotlin/kotlinx-io)](LICENSE.txt)
[![GitHub Release](https://img.shields.io/github/v/release/Kotlin/kotlin-lsp)](https://github.com/Kotlin/kotlin-lsp/releases/latest)

[![VS Code Extension](https://img.shields.io/badge/VS%20Code-Kotlin_by_JetBrains-007ACC?logo=visualstudiocode&logoColor=white)](https://marketplace.visualstudio.com/items?itemName=JetBrains.kotlin-server)

Official Kotlin support for Visual Studio Code and an implementation of the [Language Server Protocol](https://github.com/Microsoft/language-server-protocol)
for the Kotlin language.

The server is based on [IntelliJ IDEA](https://github.com/JetBrains/intellij-community) and the [IntelliJ IDEA Kotlin Plugin](https://github.com/JetBrains/intellij-community/tree/master/plugins/kotlin)
implementation.

### VS Code Quick Start

For the latest stable release:

* [Install the extension](https://marketplace.visualstudio.com/items?itemName=org.jetbrains.kotlin-server)
* The extension is automatically activated when opening a Kotlin file in a workspace

For manually downloaded builds from the [release page](https://github.com/Kotlin/kotlin-lsp/releases):

1. Download the required VSIX file from the corresponding release page
2. Install it as a VSC Extension via `Extensions | More Actions | Install from VSIX`
    * Alternatively, it is possible to drag-and-drop VSIX extension directly into the `Extensions` tool window

### Install kotlin-lsp CLI

For Homebrew users: `brew install JetBrains/utils/kotlin-lsp`

Manual installation:
1. Download the standalone zip from the [Releases Page](https://github.com/Kotlin/kotlin-lsp/releases)
2. Unpack the ZIP file
3. `chmod +x $KOTLIN_LSP_DIR/kotlin-lsp.sh`
4. Create a symlink on your `$PATH` `ln -s $KOTLIN_LSP_DIR/kotlin-lsp.sh $HOME/.local/bin/kotlin-lsp`

### Supported Features

* Up-to-date Kotlin language versions support
* IntelliJ-powered code completion
* IntelliJ-powered diagnostics and quick fixes for Kotlin and kotlinx libraries
* Build system support: Gradle, Maven, experimental AGP support
* Semantic highlighting
* Organize imports
* Rename refactoring
* Code formatting
* Documentation navigation and hover support
* Call Hierarchy
* Code Folding

### Project Status

⚠️ **The project is currently in the Alpha state** ⚠️

The language server is based on the most recent IntelliJ IDEA version and proprietary parts of JetBrains Air and Fleet products, 
making it partially closed-source.

Its core implementation details and general architecture are mostly settled and stable.
Upcoming releases will focus on feature completeness and stability of the existing functionality.

### Supported editors

`kotlin-server` is designed to work with any editor that supports LSP.
Releases are generally tested with [Visual Studio Code](https://code.visualstudio.com/) and there is a community-maintained
list of scripts for other editors in the [scripts](scripts) folder.
See also `./kotlin-lsp.sh --help` for available options.

### Feedback and issues

The best way to provide feedback or report an issue is to file a bug [in GitHub issues](https://github.com/Kotlin/kotlin-lsp/issues/new).

Currently, direct contributions are not supported as this repository is a read-only mirror,
but it is possible to open a PR for documentation changes, and it will be integrated manually by maintainers.
