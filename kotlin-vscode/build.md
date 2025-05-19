# How to build Kotlin VSC extension

## Building release vsix

1. Run [LSP build](../building/src/org/jetbrains/ls/building/BuildLSP.kt)
2. Set `export LSP_ZIP_PATH=../../out/jps-artifacts/language_server_lsp_zip/language-server.kotlin-lsp.zip`
3. `npx vsce package --baseContentUrl=https://github.com/Kotlin/kotlin-lsp/tree/main/kotlin-vscode`
4. The resulting `.vsix` file will be located in the current directory

## Development mode

1. Run 1-3 steps from the release steps
2. Run `npm install && npm run unpack-server`
3. Open this directory in VSC
4. Run extension (F5). LSP will start out of the box
5. [optional] If you want to easily debug LSP:
    1. Run [LSP Server](language-server/language-server.kotlin-lsp/src/com/jetbrains/ls/lsp/LspServer.kt)
    2. In the debug VSC, go to settings and specify LSP port (9999) in `kotlinLSP.dev.serverPort`
    3. Reload VSC window using command `Developer: Reload window`
