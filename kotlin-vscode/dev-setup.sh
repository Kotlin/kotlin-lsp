#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
EXTENSION_DIR="${EXTENSION_DIR:-$SCRIPT_DIR/out/dev}"
BUNDLE_TYPE="${BUNDLE_TYPE:-kotlin-server}"

echo "Setting up dev build in $EXTENSION_DIR (BUNDLE_TYPE=$BUNDLE_TYPE)"

mkdir -p "$EXTENSION_DIR"

# Refresh static artifacts (cheap, always).
rm -f "$EXTENSION_DIR"/*.js "$EXTENSION_DIR"/tsconfig*.json
cp "$SCRIPT_DIR"/.nvmrc "$EXTENSION_DIR"/
cp "$SCRIPT_DIR"/.vscodeignore "$EXTENSION_DIR"/
cp "$SCRIPT_DIR"/*.js "$EXTENSION_DIR"/
cp "$SCRIPT_DIR"/package.json "$EXTENSION_DIR"/
cp "$SCRIPT_DIR"/tsconfig*.json "$EXTENSION_DIR"/

rm -rf "${EXTENSION_DIR:?}/icons" "${EXTENSION_DIR:?}/syntaxes"
cp -R "$SCRIPT_DIR/icons" "$EXTENSION_DIR/"
cp -R "$SCRIPT_DIR/syntaxes" "$EXTENSION_DIR/"

# Re-symlink src/ and testSources/ so webpack watch picks up edits to originals,
# and so files newly added to either tree become visible (for tests, too).
shopt -s dotglob nullglob
for dir in src testSources; do
    rm -rf "${EXTENSION_DIR:?}/$dir"
    mkdir -p "$EXTENSION_DIR/$dir"
    for entry in "$SCRIPT_DIR/$dir"/*; do
        ln -s "$entry" "$EXTENSION_DIR/$dir/$(basename "$entry")"
    done
done

cd "$EXTENSION_DIR"

# Patch package.json, overlay sources, and symlink the bundle-specific
# package-lock.json. With DEV_SYMLINK=1, npm-install writes through the symlink,
# so dep changes propagate back to the source-tracked lockfile automatically.
DEV_SYMLINK=1 BUNDLE_TYPE="$BUNDLE_TYPE" node apply-intellij.js

npm install --ignore-scripts

echo "Dev build ready: $EXTENSION_DIR"
