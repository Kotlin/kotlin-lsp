### Kotlin LSP and VSC extension releases

This file contains TeamCity auto-generated download links that are updated on a weekly basis.
These are pre-alpha builds that are built directly from `master` branch after the initial acceptance.

### v263.4702.0
- :test_tube: "Kotlin by JetBrains" extension v0.0.12 for VS Code

  Includes Kotlin Language Server bundled for use with Visual Studio Code.

  The extension is also available on the [VS Code Marketplace](https://marketplace.visualstudio.com/items?itemName=JetBrains.kotlin-server) and [OpenVSX Registry](https://open-vsx.org/extension/JetBrains/kotlin-server).
    * [Download for macOS-x64](https://download.jetbrains.com/language-server/kotlin-server/263.4702.0/kotlin-server-0.0.12-mac-amd64.vsix)&nbsp;&nbsp;|&nbsp;&nbsp;[SHA-256 checksum](https://download.jetbrains.com/language-server/kotlin-server/263.4702.0/kotlin-server-0.0.12-mac-amd64.vsix.sha256)
    * [Download for macOS-arm64](https://download.jetbrains.com/language-server/kotlin-server/263.4702.0/kotlin-server-0.0.12-mac-aarch64.vsix)&nbsp;&nbsp;|&nbsp;&nbsp;[SHA-256 checksum](https://download.jetbrains.com/language-server/kotlin-server/263.4702.0/kotlin-server-0.0.12-mac-aarch64.vsix.sha256)
    * [Download for Linux-x64](https://download.jetbrains.com/language-server/kotlin-server/263.4702.0/kotlin-server-0.0.12-linux-amd64.vsix)&nbsp;&nbsp;|&nbsp;&nbsp;[SHA-256 checksum](https://download.jetbrains.com/language-server/kotlin-server/263.4702.0/kotlin-server-0.0.12-linux-amd64.vsix.sha256)
    * [Download for Linux-arm64](https://download.jetbrains.com/language-server/kotlin-server/263.4702.0/kotlin-server-0.0.12-linux-aarch64.vsix)&nbsp;&nbsp;|&nbsp;&nbsp;[SHA-256 checksum](https://download.jetbrains.com/language-server/kotlin-server/263.4702.0/kotlin-server-0.0.12-linux-aarch64.vsix.sha256)
    * [Download for Windows-x64](https://download.jetbrains.com/language-server/kotlin-server/263.4702.0/kotlin-server-0.0.12-win-amd64.vsix)&nbsp;&nbsp;|&nbsp;&nbsp;[SHA-256 checksum](https://download.jetbrains.com/language-server/kotlin-server/263.4702.0/kotlin-server-0.0.12-win-amd64.vsix.sha256)
    * [Download for Windows-arm64](https://download.jetbrains.com/language-server/kotlin-server/263.4702.0/kotlin-server-0.0.12-win-aarch64.vsix)&nbsp;&nbsp;|&nbsp;&nbsp;[SHA-256 checksum](https://download.jetbrains.com/language-server/kotlin-server/263.4702.0/kotlin-server-0.0.12-win-aarch64.vsix.sha256)


- :card_index_dividers: **Standalone Kotlin LSP Archive**

  Standalone Kotlin Language Server version for editors other than VS Code.

    * [Download for macOS-x64](https://download.jetbrains.com/language-server/kotlin-server/263.4702.0/kotlin-server-263.4702.0.sit)&nbsp;&nbsp;|&nbsp;&nbsp;[SHA-256 checksum](https://download.jetbrains.com/language-server/kotlin-server/263.4702.0/kotlin-server-263.4702.0.sit.sha256)
    * [Download for macOS-arm64](https://download.jetbrains.com/language-server/kotlin-server/263.4702.0/kotlin-server-263.4702.0-aarch64.sit)&nbsp;&nbsp;|&nbsp;&nbsp;[SHA-256 checksum](https://download.jetbrains.com/language-server/kotlin-server/263.4702.0/kotlin-server-263.4702.0-aarch64.sit.sha256)
    * [Download for Linux-x64](https://download.jetbrains.com/language-server/kotlin-server/263.4702.0/kotlin-server-263.4702.0.tar.gz)&nbsp;&nbsp;|&nbsp;&nbsp;[SHA-256 checksum](https://download.jetbrains.com/language-server/kotlin-server/263.4702.0/kotlin-server-263.4702.0.tar.gz.sha256)
    * [Download for Linux-arm64](https://download.jetbrains.com/language-server/kotlin-server/263.4702.0/kotlin-server-263.4702.0-aarch64.tar.gz)&nbsp;&nbsp;|&nbsp;&nbsp;[SHA-256 checksum](https://download.jetbrains.com/language-server/kotlin-server/263.4702.0/kotlin-server-263.4702.0-aarch64.tar.gz.sha256)
    * [Download for Windows-x64](https://download.jetbrains.com/language-server/kotlin-server/263.4702.0/kotlin-server-263.4702.0.win.zip)&nbsp;&nbsp;|&nbsp;&nbsp;[SHA-256 checksum](https://download.jetbrains.com/language-server/kotlin-server/263.4702.0/kotlin-server-263.4702.0.win.zip.sha256)
    * [Download for Windows-arm64](https://download.jetbrains.com/language-server/kotlin-server/263.4702.0/kotlin-server-263.4702.0-aarch64.win.zip)&nbsp;&nbsp;|&nbsp;&nbsp;[SHA-256 checksum](https://download.jetbrains.com/language-server/kotlin-server/263.4702.0/kotlin-server-263.4702.0-aarch64.win.zip.sha256)

##### Changelog

> [!NOTE]
> "Kotlin by JetBrains" is now on the [Open VSX Registry](https://open-vsx.org/extension/JetBrains/kotlin-server).
> You can install it in Cursor, VSCodium, and other VS Code forks. <!-- LSP-1425, LSP-1227 -->
>
> JetBrains also released "Java and Kotlin by IntelliJ IDEA", which supports both languages.
> If you install it while "Kotlin by JetBrains" is present, it offers to remove this extension.
> This is expected. Two language servers must not run at the same time. <!-- LSP-1422 -->
>
> Find out more at <https://www.jetbrains.com/help/intellij-vscode/About-instance.html>.

> [!IMPORTANT]
> When the extension first starts, it now asks you to choose a Region and a Data Sharing option.
> The language server does not start until you choose both.
> You can change them later in the VS Code settings. <!-- LSP-1344, LSP-550, LSP-1424 -->

#### 🛠 LSP capabilities

* Most of the Kotlin intentions from the IntelliJ Kotlin plugin are now available in Kotlin LSP. <!-- LSP-569 -->
  <!-- LSP-157, LSP-1216, LSP-1255, LSP-1403, LSP-1753, LSP-1624 -->
* Code lenses above a Kotlin `main` function now let you run or debug it. <!-- LSP-1321, LSP-1323 -->
* "Organize Imports" is now available as a Source Action. <!-- LSP-1212 -->
* Live template completion now works in Kotlin (e.g. `main`, `sout`, or `if`). <!-- LSP-1622 -->
* You can now move Kotlin files while preserving correct imports and references in other files. <!-- LSP-1319 -->
  * Moving entire folders is not supported yet.
* Workspace symbol search now finds symbols in libraries. <!-- LSP-1179 -->
* A new status bar widget shows the server state and lets you restart the server. <!-- LSP-1170 -->

#### 🐞 Run & Debug

* The extension reads `launch.json`, and it can launch a JVM application. <!-- LSP-1207, LSP-1051 -->
* Completion works in the debug console. Evaluation no longer stops at "Collecting Data".
  <!-- LSP-1304, LSP-451 -->

#### 📦 Import & build systems

* You can now import more than one project from a single workspace. The `intellij.projects` setting
  lists each project with its build system and its path. This covers a monorepo, and a directory
  that holds several independent projects. Find out more at
  <https://www.jetbrains.com/help/intellij-vscode/Project-import.html>. <!-- LSP-960, LSP-1302 -->
* The workspace now reloads automatically when you edit and save a build script. A new setting controls the reload.
  It can reload always, ask first, or never reload. New commands are also added: "Reimport
  Project" and "Clear Caches and Restart".
* Gradle sync now downloads the library sources by default. This makes source lookup work in a
  library. The first sync takes more time. <!-- LSP-1192 -->
* The server uses the Gradle Tooling API 9.5.0. <!-- LSP-1095 -->
* Gradle import uses the JRE of the language server when `JAVA_HOME` is not set. Maven import now
  honors the `java-home` entry of the `intellij.projects` setting.
  <!-- LSP-1148, LSP-1497 -->
* Maven: the importer honors the compiler arguments, and the spurious jar warnings are gone.
  Maven import works on Windows. <!-- LSP-940, LSP-489, LSP-1560 -->
* Better import of a multi-root workspace. The extension also tells you when the project root
  holds more than one build system. <!-- LSP-1409, LSP-1407, LSP-1615 -->
* Indexing now reports the progress as a percentage. <!-- LSP-1256, LSP-883 -->
* Build tool output goes to a separate VS Code output channel. <!-- LSP-679 -->
* The extension now activates automatically when there is a build file somewhere in the project. <!-- LSP-1000 -->

#### ⌨️ Editor / typing

* More Enter-key fixes in a string template. Pressing Enter no longer inserts an incorrect string
  concatenation. <!-- LSP-1141, LSP-1367, LSP-1391, LSP-1392 -->
* "Toggle line comment" action works again. <!-- LSP-1176 -->

#### 🚀 Performance

* Faster indexing, and fewer stalls during and after the project import.
  The local inspections also run faster. <!-- LSP-115, LSP-1235, LSP-1248, LSP-278 -->

#### 🐛 Bug fixes

* Import of an Android Gradle project no longer fails with a `ClassCastException`. Fixes
  <https://github.com/Kotlin/kotlin-lsp/issues/243>. <!-- LSP-1561 -->
* Windows: the analyzer database moved from the roaming profile to `%LOCALAPPDATA%`. Fixes <https://github.com/Kotlin/kotlin-lsp/issues/215>. <!-- LSP-1004 -->
* The server now closes the OS file handles for the JAR files it opens. <!-- LSP-244 -->
* The server uses the proxy settings of VS Code. <!-- LSP-1532 -->
* Many other small improvements and bug fixes.

### v262.9593.0
- :test_tube: "Kotlin by JetBrains" extension v0.0.6 for VS Code

  Includes Kotlin Language Server bundled for use with Visual Studio Code.

  The extension is also available on the [VS Code Marketplace](https://marketplace.visualstudio.com/items?itemName=JetBrains.kotlin-server).
    * [Download for macOS-x64](https://download-cdn.jetbrains.com/language-server/kotlin-server/262.9593.0/kotlin-server-0.0.6-mac-amd64.vsix)&nbsp;&nbsp;|&nbsp;&nbsp;[SHA-256 checksum](https://download-cdn.jetbrains.com/language-server/kotlin-server/262.9593.0/kotlin-server-0.0.6-mac-amd64.vsix.sha256)
    * [Download for macOS-arm64](https://download-cdn.jetbrains.com/language-server/kotlin-server/262.9593.0/kotlin-server-0.0.6-mac-aarch64.vsix)&nbsp;&nbsp;|&nbsp;&nbsp;[SHA-256 checksum](https://download-cdn.jetbrains.com/language-server/kotlin-server/262.9593.0/kotlin-server-0.0.6-mac-aarch64.vsix.sha256)
    * [Download for Linux-x64](https://download-cdn.jetbrains.com/language-server/kotlin-server/262.9593.0/kotlin-server-0.0.6-linux-amd64.vsix)&nbsp;&nbsp;|&nbsp;&nbsp;[SHA-256 checksum](https://download-cdn.jetbrains.com/language-server/kotlin-server/262.9593.0/kotlin-server-0.0.6-linux-amd64.vsix.sha256)
    * [Download for Linux-arm64](https://download-cdn.jetbrains.com/language-server/kotlin-server/262.9593.0/kotlin-server-0.0.6-linux-aarch64.vsix)&nbsp;&nbsp;|&nbsp;&nbsp;[SHA-256 checksum](https://download-cdn.jetbrains.com/language-server/kotlin-server/262.9593.0/kotlin-server-0.0.6-linux-aarch64.vsix.sha256)
    * [Download for Windows-x64](https://download-cdn.jetbrains.com/language-server/kotlin-server/262.9593.0/kotlin-server-0.0.6-win-amd64.vsix)&nbsp;&nbsp;|&nbsp;&nbsp;[SHA-256 checksum](https://download-cdn.jetbrains.com/language-server/kotlin-server/262.9593.0/kotlin-server-0.0.6-win-amd64.vsix.sha256)
    * [Download for Windows-arm64](https://download-cdn.jetbrains.com/language-server/kotlin-server/262.9593.0/kotlin-server-0.0.6-win-aarch64.vsix)&nbsp;&nbsp;|&nbsp;&nbsp;[SHA-256 checksum](https://download-cdn.jetbrains.com/language-server/kotlin-server/262.9593.0/kotlin-server-0.0.6-win-aarch64.vsix.sha256)


- :card_index_dividers: **Standalone Kotlin LSP Archive**

  Standalone Kotlin Language Server version for editors other than VS Code.

    * [Download for macOS-x64](https://download-cdn.jetbrains.com/language-server/kotlin-server/262.9593.0/kotlin-server-262.9593.0.sit)&nbsp;&nbsp;|&nbsp;&nbsp;[SHA-256 checksum](https://download-cdn.jetbrains.com/language-server/kotlin-server/262.9593.0/kotlin-server-262.9593.0.sit.sha256)
    * [Download for macOS-arm64](https://download-cdn.jetbrains.com/language-server/kotlin-server/262.9593.0/kotlin-server-262.9593.0-aarch64.sit)&nbsp;&nbsp;|&nbsp;&nbsp;[SHA-256 checksum](https://download-cdn.jetbrains.com/language-server/kotlin-server/262.9593.0/kotlin-server-262.9593.0-aarch64.sit.sha256)
    * [Download for Linux-x64](https://download-cdn.jetbrains.com/language-server/kotlin-server/262.9593.0/kotlin-server-262.9593.0.tar.gz)&nbsp;&nbsp;|&nbsp;&nbsp;[SHA-256 checksum](https://download-cdn.jetbrains.com/language-server/kotlin-server/262.9593.0/kotlin-server-262.9593.0.tar.gz.sha256)
    * [Download for Linux-arm64](https://download-cdn.jetbrains.com/language-server/kotlin-server/262.9593.0/kotlin-server-262.9593.0-aarch64.tar.gz)&nbsp;&nbsp;|&nbsp;&nbsp;[SHA-256 checksum](https://download-cdn.jetbrains.com/language-server/kotlin-server/262.9593.0/kotlin-server-262.9593.0-aarch64.tar.gz.sha256)
    * [Download for Windows-x64](https://download-cdn.jetbrains.com/language-server/kotlin-server/262.9593.0/kotlin-server-262.9593.0.win.zip)&nbsp;&nbsp;|&nbsp;&nbsp;[SHA-256 checksum](https://download-cdn.jetbrains.com/language-server/kotlin-server/262.9593.0/kotlin-server-262.9593.0.win.zip.sha256)
    * [Download for Windows-arm64](https://download-cdn.jetbrains.com/language-server/kotlin-server/262.9593.0/kotlin-server-262.9593.0-aarch64.win.zip)&nbsp;&nbsp;|&nbsp;&nbsp;[SHA-256 checksum](https://download-cdn.jetbrains.com/language-server/kotlin-server/262.9593.0/kotlin-server-262.9593.0-aarch64.win.zip.sha256)


##### Changelog

> [!NOTE]
> This is a minor release based on [v262.8190.0](https://github.com/Kotlin/kotlin-lsp/releases/tag/kotlin-lsp%2Fv262.8190.0). See its changelog for the recent changes.

### v262.8190.0
- :test_tube: "Kotlin by JetBrains" extension v0.0.5 for VS Code

  Includes Kotlin Language Server bundled for use with Visual Studio Code.

  The extension is also available on the [VS Code Marketplace](https://marketplace.visualstudio.com/items?itemName=JetBrains.kotlin-server).
    * [Download for macOS-x64](https://download-cdn.jetbrains.com/language-server/kotlin-server/262.8190.0/kotlin-server-0.0.5-mac-amd64.vsix)&nbsp;&nbsp;|&nbsp;&nbsp;[SHA-256 checksum](https://download-cdn.jetbrains.com/language-server/kotlin-server/262.8190.0/kotlin-server-0.0.5-mac-amd64.vsix.sha256)
    * [Download for macOS-arm64](https://download-cdn.jetbrains.com/language-server/kotlin-server/262.8190.0/kotlin-server-0.0.5-mac-aarch64.vsix)&nbsp;&nbsp;|&nbsp;&nbsp;[SHA-256 checksum](https://download-cdn.jetbrains.com/language-server/kotlin-server/262.8190.0/kotlin-server-0.0.5-mac-aarch64.vsix.sha256)
    * [Download for Linux-x64](https://download-cdn.jetbrains.com/language-server/kotlin-server/262.8190.0/kotlin-server-0.0.5-linux-amd64.vsix)&nbsp;&nbsp;|&nbsp;&nbsp;[SHA-256 checksum](https://download-cdn.jetbrains.com/language-server/kotlin-server/262.8190.0/kotlin-server-0.0.5-linux-amd64.vsix.sha256)
    * [Download for Linux-arm64](https://download-cdn.jetbrains.com/language-server/kotlin-server/262.8190.0/kotlin-server-0.0.5-linux-aarch64.vsix)&nbsp;&nbsp;|&nbsp;&nbsp;[SHA-256 checksum](https://download-cdn.jetbrains.com/language-server/kotlin-server/262.8190.0/kotlin-server-0.0.5-linux-aarch64.vsix.sha256)
    * [Download for Windows-x64](https://download-cdn.jetbrains.com/language-server/kotlin-server/262.8190.0/kotlin-server-0.0.5-win-amd64.vsix)&nbsp;&nbsp;|&nbsp;&nbsp;[SHA-256 checksum](https://download-cdn.jetbrains.com/language-server/kotlin-server/262.8190.0/kotlin-server-0.0.5-win-amd64.vsix.sha256)
    * [Download for Windows-arm64](https://download-cdn.jetbrains.com/language-server/kotlin-server/262.8190.0/kotlin-server-0.0.5-win-aarch64.vsix)&nbsp;&nbsp;|&nbsp;&nbsp;[SHA-256 checksum](https://download-cdn.jetbrains.com/language-server/kotlin-server/262.8190.0/kotlin-server-0.0.5-win-aarch64.vsix.sha256)


- :card_index_dividers: **Standalone Kotlin LSP Archive**

  Standalone Kotlin Language Server version for editors other than VS Code.

    * [Download for macOS-x64](https://download-cdn.jetbrains.com/language-server/kotlin-server/262.8190.0/kotlin-server-262.8190.0.sit)&nbsp;&nbsp;|&nbsp;&nbsp;[SHA-256 checksum](https://download-cdn.jetbrains.com/language-server/kotlin-server/262.8190.0/kotlin-server-262.8190.0.sit.sha256)
    * [Download for macOS-arm64](https://download-cdn.jetbrains.com/language-server/kotlin-server/262.8190.0/kotlin-server-262.8190.0-aarch64.sit)&nbsp;&nbsp;|&nbsp;&nbsp;[SHA-256 checksum](https://download-cdn.jetbrains.com/language-server/kotlin-server/262.8190.0/kotlin-server-262.8190.0-aarch64.sit.sha256)
    * [Download for Linux-x64](https://download-cdn.jetbrains.com/language-server/kotlin-server/262.8190.0/kotlin-server-262.8190.0.tar.gz)&nbsp;&nbsp;|&nbsp;&nbsp;[SHA-256 checksum](https://download-cdn.jetbrains.com/language-server/kotlin-server/262.8190.0/kotlin-server-262.8190.0.tar.gz.sha256)
    * [Download for Linux-arm64](https://download-cdn.jetbrains.com/language-server/kotlin-server/262.8190.0/kotlin-server-262.8190.0-aarch64.tar.gz)&nbsp;&nbsp;|&nbsp;&nbsp;[SHA-256 checksum](https://download-cdn.jetbrains.com/language-server/kotlin-server/262.8190.0/kotlin-server-262.8190.0-aarch64.tar.gz.sha256)
    * [Download for Windows-x64](https://download-cdn.jetbrains.com/language-server/kotlin-server/262.8190.0/kotlin-server-262.8190.0.win.zip)&nbsp;&nbsp;|&nbsp;&nbsp;[SHA-256 checksum](https://download-cdn.jetbrains.com/language-server/kotlin-server/262.8190.0/kotlin-server-262.8190.0.win.zip.sha256)
    * [Download for Windows-arm64](https://download-cdn.jetbrains.com/language-server/kotlin-server/262.8190.0/kotlin-server-262.8190.0-aarch64.win.zip)&nbsp;&nbsp;|&nbsp;&nbsp;[SHA-256 checksum](https://download-cdn.jetbrains.com/language-server/kotlin-server/262.8190.0/kotlin-server-262.8190.0-aarch64.win.zip.sha256)

##### Changelog

> [!NOTE]
> This is a minor release based on [v262.7569.0](https://github.com/Kotlin/kotlin-lsp/releases/edit/kotlin-lsp%2Fv262.7569.0). See it's changelog for the recent changes.

#### 🐛 Bug fixes

- Fixed an issue with the extension importing nested projects too eagerly, resulting in huge workspace caches. Fixes <https://github.com/Kotlin/kotlin-lsp/issues/213>. <!-- LSP-1288 -->

### v262.7569.0
- :test_tube: **"Kotlin by JetBrains" extension v0.0.4 for VS Code **

  Includes Kotlin Language Server bundled for use with Visual Studio Code. 
  
  The extension is also available on the [VS Code Marketplace](https://marketplace.visualstudio.com/items?itemName=JetBrains.kotlin-server).
    * [Download for macOS-x64](https://download-cdn.jetbrains.com/kotlin-lsp/262.7569.0/kotlin-server-0.0.4-mac-amd64.vsix)&nbsp;&nbsp;|&nbsp;&nbsp;[SHA-256 checksum](https://download-cdn.jetbrains.com/kotlin-lsp/262.7569.0/kotlin-server-0.0.4-mac-amd64.vsix.sha256)
    * [Download for macOS-arm64](https://download-cdn.jetbrains.com/kotlin-lsp/262.7569.0/kotlin-server-0.0.4-mac-aarch64.vsix)&nbsp;&nbsp;|&nbsp;&nbsp;[SHA-256 checksum](https://download-cdn.jetbrains.com/kotlin-lsp/262.7569.0/kotlin-server-0.0.4-mac-aarch64.vsix.sha256)
    * [Download for Linux-x64](https://download-cdn.jetbrains.com/kotlin-lsp/262.7569.0/kotlin-server-0.0.4-linux-amd64.vsix)&nbsp;&nbsp;|&nbsp;&nbsp;[SHA-256 checksum](https://download-cdn.jetbrains.com/kotlin-lsp/262.7569.0/kotlin-server-0.0.4-linux-amd64.vsix.sha256)
    * [Download for Linux-arm64](https://download-cdn.jetbrains.com/kotlin-lsp/262.7569.0/kotlin-server-0.0.4-linux-aarch64.vsix)&nbsp;&nbsp;|&nbsp;&nbsp;[SHA-256 checksum](https://download-cdn.jetbrains.com/kotlin-lsp/262.7569.0/kotlin-server-0.0.4-linux-aarch64.vsix.sha256)
    * [Download for Windows-x64](https://download-cdn.jetbrains.com/kotlin-lsp/262.7569.0/kotlin-server-0.0.4-win-amd64.vsix)&nbsp;&nbsp;|&nbsp;&nbsp;[SHA-256 checksum](https://download-cdn.jetbrains.com/kotlin-lsp/262.7569.0/kotlin-server-0.0.4-win-amd64.vsix.sha256)
    * [Download for Windows-arm64](https://download-cdn.jetbrains.com/kotlin-lsp/262.7569.0/kotlin-server-0.0.4-win-aarch64.vsix)&nbsp;&nbsp;|&nbsp;&nbsp;[SHA-256 checksum](https://download-cdn.jetbrains.com/kotlin-lsp/262.7569.0/kotlin-server-0.0.4-win-aarch64.vsix.sha256)


- :card_index_dividers: **Standalone Kotlin LSP Archive**

  Standalone Kotlin Language Server version for editors other than VS Code.
    * [Download for macOS-x64](https://download-cdn.jetbrains.com/kotlin-lsp/262.7569.0/kotlin-server-262.7569.0.sit)&nbsp;&nbsp;|&nbsp;&nbsp;[SHA-256 checksum](https://download-cdn.jetbrains.com/kotlin-lsp/262.7569.0/kotlin-server-262.7569.0.sit.sha256)
    * [Download for macOS-arm64](https://download-cdn.jetbrains.com/kotlin-lsp/262.7569.0/kotlin-server-262.7569.0-aarch64.sit)&nbsp;&nbsp;|&nbsp;&nbsp;[SHA-256 checksum](https://download-cdn.jetbrains.com/kotlin-lsp/262.7569.0/kotlin-server-262.7569.0-aarch64.sit.sha256)
    * [Download for Linux-x64](https://download-cdn.jetbrains.com/kotlin-lsp/262.7569.0/kotlin-server-262.7569.0.tar.gz)&nbsp;&nbsp;|&nbsp;&nbsp;[SHA-256 checksum](https://download-cdn.jetbrains.com/kotlin-lsp/262.7569.0/kotlin-server-262.7569.0.tar.gz.sha256)
    * [Download for Linux-arm64](https://download-cdn.jetbrains.com/kotlin-lsp/262.7569.0/kotlin-server-262.7569.0-aarch64.tar.gz)&nbsp;&nbsp;|&nbsp;&nbsp;[SHA-256 checksum](https://download-cdn.jetbrains.com/kotlin-lsp/262.7569.0/kotlin-server-262.7569.0-aarch64.tar.gz.sha256)
    * [Download for Windows-x64](https://download-cdn.jetbrains.com/kotlin-lsp/262.7569.0/kotlin-server-262.7569.0.win.zip)&nbsp;&nbsp;|&nbsp;&nbsp;[SHA-256 checksum](https://download-cdn.jetbrains.com/kotlin-lsp/262.7569.0/kotlin-server-262.7569.0.win.zip.sha256)
    * [Download for Windows-arm64](https://download-cdn.jetbrains.com/kotlin-lsp/262.7569.0/kotlin-server-262.7569.0-aarch64.win.zip)&nbsp;&nbsp;|&nbsp;&nbsp;[SHA-256 checksum](https://download-cdn.jetbrains.com/kotlin-lsp/262.7569.0/kotlin-server-262.7569.0-aarch64.win.zip.sha256)

##### Changelog

#### 🎉 Extension is now published to the VS Code Marketplace

* "Kotlin by JetBrains" is now available on the [Visual Studio Code Marketplace](https://marketplace.visualstudio.com/items?itemName=JetBrains.kotlin-server), and can be installed to VS Code directly.
* VS Code extension is now distributed under the [JetBrains Free Plugin License](LICENSE.txt) instead of Apache 2.0. <!-- LSP-947 -->

> [!WARNING]
> "Kotlin by JetBrains" extension now has a different VS Code Marketplace ID:
>
> Old one: `jetbrains.kotlin`
>
> New one: `jetbrains.kotlin-server`
>
> When you install a newer `jetbrains.kotlin-server`, a dialog will offer to uninstall the older `jetbrains.kotlin` extension, if present.
>  <details>
>  <summary>Example of the dialog.</summary>
>  <img width="263" height="292" alt="image" src="https://gist.github.com/user-attachments/assets/d909ceef-e1d8-4709-87eb-290eb5334e14" />
>  </details>
>
> Accept it and reload the window — the new extension cannot properly activate while the old `jetbrains.kotlin`
> extension is present.

#### 🛠 LSP capabilities

* Workspace model is now persisted across server restarts, which reduces cold-start time on subsequent launches of the same project. <!-- LSP-198 -->
* New inspections from the IntelliJ Kotlin Plugin:
    * [KTIJ-20597](https://youtrack.jetbrains.com/issue/KTIJ-20597): Replace `myMap.map { it.ket }.toSet()` chains with direct call on `myMap.keys`.

#### 📦 Import & build systems

* More fine-grained progress reporting during project import in VS Code. <!-- LSP-948 -->
* The Kotlin LSP document selector now also covers `.java` files in mixed Kotlin/Java projects, improving cross-language updates when Java files change. <!-- LSP-1053 -->

#### ⌨️ Editor / typing

* Various Enter-key and auto-indent handling fixes — multi-line strings, multi-line comments, KDoc, class type parameter/argument lists, function parameter lists, and more. <!-- LSP-988, LSP-994, LSP-1060, LSP-1101, LSP-1103, LSP-1104, LSP-1105 -->
* Correct handling of CRLF line endings in document edits, bracket/parenthesis completion, and extra-line insertion. <!-- LSP-1043, LSP-1058, LSP-1082 -->
* Completion of multi-line comments. <!-- LSP-1086 -->

#### 🐛 Bug fixes

* Kotlin `languageVersion` is now propagated from Gradle and Maven into the imported workspace, matching IntelliJ behavior. <!-- LSP-1097 -->
* Gradle import no longer throws `NoSuchElementException` when no JDK is configured — a clearer error is surfaced instead. <!-- LSP-1147 -->
* False positive `REDUNDANT_ELSE_IN_WHEN` diagnostic is addressed. Fixes <https://github.com/Kotlin/kotlin-lsp/issues/190> and <https://github.com/Kotlin/kotlin-lsp/issues/124>. <!-- LSP-1035 -->
* Document URLs are now computed correctly for unsaved/orphan files. Potentially fixes <https://github.com/Kotlin/kotlin-lsp/issues/185>. <!-- LSP-1016 -->
* Hover documentation links now use properly rendered URIs, fixing broken links on Windows and for project paths that contain spaces. <!-- LSP-1074, LSP-1098 -->
* "Find References" of Kotlin type aliases is now supported. <!-- LSP-1075 -->
* `Format Document` now applies to files created from templates. <!-- LSP-928 -->
* `PROJECT_NAME` interpolation in file templates is now resolved correctly. <!-- LSP-998 -->
* Fixed the broken link to `file_templates.md` in extension settings. <!-- LSP-995 -->
* Many other small improvements and bug fixes.

#### Other

* :rocket: Completion and auto-import performance improvements, including a fix for the regression introduced by the RocksDB migration. Fixes <https://github.com/Kotlin/kotlin-lsp/issues/193>. <!-- LSP-893, LSP-1041, LSP-1064, LSP-1081 -->
* The server now reports its log file location to the client on startup. <!-- LSP-1022 -->
* Background coroutine exceptions are now captured and reported properly in the server log. <!-- LSP-1021 -->
* `.editorconfig` files outside source roots are now honored. <!-- LSP-1070 -->
* Fix for serialization of empty/optional values when handling LSP requests. Related to <https://github.com/Kotlin/kotlin-lsp/issues/104>. <!-- LSP-1093 -->

### v262.4739.0
- :test_tube: **Kotlin LSP for VS Code Extension**
  Includes Kotlin Language Server bundled for use with Visual Studio Code
    * [Download for macOS-x64](https://download-cdn.jetbrains.com/kotlin-lsp/262.4739.0/kotlin-server-262.4739.0-mac-amd64.vsix)&nbsp;&nbsp;|&nbsp;&nbsp;[SHA-256 checksum](https://download-cdn.jetbrains.com/kotlin-lsp/262.4739.0/kotlin-server-262.4739.0-mac-amd64.vsix.sha256)
    * [Download for macOS-arm64](https://download-cdn.jetbrains.com/kotlin-lsp/262.4739.0/kotlin-server-262.4739.0-mac-aarch64.vsix)&nbsp;&nbsp;|&nbsp;&nbsp;[SHA-256 checksum](https://download-cdn.jetbrains.com/kotlin-lsp/262.4739.0/kotlin-server-262.4739.0-mac-aarch64.vsix.sha256)
    * [Download for Linux-x64](https://download-cdn.jetbrains.com/kotlin-lsp/262.4739.0/kotlin-server-262.4739.0-linux-amd64.vsix)&nbsp;&nbsp;|&nbsp;&nbsp;[SHA-256 checksum](https://download-cdn.jetbrains.com/kotlin-lsp/262.4739.0/kotlin-server-262.4739.0-linux-amd64.vsix.sha256)
    * [Download for Linux-arm64](https://download-cdn.jetbrains.com/kotlin-lsp/262.4739.0/kotlin-server-262.4739.0-linux-aarch64.vsix)&nbsp;&nbsp;|&nbsp;&nbsp;[SHA-256 checksum](https://download-cdn.jetbrains.com/kotlin-lsp/262.4739.0/kotlin-server-262.4739.0-linux-aarch64.vsix.sha256)
    * [Download for Windows-x64](https://download-cdn.jetbrains.com/kotlin-lsp/262.4739.0/kotlin-server-262.4739.0-win-amd64.vsix)&nbsp;&nbsp;|&nbsp;&nbsp;[SHA-256 checksum](https://download-cdn.jetbrains.com/kotlin-lsp/262.4739.0/kotlin-server-262.4739.0-win-amd64.vsix.sha256)
    * [Download for Windows-arm64](https://download-cdn.jetbrains.com/kotlin-lsp/262.4739.0/kotlin-server-262.4739.0-win-aarch64.vsix)&nbsp;&nbsp;|&nbsp;&nbsp;[SHA-256 checksum](https://download-cdn.jetbrains.com/kotlin-lsp/262.4739.0/kotlin-server-262.4739.0-win-aarch64.vsix.sha256)


- :card_index_dividers: **Standalone Kotlin LSP Archive**
  Standalone Kotlin Language Server version for editors other than VS Code
    * [Download for macOS-x64](https://download-cdn.jetbrains.com/kotlin-lsp/262.4739.0/kotlin-server-262.4739.0.sit)&nbsp;&nbsp;|&nbsp;&nbsp;[SHA-256 checksum](https://download-cdn.jetbrains.com/kotlin-lsp/262.4739.0/kotlin-server-262.4739.0.sit.sha256)
    * [Download for macOS-arm64](https://download-cdn.jetbrains.com/kotlin-lsp/262.4739.0/kotlin-server-262.4739.0-aarch64.sit)&nbsp;&nbsp;|&nbsp;&nbsp;[SHA-256 checksum](https://download-cdn.jetbrains.com/kotlin-lsp/262.4739.0/kotlin-server-262.4739.0-aarch64.sit.sha256)
    * [Download for Linux-x64](https://download-cdn.jetbrains.com/kotlin-lsp/262.4739.0/kotlin-server-262.4739.0.tar.gz)&nbsp;&nbsp;|&nbsp;&nbsp;[SHA-256 checksum](https://download-cdn.jetbrains.com/kotlin-lsp/262.4739.0/kotlin-server-262.4739.0.tar.gz.sha256)
    * [Download for Linux-arm64](https://download-cdn.jetbrains.com/kotlin-lsp/262.4739.0/kotlin-server-262.4739.0-aarch64.tar.gz)&nbsp;&nbsp;|&nbsp;&nbsp;[SHA-256 checksum](https://download-cdn.jetbrains.com/kotlin-lsp/262.4739.0/kotlin-server-262.4739.0-aarch64.tar.gz.sha256)
    * [Download for Windows-x64](https://download-cdn.jetbrains.com/kotlin-lsp/262.4739.0/kotlin-server-262.4739.0.win.zip)&nbsp;&nbsp;|&nbsp;&nbsp;[SHA-256 checksum](https://download-cdn.jetbrains.com/kotlin-lsp/262.4739.0/kotlin-server-262.4739.0.win.zip.sha256)
    * [Download for Windows-arm64](https://download-cdn.jetbrains.com/kotlin-lsp/262.4739.0/kotlin-server-262.4739.0-aarch64.win.zip)&nbsp;&nbsp;|&nbsp;&nbsp;[SHA-256 checksum](https://download-cdn.jetbrains.com/kotlin-lsp/262.4739.0/kotlin-server-262.4739.0-aarch64.win.zip.sha256)

##### Changelog

#### 🛠 LSP capabilities

* Call hierarchy (`textDocument/prepareCallHierarchy`, `callHierarchy/incomingCalls`, `callHierarchy/outgoingCalls`) — invoke "Show Call Hierarchy" / "Show Incoming/Outgoing Calls" on a Kotlin function or property to see who calls it and which symbols it calls. Fixes <https://github.com/Kotlin/kotlin-lsp/issues/143>. <!-- LSP-487 -->
* Code folding (`textDocument/foldingRange`) — Kotlin function and class bodies, blocks, imports, and multiline comments can now be collapsed in the editor. <!-- LSP-655 -->
* Smart insertion of parentheses, braces, and quotes — auto-pairing and overtyping work for KDoc brackets, string templates, raw strings, generic angle brackets, `when` / lambda braces, and char literals. <!-- LSP-283 -->
* File templates (IntelliJ-style) — newly created Kotlin files are generated from configurable templates that support predefined variables and conditional expressions. Templates are configured through VS Code settings. <!-- LSP-814 -->

#### 🐛 Bug fixes

* `override` completion no longer throws an exception on methods that carry annotations. Fixes <https://github.com/Kotlin/kotlin-lsp/issues/160>. <!-- LSP-798 -->
* Kotlin compiler settings (including compiler plugins like Compose) are now correctly computed for non-standard Gradle source sets — i.e., anything beyond `main` and `test`. Fixes <https://github.com/Kotlin/kotlin-lsp/issues/169>. <!-- LSP-835 -->
* Cross-language references in mixed Kotlin/Java projects with non-standard Gradle source sets are now resolved correctly. Fixes <https://github.com/Kotlin/kotlin-lsp/issues/166>.

#### :test_tube: Experimental features

> [!WARNING]
> The features listed in this section are not finalized.
>
> They may contain bugs and are likely to change significantly in future releases — do not depend on their current behavior.

* Import of Android projects is now supported by Kotlin LSP :tada: <!-- LSP-842 -->
* :bug: Debug Adapter Protocol (DAP) for Kotlin — attach to a running JVM, set line breakpoints, pause/resume, step over/in, inspect threads, stack frames, and variables, and evaluate simple expressions. <!-- LSP-422 -->

#### Other

* :rocket: Index storage migrated to RocksDB — more robust state management and better performance. <!-- LSP-662 -->
* VS Code extension settings have been renamed from `kotlinLSP.*` to `intellij.*`. <!-- LSP-807 -->
* New `intellij.buildTool` setting controls which build-system importer should be preferred. <!-- LSP-807 -->
* :package: New bundling layout — use the `bin/intellij-server` executable to launch the standalone server. The legacy `kotlin-lsp.sh` launcher is deprecated and will be removed in future releases. <!-- LSP-884 -->
* Standalone archives are now platform-specific: `.sit` for macOS, `.tar.gz` for Linux, and `.zip` for Windows. <!-- LSP-884 -->
* `stdio` mode stability — the JVM's own stdout is now isolated from the LSP framing channel, so unexpected output from the JVM no longer corrupts the protocol stream. <!-- LSP-817 -->
* :warning: Kotlin LSP now requires JDK 25 to run. <!-- IJPL-221307 -->

### v262.2310.0
- :test_tube: **Kotlin LSP for VS Code Extension**
  Includes Kotlin Language Server bundled for use with Visual Studio Code
    * [Download for macOS-x64](https://download-cdn.jetbrains.com/kotlin-lsp/262.2310.0/kotlin-lsp-262.2310.0-mac-x64.vsix)&nbsp;&nbsp;|&nbsp;&nbsp;[SHA-256 checksum](https://download-cdn.jetbrains.com/kotlin-lsp/262.2310.0/kotlin-lsp-262.2310.0-mac-x64.vsix.sha256)
    * [Download for macOS-arm64](https://download-cdn.jetbrains.com/kotlin-lsp/262.2310.0/kotlin-lsp-262.2310.0-mac-aarch64.vsix)&nbsp;&nbsp;|&nbsp;&nbsp;[SHA-256 checksum](https://download-cdn.jetbrains.com/kotlin-lsp/262.2310.0/kotlin-lsp-262.2310.0-mac-aarch64.vsix.sha256)
    * [Download for Linux-x64](https://download-cdn.jetbrains.com/kotlin-lsp/262.2310.0/kotlin-lsp-262.2310.0-linux-x64.vsix)&nbsp;&nbsp;|&nbsp;&nbsp;[SHA-256 checksum](https://download-cdn.jetbrains.com/kotlin-lsp/262.2310.0/kotlin-lsp-262.2310.0-linux-x64.vsix.sha256)
    * [Download for Linux-arm64](https://download-cdn.jetbrains.com/kotlin-lsp/262.2310.0/kotlin-lsp-262.2310.0-linux-aarch64.vsix)&nbsp;&nbsp;|&nbsp;&nbsp;[SHA-256 checksum](https://download-cdn.jetbrains.com/kotlin-lsp/262.2310.0/kotlin-lsp-262.2310.0-linux-aarch64.vsix.sha256)
    * [Download for Windows-x64](https://download-cdn.jetbrains.com/kotlin-lsp/262.2310.0/kotlin-lsp-262.2310.0-win-x64.vsix)&nbsp;&nbsp;|&nbsp;&nbsp;[SHA-256 checksum](https://download-cdn.jetbrains.com/kotlin-lsp/262.2310.0/kotlin-lsp-262.2310.0-win-x64.vsix.sha256)
    * [Download for Windows-arm64](https://download-cdn.jetbrains.com/kotlin-lsp/262.2310.0/kotlin-lsp-262.2310.0-win-aarch64.vsix)&nbsp;&nbsp;|&nbsp;&nbsp;[SHA-256 checksum](https://download-cdn.jetbrains.com/kotlin-lsp/262.2310.0/kotlin-lsp-262.2310.0-win-aarch64.vsix.sha256)


- :card_index_dividers: **Standalone Kotlin LSP ZIP Archive**
  Standalone Kotlin Language Server version for editors other than VS Code
    * [Download for macOS-x64](https://download-cdn.jetbrains.com/kotlin-lsp/262.2310.0/kotlin-lsp-262.2310.0-mac-x64.zip)&nbsp;&nbsp;|&nbsp;&nbsp;[SHA-256 checksum](https://download-cdn.jetbrains.com/kotlin-lsp/262.2310.0/kotlin-lsp-262.2310.0-mac-x64.zip.sha256)
    * [Download for macOS-arm64](https://download-cdn.jetbrains.com/kotlin-lsp/262.2310.0/kotlin-lsp-262.2310.0-mac-aarch64.zip)&nbsp;&nbsp;|&nbsp;&nbsp;[SHA-256 checksum](https://download-cdn.jetbrains.com/kotlin-lsp/262.2310.0/kotlin-lsp-262.2310.0-mac-aarch64.zip.sha256)
    * [Download for Linux-x64](https://download-cdn.jetbrains.com/kotlin-lsp/262.2310.0/kotlin-lsp-262.2310.0-linux-x64.zip)&nbsp;&nbsp;|&nbsp;&nbsp;[SHA-256 checksum](https://download-cdn.jetbrains.com/kotlin-lsp/262.2310.0/kotlin-lsp-262.2310.0-linux-x64.zip.sha256)
    * [Download for Linux-arm64](https://download-cdn.jetbrains.com/kotlin-lsp/262.2310.0/kotlin-lsp-262.2310.0-linux-aarch64.zip)&nbsp;&nbsp;|&nbsp;&nbsp;[SHA-256 checksum](https://download-cdn.jetbrains.com/kotlin-lsp/262.2310.0/kotlin-lsp-262.2310.0-linux-aarch64.zip.sha256)
    * [Download for Windows-x64](https://download-cdn.jetbrains.com/kotlin-lsp/262.2310.0/kotlin-lsp-262.2310.0-win-x64.zip)&nbsp;&nbsp;|&nbsp;&nbsp;[SHA-256 checksum](https://download-cdn.jetbrains.com/kotlin-lsp/262.2310.0/kotlin-lsp-262.2310.0-win-x64.zip.sha256)
    * [Download for Windows-arm64](https://download-cdn.jetbrains.com/kotlin-lsp/262.2310.0/kotlin-lsp-262.2310.0-win-aarch64.zip)&nbsp;&nbsp;|&nbsp;&nbsp;[SHA-256 checksum](https://download-cdn.jetbrains.com/kotlin-lsp/262.2310.0/kotlin-lsp-262.2310.0-win-aarch64.zip.sha256)

##### Changelog

> [!IMPORTANT]
> This is the second hotfix release for [v262.1668.0](https://github.com/Kotlin/kotlin-lsp/releases/edit/kotlin-lsp%2Fv262.1668.0).
>
> The second fix is related to disabling faulty parts of a JVM DAP adapter (which is currently a WIP).
> 
> The changelog below comes from the v262.1668.0 release and is repeated here for clarity.


#### 🔧 Kotlin 2.3.0 support

* [Kotlin 2.3.0](https://kotlinlang.org/docs/whatsnew23.html) is out and supported by Kotlin LSP :tada:

#### 🛠 LSP capabilities

* Import of Maven projects is now supported
* Import of Gradle projects is now more robust
* "Go to Type Definition" (`typeDefinition`) for Kotlin symbols
* "Go to Implementation" (`implementation`) for Kotlin symbols
* New code actions are supported:
    * "Add names to call arguments"
    * "Specify type explicitly"
    * "Add import" quick fixes for unresolved references
* New inspections from the IntelliJ Kotlin Plugin:
    * [KTIJ-32563](https://youtrack.jetbrains.com/issue/KTIJ-32563): Detects inefficient/redundant operations on `Flow` from `kotlinx.coroutines`
    * [KTIJ-35457](https://youtrack.jetbrains.com/issue/KTIJ-35457), [KTIJ-35456](https://youtrack.jetbrains.com/issue/KTIJ-35456): Inspections for migrating to [new experimental name-based destructuring](https://github.com/Kotlin/KEEP/blob/main/proposals/KEEP-0438-name-based-destructuring.md) (KEEP-0438)
    * [KTIJ-35642](https://youtrack.jetbrains.com/issue/KTIJ-35642): Suggests converting properties with getters to use explicit backing fields (Kotlin 2.0+)
* Compiler plugins like `kotlinx.serialization` and `AllOpen` are now fully supported

#### ✨ UX improvements

* "Go to Symbol" now works faster due to improved performance of "Workspace Symbols" requests
* Distribution size has been reduced by > 30% (from 600 MB down to 400 MB)
* Various other performance improvements

#### 🐛 Bug fixes

* Fixed caret misplacement and exceptions after invoking code completion
* Angular brackets are no longer highlighted as unmatched in the editor
* Various memory leaks fixed

### v262.1817.0
- :test_tube: **Kotlin LSP for VS Code Extension**
  Includes Kotlin Language Server bundled for use with Visual Studio Code
    * [Download for macOS-x64](https://download-cdn.jetbrains.com/kotlin-lsp/262.1817.0/kotlin-lsp-262.1817.0-mac-x64.vsix)&nbsp;&nbsp;|&nbsp;&nbsp;[SHA-256 checksum](https://download-cdn.jetbrains.com/kotlin-lsp/262.1817.0/kotlin-lsp-262.1817.0-mac-x64.vsix.sha256)
    * [Download for macOS-arm64](https://download-cdn.jetbrains.com/kotlin-lsp/262.1817.0/kotlin-lsp-262.1817.0-mac-aarch64.vsix)&nbsp;&nbsp;|&nbsp;&nbsp;[SHA-256 checksum](https://download-cdn.jetbrains.com/kotlin-lsp/262.1817.0/kotlin-lsp-262.1817.0-mac-aarch64.vsix.sha256)
    * [Download for Linux-x64](https://download-cdn.jetbrains.com/kotlin-lsp/262.1817.0/kotlin-lsp-262.1817.0-linux-x64.vsix)&nbsp;&nbsp;|&nbsp;&nbsp;[SHA-256 checksum](https://download-cdn.jetbrains.com/kotlin-lsp/262.1817.0/kotlin-lsp-262.1817.0-linux-x64.vsix.sha256)
    * [Download for Linux-arm64](https://download-cdn.jetbrains.com/kotlin-lsp/262.1817.0/kotlin-lsp-262.1817.0-linux-aarch64.vsix)&nbsp;&nbsp;|&nbsp;&nbsp;[SHA-256 checksum](https://download-cdn.jetbrains.com/kotlin-lsp/262.1817.0/kotlin-lsp-262.1817.0-linux-aarch64.vsix.sha256)
    * [Download for Windows-x64](https://download-cdn.jetbrains.com/kotlin-lsp/262.1817.0/kotlin-lsp-262.1817.0-win-x64.vsix)&nbsp;&nbsp;|&nbsp;&nbsp;[SHA-256 checksum](https://download-cdn.jetbrains.com/kotlin-lsp/262.1817.0/kotlin-lsp-262.1817.0-win-x64.vsix.sha256)
    * [Download for Windows-arm64](https://download-cdn.jetbrains.com/kotlin-lsp/262.1817.0/kotlin-lsp-262.1817.0-win-aarch64.vsix)&nbsp;&nbsp;|&nbsp;&nbsp;[SHA-256 checksum](https://download-cdn.jetbrains.com/kotlin-lsp/262.1817.0/kotlin-lsp-262.1817.0-win-aarch64.vsix.sha256)


- :card_index_dividers: **Standalone Kotlin LSP ZIP Archive**
  Standalone Kotlin Language Server version for editors other than VS Code
    * [Download for macOS-x64](https://download-cdn.jetbrains.com/kotlin-lsp/262.1817.0/kotlin-lsp-262.1817.0-mac-x64.zip)&nbsp;&nbsp;|&nbsp;&nbsp;[SHA-256 checksum](https://download-cdn.jetbrains.com/kotlin-lsp/262.1817.0/kotlin-lsp-262.1817.0-mac-x64.zip.sha256)
    * [Download for macOS-arm64](https://download-cdn.jetbrains.com/kotlin-lsp/262.1817.0/kotlin-lsp-262.1817.0-mac-aarch64.zip)&nbsp;&nbsp;|&nbsp;&nbsp;[SHA-256 checksum](https://download-cdn.jetbrains.com/kotlin-lsp/262.1817.0/kotlin-lsp-262.1817.0-mac-aarch64.zip.sha256)
    * [Download for Linux-x64](https://download-cdn.jetbrains.com/kotlin-lsp/262.1817.0/kotlin-lsp-262.1817.0-linux-x64.zip)&nbsp;&nbsp;|&nbsp;&nbsp;[SHA-256 checksum](https://download-cdn.jetbrains.com/kotlin-lsp/262.1817.0/kotlin-lsp-262.1817.0-linux-x64.zip.sha256)
    * [Download for Linux-arm64](https://download-cdn.jetbrains.com/kotlin-lsp/262.1817.0/kotlin-lsp-262.1817.0-linux-aarch64.zip)&nbsp;&nbsp;|&nbsp;&nbsp;[SHA-256 checksum](https://download-cdn.jetbrains.com/kotlin-lsp/262.1817.0/kotlin-lsp-262.1817.0-linux-aarch64.zip.sha256)
    * [Download for Windows-x64](https://download-cdn.jetbrains.com/kotlin-lsp/262.1817.0/kotlin-lsp-262.1817.0-win-x64.zip)&nbsp;&nbsp;|&nbsp;&nbsp;[SHA-256 checksum](https://download-cdn.jetbrains.com/kotlin-lsp/262.1817.0/kotlin-lsp-262.1817.0-win-x64.zip.sha256)
    * [Download for Windows-arm64](https://download-cdn.jetbrains.com/kotlin-lsp/262.1817.0/kotlin-lsp-262.1817.0-win-aarch64.zip)&nbsp;&nbsp;|&nbsp;&nbsp;[SHA-256 checksum](https://download-cdn.jetbrains.com/kotlin-lsp/262.1817.0/kotlin-lsp-262.1817.0-win-aarch64.zip.sha256)

##### Changelog

> [!IMPORTANT]
> This is a hotfix release for [v262.1668.0](https://github.com/Kotlin/kotlin-lsp/releases/edit/kotlin-lsp%2Fv262.1668.0).
>
> The fix is related to unexpected warnings being printed to STDIO at the start of the server.
> It disables them to avoid potential problems with different LSP clients.
>
> The changelog below comes from the v262.1668.0 release and is repeated here for clarity.


#### 🔧 Kotlin 2.3.0 support

* [Kotlin 2.3.0](https://kotlinlang.org/docs/whatsnew23.html) is out and supported by Kotlin LSP :tada:

#### 🛠 LSP capabilities

* Import of Maven projects is now supported
* Import of Gradle projects is now more robust
* "Go to Type Definition" (`typeDefinition`) for Kotlin symbols
* "Go to Implementation" (`implementation`) for Kotlin symbols
* New code actions are supported:
    * "Add names to call arguments"
    * "Specify type explicitly"
    * "Add import" quick fixes for unresolved references
* New inspections from the IntelliJ Kotlin Plugin:
    * [KTIJ-32563](https://youtrack.jetbrains.com/issue/KTIJ-32563): Detects inefficient/redundant operations on `Flow` from `kotlinx.coroutines`
    * [KTIJ-35457](https://youtrack.jetbrains.com/issue/KTIJ-35457), [KTIJ-35456](https://youtrack.jetbrains.com/issue/KTIJ-35456): Inspections for migrating to [new experimental name-based destructuring](https://github.com/Kotlin/KEEP/blob/main/proposals/KEEP-0438-name-based-destructuring.md) (KEEP-0438)
    * [KTIJ-35642](https://youtrack.jetbrains.com/issue/KTIJ-35642): Suggests converting properties with getters to use explicit backing fields (Kotlin 2.0+)
* Compiler plugins like `kotlinx.serialization` and `AllOpen` are now fully supported

#### ✨ UX improvements

* "Go to Symbol" now works faster due to improved performance of "Workspace Symbols" requests
* Distribution size has been reduced by > 30% (from 600 MB down to 400 MB)
* Various other performance improvements

#### 🐛 Bug fixes

* Fixed caret misplacement and exceptions after invoking code completion
* Angular brackets are no longer highlighted as unmatched in the editor
* Various memory leaks fixed

### v262.1668.0
- :test_tube: **Kotlin LSP for VS Code Extension**
  Includes Kotlin Language Server bundled for use with Visual Studio Code
    * [Download for macOS-x64](https://download-cdn.jetbrains.com/kotlin-lsp/262.1668.0/kotlin-lsp-262.1668.0-mac-x64.vsix)&nbsp;&nbsp;|&nbsp;&nbsp;[SHA-256 checksum](https://download-cdn.jetbrains.com/kotlin-lsp/262.1668.0/kotlin-lsp-262.1668.0-mac-x64.vsix.sha256)
    * [Download for macOS-arm64](https://download-cdn.jetbrains.com/kotlin-lsp/262.1668.0/kotlin-lsp-262.1668.0-mac-aarch64.vsix)&nbsp;&nbsp;|&nbsp;&nbsp;[SHA-256 checksum](https://download-cdn.jetbrains.com/kotlin-lsp/262.1668.0/kotlin-lsp-262.1668.0-mac-aarch64.vsix.sha256)
    * [Download for Linux-x64](https://download-cdn.jetbrains.com/kotlin-lsp/262.1668.0/kotlin-lsp-262.1668.0-linux-x64.vsix)&nbsp;&nbsp;|&nbsp;&nbsp;[SHA-256 checksum](https://download-cdn.jetbrains.com/kotlin-lsp/262.1668.0/kotlin-lsp-262.1668.0-linux-x64.vsix.sha256)
    * [Download for Linux-arm64](https://download-cdn.jetbrains.com/kotlin-lsp/262.1668.0/kotlin-lsp-262.1668.0-linux-aarch64.vsix)&nbsp;&nbsp;|&nbsp;&nbsp;[SHA-256 checksum](https://download-cdn.jetbrains.com/kotlin-lsp/262.1668.0/kotlin-lsp-262.1668.0-linux-aarch64.vsix.sha256)
    * [Download for Windows-x64](https://download-cdn.jetbrains.com/kotlin-lsp/262.1668.0/kotlin-lsp-262.1668.0-win-x64.vsix)&nbsp;&nbsp;|&nbsp;&nbsp;[SHA-256 checksum](https://download-cdn.jetbrains.com/kotlin-lsp/262.1668.0/kotlin-lsp-262.1668.0-win-x64.vsix.sha256)
    * [Download for Windows-arm64](https://download-cdn.jetbrains.com/kotlin-lsp/262.1668.0/kotlin-lsp-262.1668.0-win-aarch64.vsix)&nbsp;&nbsp;|&nbsp;&nbsp;[SHA-256 checksum](https://download-cdn.jetbrains.com/kotlin-lsp/262.1668.0/kotlin-lsp-262.1668.0-win-aarch64.vsix.sha256)


- :card_index_dividers: **Standalone Kotlin LSP ZIP Archive**
  Standalone Kotlin Language Server version for editors other than VS Code
    * [Download for macOS-x64](https://download-cdn.jetbrains.com/kotlin-lsp/262.1668.0/kotlin-lsp-262.1668.0-mac-x64.zip)&nbsp;&nbsp;|&nbsp;&nbsp;[SHA-256 checksum](https://download-cdn.jetbrains.com/kotlin-lsp/262.1668.0/kotlin-lsp-262.1668.0-mac-x64.zip.sha256)
    * [Download for macOS-arm64](https://download-cdn.jetbrains.com/kotlin-lsp/262.1668.0/kotlin-lsp-262.1668.0-mac-aarch64.zip)&nbsp;&nbsp;|&nbsp;&nbsp;[SHA-256 checksum](https://download-cdn.jetbrains.com/kotlin-lsp/262.1668.0/kotlin-lsp-262.1668.0-mac-aarch64.zip.sha256)
    * [Download for Linux-x64](https://download-cdn.jetbrains.com/kotlin-lsp/262.1668.0/kotlin-lsp-262.1668.0-linux-x64.zip)&nbsp;&nbsp;|&nbsp;&nbsp;[SHA-256 checksum](https://download-cdn.jetbrains.com/kotlin-lsp/262.1668.0/kotlin-lsp-262.1668.0-linux-x64.zip.sha256)
    * [Download for Linux-arm64](https://download-cdn.jetbrains.com/kotlin-lsp/262.1668.0/kotlin-lsp-262.1668.0-linux-aarch64.zip)&nbsp;&nbsp;|&nbsp;&nbsp;[SHA-256 checksum](https://download-cdn.jetbrains.com/kotlin-lsp/262.1668.0/kotlin-lsp-262.1668.0-linux-aarch64.zip.sha256)
    * [Download for Windows-x64](https://download-cdn.jetbrains.com/kotlin-lsp/262.1668.0/kotlin-lsp-262.1668.0-win-x64.zip)&nbsp;&nbsp;|&nbsp;&nbsp;[SHA-256 checksum](https://download-cdn.jetbrains.com/kotlin-lsp/262.1668.0/kotlin-lsp-262.1668.0-win-x64.zip.sha256)
    * [Download for Windows-arm64](https://download-cdn.jetbrains.com/kotlin-lsp/262.1668.0/kotlin-lsp-262.1668.0-win-aarch64.zip)&nbsp;&nbsp;|&nbsp;&nbsp;[SHA-256 checksum](https://download-cdn.jetbrains.com/kotlin-lsp/262.1668.0/kotlin-lsp-262.1668.0-win-aarch64.zip.sha256)

##### Changelog

#### 🔧 Kotlin 2.3.0 support

* [Kotlin 2.3.0](https://kotlinlang.org/docs/whatsnew23.html) is out and supported by Kotlin LSP :tada:

#### 🛠 LSP capabilities

* Import of Maven projects is now supported
* Import of Gradle projects is now more robust
* "Go to Type Definition" (`typeDefinition`) for Kotlin symbols
* "Go to Implementation" (`implementation`) for Kotlin symbols
* New code actions are supported:
    * "Add names to call arguments"
    * "Specify type explicitly"
    * "Add import" quick fixes for unresolved references
* New inspections from the IntelliJ Kotlin Plugin:
    * [KTIJ-32563](https://youtrack.jetbrains.com/issue/KTIJ-32563): Detects inefficient/redundant operations on `Flow` from `kotlinx.coroutines`
    * [KTIJ-35457](https://youtrack.jetbrains.com/issue/KTIJ-35457), [KTIJ-35456](https://youtrack.jetbrains.com/issue/KTIJ-35456): Inspections for migrating to [new experimental name-based destructuring](https://github.com/Kotlin/KEEP/blob/main/proposals/KEEP-0438-name-based-destructuring.md) (KEEP-0438)
    * [KTIJ-35642](https://youtrack.jetbrains.com/issue/KTIJ-35642): Suggests converting properties with getters to use explicit backing fields (Kotlin 2.0+)
* Compiler plugins like `kotlinx.serialization` and `AllOpen` are now fully supported

#### ✨ UX improvements

* "Go to Symbol" now works faster due to improved performance of "Workspace Symbols" requests
* Distribution size has been reduced by > 30% (from 600 MB down to 400 MB)
* Various other performance improvements

#### 🐛 Bug fixes

* Fixed caret misplacement and exceptions after invoking code completion 
* Angular brackets are no longer highlighted as unmatched in the editor
* Various memory leaks fixed

### v261.13587.0
- :test_tube: **Kotlin LSP for VS Code Extension**
  Includes Kotlin Language Server bundled for use with Visual Studio Code
  * [Download for macOS-x64](https://download-cdn.jetbrains.com/kotlin-lsp/261.13587.0/kotlin-lsp-261.13587.0-mac-x64.vsix)&nbsp;&nbsp;|&nbsp;&nbsp;[SHA-256 checksum](https://download-cdn.jetbrains.com/kotlin-lsp/261.13587.0/kotlin-lsp-261.13587.0-mac-x64.vsix.sha256)
  * [Download for macOS-arm64](https://download-cdn.jetbrains.com/kotlin-lsp/261.13587.0/kotlin-lsp-261.13587.0-mac-aarch64.vsix)&nbsp;&nbsp;|&nbsp;&nbsp;[SHA-256 checksum](https://download-cdn.jetbrains.com/kotlin-lsp/261.13587.0/kotlin-lsp-261.13587.0-mac-aarch64.vsix.sha256)
  * [Download for Linux-x64](https://download-cdn.jetbrains.com/kotlin-lsp/261.13587.0/kotlin-lsp-261.13587.0-linux-x64.vsix)&nbsp;&nbsp;|&nbsp;&nbsp;[SHA-256 checksum](https://download-cdn.jetbrains.com/kotlin-lsp/261.13587.0/kotlin-lsp-261.13587.0-linux-x64.vsix.sha256)
  * [Download for Linux-arm64](https://download-cdn.jetbrains.com/kotlin-lsp/261.13587.0/kotlin-lsp-261.13587.0-linux-aarch64.vsix)&nbsp;&nbsp;|&nbsp;&nbsp;[SHA-256 checksum](https://download-cdn.jetbrains.com/kotlin-lsp/261.13587.0/kotlin-lsp-261.13587.0-linux-aarch64.vsix.sha256)
  * [Download for Windows-x64](https://download-cdn.jetbrains.com/kotlin-lsp/261.13587.0/kotlin-lsp-261.13587.0-win-x64.vsix)&nbsp;&nbsp;|&nbsp;&nbsp;[SHA-256 checksum](https://download-cdn.jetbrains.com/kotlin-lsp/261.13587.0/kotlin-lsp-261.13587.0-win-x64.vsix.sha256)
  * [Download for Windows-arm64](https://download-cdn.jetbrains.com/kotlin-lsp/261.13587.0/kotlin-lsp-261.13587.0-win-aarch64.vsix)&nbsp;&nbsp;|&nbsp;&nbsp;[SHA-256 checksum](https://download-cdn.jetbrains.com/kotlin-lsp/261.13587.0/kotlin-lsp-261.13587.0-win-aarch64.vsix.sha256)

- :card_index_dividers: **Standalone Kotlin LSP ZIP Archive**
  Standalone Kotlin Language Server version for editors other than VS Code  
  * [Download for macOS-x64](https://download-cdn.jetbrains.com/kotlin-lsp/261.13587.0/kotlin-lsp-261.13587.0-mac-x64.zip)&nbsp;&nbsp;|&nbsp;&nbsp;[SHA-256 checksum](https://download-cdn.jetbrains.com/kotlin-lsp/261.13587.0/kotlin-lsp-261.13587.0-mac-x64.zip.sha256)
  * [Download for macOS-arm64](https://download-cdn.jetbrains.com/kotlin-lsp/261.13587.0/kotlin-lsp-261.13587.0-mac-aarch64.zip)&nbsp;&nbsp;|&nbsp;&nbsp;[SHA-256 checksum](https://download-cdn.jetbrains.com/kotlin-lsp/261.13587.0/kotlin-lsp-261.13587.0-mac-aarch64.zip.sha256)
  * [Download for Linux-x64](https://download-cdn.jetbrains.com/kotlin-lsp/261.13587.0/kotlin-lsp-261.13587.0-linux-x64.zip)&nbsp;&nbsp;|&nbsp;&nbsp;[SHA-256 checksum](https://download-cdn.jetbrains.com/kotlin-lsp/261.13587.0/kotlin-lsp-261.13587.0-linux-x64.zip.sha256)
  * [Download for Linux-arm64](https://download-cdn.jetbrains.com/kotlin-lsp/261.13587.0/kotlin-lsp-261.13587.0-linux-aarch64.zip)&nbsp;&nbsp;|&nbsp;&nbsp;[SHA-256 checksum](https://download-cdn.jetbrains.com/kotlin-lsp/261.13587.0/kotlin-lsp-261.13587.0-linux-aarch64.zip.sha256)
  * [Download for Windows-x64](https://download-cdn.jetbrains.com/kotlin-lsp/261.13587.0/kotlin-lsp-261.13587.0-win-x64.zip)&nbsp;&nbsp;|&nbsp;&nbsp;[SHA-256 checksum](https://download-cdn.jetbrains.com/kotlin-lsp/261.13587.0/kotlin-lsp-261.13587.0-win-x64.zip.sha256)
  * [Download for Windows-arm64](https://download-cdn.jetbrains.com/kotlin-lsp/261.13587.0/kotlin-lsp-261.13587.0-win-aarch64.zip)&nbsp;&nbsp;|&nbsp;&nbsp;[SHA-256 checksum](https://download-cdn.jetbrains.com/kotlin-lsp/261.13587.0/kotlin-lsp-261.13587.0-win-aarch64.zip.sha256)

##### Changelog

#### 🛠 LSP capabilities

* Full support of inlay hints with a fine-grained configuration via `jetbrains.kotlin.hints.*` LS settings

#### ✨ UX improvements

* Zero-dependencies platform-specific builds -- no JDK required by default, the language server bundles its own
* Code completion revamp: suggesting order is now on par with IJ and more relevant
* Code completion latency is ~30% better
* `kotlinLSP.jdkForSymbolResolution` option to specify JDK version that will be used as a dependency for symbol resolution
* LS now checks JDK/Gradle versions compatibility and fails gracefully in the case of incompatible changes
* Indicies are now stored in a dedicated folder and are properly shared between multiple projects and LS instances
* All inspections and intentions are now using `mod command` which a more robust approach for LSP-like protocols

#### Other

* More indexing fixes on Windows
* Smaller bundle size on every platform
* Improved Gradle import performance
* Better JDK selection for Gradle import when multiple options are present
* Native filewatcher lib is now signed on OS X in release builds
* Native filewatcher lib is now linked with `libgcc` statically
* Compiler plugins support for JPS and .json-based imports


### v0.253.10629
- :test_tube: **Kotlin for VS Code Extension**  
  Includes the Kotlin Language Server bundled for use with Visual Studio Code.  

   :x: Build is no longer available.

- :card_index_dividers: **Kotlin Language Server (Standalone ZIP)**  
  Standalone version of the Kotlin LSP for editors other than VS Code.  

   :x: Build is no longer available.


##### Changelog

#### 🛠 LSP capabilities
* Rename refactoring (`textDocument/rename`)
* Kotlin code formatting (`textDocument/formatting` and `textDocument/rangeFormatting`)
    * Auto-applied on quickfixes, configurable via LSP protocol, IntelliJ implementation
* Navigation to libraries/JDK sources (`textDocument/definition`)
* Documentation on hover (`textDocument/hover`)
* Signature help (`textDocument/signatureHelp`)
* Faster highlighting on large files (`textDocument/semanticTokens/range`)

#### ✨ UX improvements
* Native support of external file system changes (i.e. `git pull`)
* Multiple caching layers with on-disk persistence are added
    * Should drastically reduce memory pressure on large projects
* Full-blown code completion from IntelliJ IDEA
* More fine-tuned inspections and diagnostics set enabled by default
* Proper termination sequence of LSP process when the corresponding extension is closed

#### Other
* 🐛 Fixed some bugs here and there, introduced new ones
* 🧩 VSC extension bundling
* 🪟 Wrestled with `\` on Windows on multiple occasions. All on-disk persistence is hopefully platform-independent for now 

### v0.252.17811
- :test_tube: **Kotlin for VS Code Extension**  
  Includes the Kotlin Language Server bundled for use with Visual Studio Code.  

   :x: Build is no longer available.

- :card_index_dividers: **Kotlin Language Server (Standalone ZIP)**  
  Standalone version of the Kotlin LSP for editors other than VS Code.  

   :x: Build is no longer available.

### v0.252.16998
- :test_tube: **Kotlin for VS Code Extension**  
  Includes the Kotlin Language Server bundled for use with Visual Studio Code.  

   :x: Build is no longer available.

- :card_index_dividers: **Kotlin Language Server (Standalone ZIP)**  
  Standalone version of the Kotlin LSP for editors other than VS Code.  

   :x: Build is no longer available.

### v0.252.16938
- :test_tube: **Kotlin for VS Code Extension**  
  Includes the Kotlin Language Server bundled for use with Visual Studio Code.  

   :x: Build is no longer available.

- :card_index_dividers: **Kotlin Language Server (Standalone ZIP)**  
  Standalone version of the Kotlin LSP for editors other than VS Code.  

   :x: Build is no longer available.

### v0.252.16738

- :test_tube: **Kotlin for VS Code Extension**  
  Includes the Kotlin Language Server bundled for use with Visual Studio Code.  

   :x: Build is no longer available.

- :card_index_dividers: **Kotlin Language Server (Standalone ZIP)**  
  Standalone version of the Kotlin LSP for editors other than VS Code.  

   :x: Build is no longer available.
- 
### v0.252.14887

- **Kotlin for VS Code Extension**  
  Includes the Kotlin Language Server bundled for use with Visual Studio Code.  

   :x: Build is no longer available.

-  **Kotlin Language Server (Standalone ZIP)**  
  Standalone version of the Kotlin LSP for editors other than VS Code.  

   :x: Build is no longer available.
