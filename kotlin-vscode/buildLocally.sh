#!/usr/bin/env bash

set -e

echo Make sure that you have built the language-server.kotlin-lsp.zip by running org.jetbrains.ls.building.BuildLSP.main

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

BUILD_INTELLIJ_LSP=false
if [[ "$*" == *"--intellij"* ]]; then
    BUILD_INTELLIJ_LSP=true
fi

if [ "$BUILD_INTELLIJ_LSP" = true ]; then
    RELATIVE_FILE_PATH="../../../out/jps-artifacts/language_server_intellij_lsp_zip/language-server.intellij-lsp.zip"
else
    RELATIVE_FILE_PATH="../../../out/jps-artifacts/language_server_kotlin_lsp_zip/language-server.kotlin-lsp.zip"
fi

LSP_ZIP_PATH="$(cd "$SCRIPT_DIR" && cd "$(dirname "$RELATIVE_FILE_PATH")" && echo "$(pwd)/$(basename "$RELATIVE_FILE_PATH")")"
export LSP_ZIP_PATH

if [ ! -e "$LSP_ZIP_PATH" ]; then
    echo "LSP zip file not exist. Exiting"
    exit 1
fi

echo "Using $LSP_ZIP_PATH"

USED_INTELLIJ_LSP_INTERNAL_PACKAGE_JSON_PATCH=false
BACKUP_PACKAGE_JSON="./package.json.backup"
BACKUP_PACKAGE_LOCK="./package-lock.json.backup"

if [ "$BUILD_INTELLIJ_LSP" = true ]; then
    JETBRAINS_PACKAGE_JSON_PATCH="../../intellij-vscode/package-patch.json"
    CURRENT_PACKAGE_JSON="./package.json"
    CURRENT_PACKAGE_LOCK="./package-lock.json"
    
    if [ -e "$JETBRAINS_PACKAGE_JSON_PATCH" ]; then
        echo "Backing up original files..."
        cp "$CURRENT_PACKAGE_JSON" "$BACKUP_PACKAGE_JSON"
        
        if [ -e "$CURRENT_PACKAGE_LOCK" ]; then
            cp "$CURRENT_PACKAGE_LOCK" "$BACKUP_PACKAGE_LOCK"
        fi
        
        echo "Applying internal package-patch.json..."
        npm run apply-intellij
        echo "package-patch.json applied successfully"
        USED_INTELLIJ_LSP_INTERNAL_PACKAGE_JSON_PATCH=true
    else
        echo "Warning: IntelliJ package.json not found at $JETBRAINS_PACKAGE_JSON_PATCH"
        exit 1
    fi
fi

restore_original() {
    if [ "$USED_INTELLIJ_LSP_INTERNAL_PACKAGE_JSON_PATCH" = true ]; then
        echo "Restoring original files..."
        
        if [ -e "$BACKUP_PACKAGE_JSON" ]; then
            mv "$BACKUP_PACKAGE_JSON" "./package.json"
            echo "Original package.json restored"
        fi
        
        if [ -e "$BACKUP_PACKAGE_LOCK" ]; then
            mv "$BACKUP_PACKAGE_LOCK" "./package-lock.json"
            echo "Original package-lock.json restored"
        fi
    fi
}

trap restore_original EXIT

npm install
npx --yes vsce package --baseContentUrl=https://github.com/Kotlin/kotlin-lsp/tree/main/kotlin-vscode