#!/usr/bin/env bash

set -euo pipefail

# Find an absolute path to this script
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# Check for the required parameters
: "${BUILD_DIR:?BUILD_DIR is required}"
: "${EXTENSION_DIR:?EXTENSION_DIR is required}"
: "${VSIX_TARGET_FILENAME:?VSIX_TARGET_FILENAME is required}"
: "${LSP_ZIP_PATH:?LSP_ZIP_PATH is required}"

APPLY_INTELLIJ_PATCH="${APPLY_INTELLIJ_PATCH:-false}"
VSCE_VERSION="${VSCE_VERSION:-}"

if [[ -z "$VSCE_VERSION" ]]; then
  echo "Error: --vsce-version is required" >&2
  exit 1
fi

if [[ ! -f "$LSP_ZIP_PATH" ]]; then
  echo "Error: LSP zip not found: $LSP_ZIP_PATH" >&2
  exit 1
fi

mkdir -p "$BUILD_DIR"

# Delete existing extension dir
if [[ -d "$EXTENSION_DIR" ]]; then
  rm -rf -- "$EXTENSION_DIR"
fi
mkdir -p "$EXTENSION_DIR"

# Copy extension sources to the temp directory
cp -R "$SCRIPT_DIR/.nvmrc" "$EXTENSION_DIR"
cp -R "$SCRIPT_DIR/.vscodeignore" "$EXTENSION_DIR"
cp -R "$SCRIPT_DIR/icons" "$EXTENSION_DIR"
cp -R "$SCRIPT_DIR/"*.js "$EXTENSION_DIR"
cp -R "$SCRIPT_DIR/"*.json "$EXTENSION_DIR"
cp -R "$SCRIPT_DIR/LICENSE" "$EXTENSION_DIR"
cp -R "$SCRIPT_DIR/src" "$EXTENSION_DIR"
cp -R "$SCRIPT_DIR/syntaxes" "$EXTENSION_DIR"

pushd "$EXTENSION_DIR" > /dev/null

# Patch package.json for intellij extension in the temp directory
if [[ "$APPLY_INTELLIJ_PATCH" == "true" ]]; then
  echo "Applying intellij package-patch.json..."
  npm run apply-intellij
fi

# Provide a path to LSP Server, so it will be unpacked during extension packaging
export LSP_ZIP_PATH

echo "Running npm install and npx vsce package..."
npm install
npx --yes vsce package "$VSCE_VERSION" \
  --out "$BUILD_DIR/$VSIX_TARGET_FILENAME" \
  --baseContentUrl=https://github.com/Kotlin/kotlin-lsp/tree/main/kotlin-vscode
popd > /dev/null

# Delete temporary extension directory
rm -rf "$EXTENSION_DIR"
