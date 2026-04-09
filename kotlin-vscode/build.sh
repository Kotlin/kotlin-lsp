#!/usr/bin/env bash

set -euo pipefail

start_ts=$(date +%s)

SCRIPT_DIR="$(cd "$(dirname "$0")"; pwd)"
OUT_DIR=$(realpath "$SCRIPT_DIR/../../../out")
BUILD_EXTENSION_SCRIPT="$SCRIPT_DIR/buildExtension.sh"

BUNDLE_TYPE=""
for arg in "$@"; do
    case "$arg" in
        --bundle-type=*) BUNDLE_TYPE="${arg#--bundle-type=}" ;;
    esac
done

: "${BUNDLE_TYPE:?--bundle-type=<type> is required}"

ARTIFACT_DIR="$OUT_DIR/language-server/$BUNDLE_TYPE/artifacts"
BUILD_DIR="$OUT_DIR/language-server/$BUNDLE_TYPE/vscode-extension"

rm -rf "$BUILD_DIR"

if [[ ! -f "$BUILD_EXTENSION_SCRIPT" ]]; then
  echo "Error: buildExtension script not found: $BUILD_EXTENSION_SCRIPT" >&2
  exit 1
fi

FIRST_BUNDLE="$(find "$ARTIFACT_DIR" -type f -name '*.product-info.json' -print -quit)"
if [[ -z "$FIRST_BUNDLE" ]]; then
  echo "Error: no .product-info.json found in $ARTIFACT_DIR" >&2
  echo "Make sure that you have built $BUNDLE_TYPE bundles" >&2
  exit 1
fi

pids=()
for productInfo in "$ARTIFACT_DIR"/*.product-info.json; do
    bundle="${productInfo%.product-info.json}"
    echo -e "\033[32mProcessing: $bundle\033[0m"
    artifact_filename="$(basename -- "$bundle")"
    version_arch_ext="${artifact_filename#${BUNDLE_TYPE}-}"
    basename="$BUNDLE_TYPE"
    case "$version_arch_ext" in
      *.tar.gz)  version_arch="${version_arch_ext%.tar.gz}" ;;
      *.win.zip) version_arch="${version_arch_ext%.win.zip}" ;;
      *.sit)     version_arch="${version_arch_ext%.sit}" ;;
    esac

    # Derive version and arch, handling both release and SNAPSHOT versions.
    # Examples:
    #   262.0.0                   -> version=262.0.0, arch=amd64
    #   262.0.0-aarch64           -> version=262.0.0, arch=aarch64
    #   262.0.0-SNAPSHOT          -> version=262.0.0-SNAPSHOT, arch=amd64
    #   262.0.0-SNAPSHOT-aarch64  -> version=262.0.0-SNAPSHOT, arch=aarch64
    if [[ "$version_arch" == *"-SNAPSHOT-"* ]]; then
        version="${version_arch%%-SNAPSHOT-*}-SNAPSHOT"
        arch="${version_arch#"$version"-}"
    elif [[ "$version_arch" == *"-SNAPSHOT" ]]; then
        version="$version_arch"
        arch="amd64"
    elif [[ "$version_arch" == *"-"* ]]; then
        version="${version_arch%%-*}"
        arch="${version_arch#"$version"-}"
    else
        version="$version_arch"
        arch="amd64"
    fi

    # Normalize old ".SNAPSHOT" style to "-SNAPSHOT" for vscode compatibility
    if [[ "$version" == *".SNAPSHOT" ]]; then
        version="${version%.SNAPSHOT}-SNAPSHOT"
    fi

    case "$version_arch_ext" in
      *.tar.gz)  platform="linux-$arch" ;;
      *.win.zip) platform="win-$arch" ;;
      *.sit)     platform="mac-$arch" ;;
    esac
    vsix_target_filename="$basename-$version-$platform.vsix"

    EXTENSION_DIR="$BUILD_DIR/vscode-$platform"

    # Run each build in parallel with per-process environment
    BUILD_DIR="$BUILD_DIR" \
    EXTENSION_DIR="$EXTENSION_DIR" \
    VSIX_TARGET_FILENAME="$vsix_target_filename" \
    VSCE_VERSION="$version" \
    LSP_ZIP_PATH="$bundle" \
    BUNDLE_TYPE="$BUNDLE_TYPE" \
      bash "$BUILD_EXTENSION_SCRIPT" &
    pids+=($!)
done

failed=0
for pid in "${pids[@]}"; do
    wait "$pid" || failed=1
done

end_ts=$(date +%s)
elapsed=$((end_ts - start_ts))

if [[ $failed -ne 0 ]]; then
    echo "Error: one or more extension builds failed" >&2
    echo "Completed in ${elapsed} s"
    exit 1
else
    echo "Completed in ${elapsed} s"
fi