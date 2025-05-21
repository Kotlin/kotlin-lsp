Language Server for Kotlin
========

[![Kotlin Alpha](https://kotl.in/badges/experimental.svg)](https://kotlinlang.org/docs/components-stability.html)
[![JetBrains incubator project](https://jb.gg/badges/incubator.svg)](https://confluence.jetbrains.com/display/ALL/JetBrains+on+GitHub)


Pre-alpha official Kotlin support for Visual Studio Code and an implementation of [Language Server Protocol](https://github.com/Microsoft/language-server-protocol)
for the Kotlin language.

The server is based on [IntelliJ IDEA](https://github.com/JetBrains/intellij-community) and the [IntelliJ IDEA Kotlin Plugin](https://github.com/JetBrains/intellij-community/tree/master/plugins/kotlin)
implementation.

### Quick Start

1. Download the latest build of VSC extension via [RELEASES.md](RELEASES.md)
2. Install it as a VSC Extension via `Extensions | More Action | Install from VSIX`
    * Alternatively, it is possible to drag-and-drop VSIX extension directly into `Extensions` tool window
3. Ensure that your Java version is 17 or above
4. Open a folder with JVM-only Kotlin Gradle project and the project will be immediately recognized and LSP activated

![quickstart_sample.gif](images/quickstart_sample.gif)

### Supported features and Roadmap

The best way to track current capabilities and what is going to be supported in the next builds is this table:

>Important note: currently, only JVM-only Kotlin Gradle projects are supported out-of-the box.

* [ ] Project import
  * [x] Gradle JVM project import
  * [ ] Gradle KMP project import
  * [x] JSON-based build system agnostic import
    * [ ] Quickstart for JSON
  * [ ] Maven/Amper import
  * [ ] Dumb mode for no build system at all
* [x] Highlighting
  * [x] Semantic highlighting
* [x] Navigation
  * [x] Navigation to Kotlin (source, binary)
  * [x] Navigation to Kotlin builtins
  * [x] Navigation to Java (source, binary)
* [x] Code actions
  * [x] Quickfixes (i.e. `replace with`)
  * [x] Kotlin inspections
  * [x] Organize imports
  * [x] Go to reference
* [ ] Refactorings
  * [ ] Rename
  * [ ] Move
  * [ ] Change signature
* [x] On-the-fly Kotlin diagnostics
* [x] Completion
  * [x] Analysis-API based completion
  * [x] IJ-based completion
    * [ ] Enable IJ-based completion
* [ ] KDoc support
  * [x] In-project documentation hovers
  * [ ] Dependencies/Java documentation hovers from `source.jar`
* [ ] Code formatting
* [ ] Fully-featured Windows support
* [x] Reactive updates from the filesystem


### Project Status

**The project is in an experimental, pre-alpha, exploratory phase** with the intention to be productionized.

We [move fast, break things](https://xkcd.com/1428/), and explore various aspects of the seamless developer experience 
including Java interoperability, limits of IntelliJ capabilities as a standalone server, native binaries of the LSP, and 
debug capabilities.

The LSP supports most of the essential parts, but its final shape is not near to be defined and 
even the most basic and core parts are being changed on a regular basis.

So we have the corresponding stability guarantees -- **none**. It is okay to use it in your toy 
projects, to experiment with it and to provide your feedback, but it is not recommended 
to depend on its stability in your day-to-day work.


### Supported platforms

In the current state, the golden path has been tested for Visual Studio Code with macOS and Linux platforms.

You can use Kotlin LSP with other LSP-compliant editors, but configuration must be done manually.
Please note that Kotlin LSP uses pull-based diagnostics, so the editor must support that.
You can find a standalone LSP launch script in [kotlin-lsp.sh](scripts/kotlin-lsp.sh).

### Source code

Currently, the LSP implementation is partially closed-source, primarily for the sake of development speed convenience -- 
it heavily depends on parts of IntelliJ, Fleet, and our distributed Bazel build that allows us to 
iterate quickly and experiment much faster, cutting corners and re-using internal infrastructure where it helps.
After the initial stabilization phase and defining the final set of capabilities, we will de-couple the LSP implementation from the internal repository 
and build pipelines and open source it (with an explicit dependency on IntelliJ), this is a temporary constraint.
VSC extension is mirrored into [kotlin-vscode](kotlin-vscode) as it does not depend on anything internal.

### Feedback and issues

The best way to provide your feedback or report an issue is to file a bug [in GitHub issues](https://github.com/Kotlin/kotlin-lsp/issues/new).

As a temporary limitation, direct contributions are not supported as this repository is a read-only mirror,
but it is possible to open a PR into the documentation, and it will be integrated manually by maintainers.
