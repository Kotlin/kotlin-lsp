### Kotlin LSP and VSC extension releases

This file contains TeamCity auto-generated download links that are updated on a weekly basis.
These are pre-alpha builds that are built directly from `master` branch after the initial acceptance.

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
