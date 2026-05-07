# Kotlin VS Code Extension

Provides Kotlin language support for Visual Studio Code

## Features

* Out-of-the-box integration with Kotlin LSP
* Semantic highlighting
* Autocompletion
* Go to Definition
* Go to References
* Diagnostics
* Intentions and quickfixes
* Rename refactoring
* Document and Workspace symbols
* Documentation hovers and inline values
* Formatting
* Import of Gradle projects

## Requirements

* Visual Studio Code version 1.96.0 and above

## Getting started

* Download the latest release bundle of VSC extension from [RELEASES.md](../RELEASES.md)
* Install the extension into Visual Studio Code via `Extensions | More Action | Install from VSIX`
    * Alternatively, it is possible to drag-and-drop VSIX extension directly into `Extensions` tool window
* Open a folder with a Gradle JVM (**not** Kotlin Multiplatform) project
* Open any Kotlin file
* Enjoy!

## Development

The dev build is sandboxed in `out/dev/` — the source tree stays clean, the IntelliJ overlay can be applied non-destructively, and a single ignored folder (`out/`) covers everything generated.

### Bootstrap, run, watch

In VS Code, press F5 (`Extension`). The launch config triggers the `dev-watch` task, which depends on `dev-bootstrap`, which runs `dev-setup.sh`. The script is idempotent — re-running is fast.

What `dev-setup.sh` does:

- Mirrors static files into `out/dev/`.
- Symlinks `src/` (and `testSources/`) so webpack-watch picks up source edits live.
- Runs `npm install` inside `out/dev/` (npm itself is a no-op when nothing changed).

After F5: edit a file in `src/`, webpack rebuilds, hit `Cmd+R` in the development host.

### Test

```sh
npm test
```

Runs from this directory. It bootstraps `out/dev/` and runs all tests. In VS Code, `Tasks: Run Test Task` does the same via the `dev-test` task.

## License

See [LICENSE.txt](LICENSE.txt).
