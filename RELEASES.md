### Kotlin LSP and VSC extension releases

This file contains TeamCity auto-generated download links that are updated on a weekly basis.
These are pre-alpha builds that are built directly from `master` branch after the initial acceptance.

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
