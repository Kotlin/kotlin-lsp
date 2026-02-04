#!/usr/bin/env bash

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")"; pwd)"
OUT_DIR=$(realpath "$SCRIPT_DIR/../../../out")
BUILD_EXTENSION_SCRIPT="$SCRIPT_DIR/buildExtension.sh"

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

if [[ ! -f "$BUILD_EXTENSION_SCRIPT" ]]; then
  echo "Error: buildExtension script not found: $BUILD_EXTENSION_SCRIPT" >&2
  exit 1
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
    export BUILD_DIR="$BUILD_DIR"
    export EXTENSION_DIR="$EXTENSION_DIR"
    export VSIX_TARGET_FILENAME="$vsix_target_filename"
    export VSCE_VERSION="$version"
    export LSP_ZIP_PATH="$zip"
    export APPLY_INTELLIJ_PATCH="$BUILD_INTELLIJ_LSP"

    bash "$BUILD_EXTENSION_SCRIPT"
done
