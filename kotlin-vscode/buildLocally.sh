#!/usr/bin/env bash

set -e

echo Make sure that you have built the language-server.kotlin-lsp.zip by running org.jetbrains.ls.building.BuildLSP.main

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
RELATIVE_FILE_PATH="../../../out/jps-artifacts/language_server_kotlin_lsp_zip/language-server.kotlin-lsp.zip"
LSP_ZIP_PATH="$(cd "$SCRIPT_DIR" && cd "$(dirname "$RELATIVE_FILE_PATH")" && echo "$(pwd)/$(basename "$RELATIVE_FILE_PATH")")"
export LSP_ZIP_PATH

if [ ! -e "$LSP_ZIP_PATH" ]; then
    echo "language-server.kotlin-lsp.zip not exist. Exiting"
    exit 1
fi

echo "Using $LSP_ZIP_PATH"

npx --yes vsce package --baseContentUrl=https://github.com/Kotlin/kotlin-lsp/tree/main/kotlin-vscode