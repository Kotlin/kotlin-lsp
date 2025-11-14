#!/usr/bin/env bash

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")"; pwd)"
OUT_DIR=$(realpath "$SCRIPT_DIR/../../../out")

BUILD_INTELLIJ_LSP=false
if [[ "$*" == *"--intellij"* ]]; then
    BUILD_INTELLIJ_LSP=true
fi

if [ "$BUILD_INTELLIJ_LSP" = true ]; then
    ARTIFACT_DIR="$OUT_DIR/jps-artifacts/language_server_intellij_lsp_zip"
    BUILD_DIR="$OUT_DIR/vscode-extension/language_server_intellij_lsp"
else
    ARTIFACT_DIR="$OUT_DIR/jps-artifacts/language_server_kotlin_lsp_zip"
    BUILD_DIR="$OUT_DIR/vscode-extension/language_server_kotlin_lsp"
fi

FIRST_ZIP="$(find "$ARTIFACT_DIR" -type f -name '*.zip' -print -quit)"
if [[ -z "$FIRST_ZIP" ]]; then
  echo "Error: no .zip found in $ARTIFACT_DIR" >&2
  echo "Make sure that you have built LSP zip by running org.jetbrains.ls.building.BuildLSP" >&2
  exit 1
fi

for zip in "$ARTIFACT_DIR"/*.zip; do
    echo -e "\033[32mProcessing: $zip\033[0m"
    artifact_filename="$(basename -- "$zip")"
    if [ "$BUILD_INTELLIJ_LSP" = true ]; then
        version_platform="${artifact_filename#intellij-lsp-}"
        basename="intellij-lsp"
    else
        version_platform="${artifact_filename#kotlin-lsp-}"
        basename="kotlin-lsp"
    fi
    version="${version_platform%%-*}"
    platform="${version_platform#"$version"-}"
    platform="${platform%.zip}"
    if [[ "$version" == *".SNAPSHOT" ]]; then
        version="${version%.SNAPSHOT}-SNAPSHOT"
    fi
    vsix_target_filename="$basename-$version-$platform.vsix"

    EXTENSION_DIR="$BUILD_DIR/vscode-$platform"
    if [[ -d "$EXTENSION_DIR" ]]; then
      rm -rf -- "$EXTENSION_DIR"
    fi
    mkdir -p "$EXTENSION_DIR"
    cp -R "$SCRIPT_DIR/../kotlin-vscode/.nvmrc" "$EXTENSION_DIR"
    cp -R "$SCRIPT_DIR/../kotlin-vscode/.vscodeignore" "$EXTENSION_DIR"
    cp -R "$SCRIPT_DIR/../kotlin-vscode/icons" "$EXTENSION_DIR"
    cp -R "$SCRIPT_DIR/../kotlin-vscode/"/*.js "$EXTENSION_DIR"
    cp -R "$SCRIPT_DIR/../kotlin-vscode/"/*.json "$EXTENSION_DIR"
    cp -R "$SCRIPT_DIR/../kotlin-vscode/LICENSE" "$EXTENSION_DIR"
    cp -R "$SCRIPT_DIR/../kotlin-vscode/src" "$EXTENSION_DIR"
    cp -R "$SCRIPT_DIR/../kotlin-vscode/syntaxes" "$EXTENSION_DIR"
    pushd "$EXTENSION_DIR" > /dev/null
    if [ "$BUILD_INTELLIJ_LSP" = true ]; then
        echo "Applying internal package-patch.json..."
        npm run apply-intellij
    fi
    export LSP_ZIP_PATH="$zip"
    echo "Running npm install and npx vsce package..."
    npm install
    npx --yes vsce package "$version" --out "$BUILD_DIR/$vsix_target_filename" --baseContentUrl=https://github.com/Kotlin/kotlin-lsp/tree/main/kotlin-vscode
    rm -rf "$EXTENSION_DIR"
    popd > /dev/null
done
