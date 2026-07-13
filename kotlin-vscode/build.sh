#!/usr/bin/env bash

set -euo pipefail

start_ts=$(date +%s)

SCRIPT_DIR="$(realpath "$(dirname "$0")")"
WORKSPACE_DIR=""
OUT_DIR="$SCRIPT_DIR/out"
workspace_candidate="$(realpath "$SCRIPT_DIR/../..")"
if [[ -f "$workspace_candidate/pnpm-workspace.yaml" ]]; then
  WORKSPACE_DIR="$workspace_candidate"
  OUT_DIR="$(realpath "$SCRIPT_DIR/../../../out")"
fi

PACKAGE_DIR="${PACKAGE_DIR:-}"
BUNDLE_TYPE="${BUNDLE_TYPE:-}"
VSCE_VERSION="${VSCE_VERSION:-}"
THIN_BUNDLE="${THIN_BUNDLE:-false}"
DOWNLOAD_BASE_URL="${DOWNLOAD_BASE_URL:-}"
DEFAULT_BUNDLE_TYPE="kotlin-server"

for arg in "$@"; do
    case "$arg" in
        --package-dir=*) PACKAGE_DIR="${arg#--package-dir=}" ;;
        --bundle-type=*) BUNDLE_TYPE="${arg#--bundle-type=}" ;;
        --vsce-version=*) VSCE_VERSION="${arg#--vsce-version=}" ;;
        --thin) THIN_BUNDLE="true" ;;
        --download-base-url=*) DOWNLOAD_BASE_URL="${arg#--download-base-url=}" ;;
    esac
done

export THIN_BUNDLE

BUNDLE_TYPE="${BUNDLE_TYPE:-$DEFAULT_BUNDLE_TYPE}"

if [[ -z "${INTELLIJ_EULA_GATE:-}" ]]; then
  case "$BUNDLE_TYPE" in
    kotlin-server|intellij-server-air) INTELLIJ_EULA_GATE="false" ;;
    *) INTELLIJ_EULA_GATE="true" ;;
  esac
fi
export INTELLIJ_EULA_GATE

if ! command -v pnpm >/dev/null; then
  if ! command -v npm >/dev/null; then
    echo "Error: pnpm is not installed and npm is not available" >&2
    exit 1
  fi

  npm install -g pnpm@11.5.1

if [[ -n "$TEAMCITY_VERSION" ]]; then
  NPM_PREFIX=$(npm config get prefix)
  export PATH="$NPM_PREFIX/bin:$PATH"
fi

  pnpm --version
fi

if [[ -z "$PACKAGE_DIR" ]]; then
  if [[ "$BUNDLE_TYPE" == "$DEFAULT_BUNDLE_TYPE" ]]; then
    PACKAGE_DIR="$SCRIPT_DIR"
  else
    if [[ -z "$WORKSPACE_DIR" ]]; then
      echo "Error: bundle type '$BUNDLE_TYPE' requires a pnpm workspace checkout" >&2
      exit 1
    fi
    PACKAGE_DIR="$(pnpm --reporter=silent --dir "$WORKSPACE_DIR" --filter "$BUNDLE_TYPE" exec pwd 2>/dev/null | tail -n1 || true)"
  fi

  if [[ ! -d "$PACKAGE_DIR" ]]; then
    echo "Error: package directory for bundle type '$BUNDLE_TYPE' not found: $PACKAGE_DIR" >&2
    exit 1
  fi
fi

DEFAULT_PACKAGE_DIR="$SCRIPT_DIR"
PACKAGE_DIR="$(realpath "$PACKAGE_DIR")"
if [[ -n "$WORKSPACE_DIR" && "$PACKAGE_DIR" != "$WORKSPACE_DIR"/* ]]; then
  echo "Error: package directory must be inside the pnpm workspace" >&2
  exit 1
fi
if [[ -z "$WORKSPACE_DIR" && "$PACKAGE_DIR" != "$DEFAULT_PACKAGE_DIR" ]]; then
  echo "Error: --package-dir requires a pnpm workspace checkout" >&2
  exit 1
fi

cd "${WORKSPACE_DIR:-$PACKAGE_DIR}"

if [[ ! -f "$PACKAGE_DIR/package.json" ]]; then
  echo "Error: package.json not found in package directory" >&2
  exit 1
fi

ARTIFACT_DIR="$OUT_DIR/language-server/$BUNDLE_TYPE/artifacts"
BUILD_DIR="$OUT_DIR/language-server/$BUNDLE_TYPE/vscode-extension"
RESOLVED_PACKAGE_DIR="$BUILD_DIR/package-manifest"
RESOLVED_PACKAGE_JSON="$RESOLVED_PACKAGE_DIR/package.json"

rm -rf "$BUILD_DIR"

FIRST_BUNDLE="$(find "$ARTIFACT_DIR" -type f -name '*.product-info.json' -print -quit)"
if [[ -z "$FIRST_BUNDLE" ]]; then
  echo "Error: no .product-info.json found in $ARTIFACT_DIR" >&2
  echo "Make sure that you have built $BUNDLE_TYPE bundles" >&2
  exit 1
fi

echo "Installing VSCode extension dependencies..."
pnpm --dir "${WORKSPACE_DIR:-$PACKAGE_DIR}" install ${WORKSPACE_DIR:+--frozen-lockfile}

echo "Building VSCode extension package: $PACKAGE_DIR"
pnpm --dir "$PACKAGE_DIR" run package

copy_file_if_exists() {
  local extension_dir="$1"
  local file="$2"
  if [[ -f "$PACKAGE_DIR/$file" ]]; then
    mkdir -p "$extension_dir/$(dirname "$file")"
    cp "$PACKAGE_DIR/$file" "$extension_dir/$file"
  fi
}

copy_dir_if_exists() {
  local extension_dir="$1"
  local dir="$2"
  if [[ -d "$PACKAGE_DIR/$dir" ]]; then
    mkdir -p "$extension_dir/$(dirname "$dir")"
    cp -R "$PACKAGE_DIR/$dir" "$extension_dir/$dir"
  fi
}

resolve_package_manifest() {
  mkdir -p "$RESOLVED_PACKAGE_DIR"

  if ! grep -q '"catalog:' "$PACKAGE_DIR/package.json"; then
    cp "$PACKAGE_DIR/package.json" "$RESOLVED_PACKAGE_JSON"
    return
  fi

  local pack_dir="$BUILD_DIR/pnpm-pack"
  rm -rf -- "$pack_dir"
  mkdir -p "$pack_dir"

  # pnpm resolves workspace catalog versions before internal manifests go into the VSIX.
  local pack_json
  pack_json="$(pnpm --dir "$PACKAGE_DIR" pack --pack-destination "$pack_dir" --json)"

  local packed_archive
  packed_archive="$(node -p 'JSON.parse(require("node:fs").readFileSync(0, "utf8")).filename || ""' <<< "$pack_json")"
  if [[ -z "$packed_archive" || ! -f "$packed_archive" ]]; then
    echo "Error: pnpm pack did not produce an archive in $pack_dir" >&2
    exit 1
  fi

  tar -xOf "$packed_archive" package/package.json > "$RESOLVED_PACKAGE_JSON"
  rm -rf -- "$pack_dir"
}

resolve_package_manifest

server_download_url() {
  local archive_name="$1"
  if [[ -n "$DOWNLOAD_BASE_URL" ]]; then
    echo "${DOWNLOAD_BASE_URL%/}/$archive_name"
    return
  fi
  echo ""
}

write_server_bundle_metadata() {
  local extension_dir="$1"
  local archive_name="$2"
  local download_url="$3"
  local sha256_file="$4"
  local sha256_url="${download_url}.sha256"
  local sha256_sidecar
  local sha256_source

  if [[ -n "${TEAMCITY_VERSION:-}" && -f "$sha256_file" ]]; then
    sha256_source="$sha256_file"
    sha256_sidecar="$(<"$sha256_source")"
  else
    sha256_source="$sha256_url"
    if ! sha256_sidecar="$(curl --fail --location --silent --show-error "$sha256_url")"; then
      echo "Error: failed to download SHA-256 sidecar: $sha256_url" >&2
      return 1
    fi
  fi

  node - "$extension_dir/server-bundle.json" "$download_url" "$archive_name" "$sha256_source" "$sha256_sidecar" <<'NODE'
const fs = require('node:fs');
const path = require('node:path');
const [target, url, archiveName, sidecarSource, sidecarValue] = process.argv.slice(2);
const value = sidecarValue.trim();
const match = /^([0-9a-fA-F]{64})(?:\s+\*?(.+))?$/.exec(value);
if (!match) {
  console.error(`Invalid SHA-256 sidecar: ${sidecarSource}`);
  process.exit(1);
}
if (match[2] !== undefined && path.basename(match[2]) !== archiveName) {
  console.error(
    `SHA-256 sidecar ${sidecarSource} describes ${match[2]}, expected ${archiveName}`,
  );
  process.exit(1);
}
const sha256 = match[1].toLowerCase();
fs.writeFileSync(target, JSON.stringify({ url, archiveName, sha256 }, null, 2) + '\n');
NODE
}

build_extension() {
  local extension_dir="$1"
  local vsix_target_filename="$2"
  local vsce_version="$3"
  local vsce_target="$4"
  local lsp_zip_path="$5"
  local log_prefix="$6"
  local download_archive_name="$7"

  if [[ "$THIN_BUNDLE" != "true" && ! -f "$lsp_zip_path" ]]; then
    echo "Error: LSP archive not found: $lsp_zip_path" >&2
    exit 1
  fi

  mkdir -p "$BUILD_DIR"
  rm -rf -- "$extension_dir"
  mkdir -p "$extension_dir"

  copy_file_if_exists "$extension_dir" ".vscodeignore"
  copy_file_if_exists "$extension_dir" "LICENSE.txt"
  copy_file_if_exists "$extension_dir" "README.md"
  cp "$RESOLVED_PACKAGE_JSON" "$extension_dir/package.json"
  copy_dir_if_exists "$extension_dir" "bin"
  copy_dir_if_exists "$extension_dir" "extension-policy"
  copy_dir_if_exists "$extension_dir" "grammars"
  copy_dir_if_exists "$extension_dir" "icons"
  copy_dir_if_exists "$extension_dir" "resources"
  copy_dir_if_exists "$extension_dir" "out/dist"
  copy_dir_if_exists "$extension_dir" "syntaxes"

  pushd "$extension_dir" > /dev/null

  if [[ "$THIN_BUNDLE" == "true" ]]; then
    download_url="$(server_download_url "$download_archive_name")"
    if [[ -z "$download_url" ]]; then
      echo "Error: thin bundle requires --download-base-url" >&2
      exit 1
    fi
    write_server_bundle_metadata \
      "$extension_dir" \
      "$download_archive_name" \
      "$download_url" \
      "${lsp_zip_path}.sha256"
  else
    cp "$SCRIPT_DIR/unpack-server.mjs" "$extension_dir/unpack-server.mjs"
    export LSP_ZIP_PATH="$lsp_zip_path"

    echo "$log_prefix Unpacking bundled language server..."
    node unpack-server.mjs
  fi

  echo "$log_prefix Running vsce package..."

  vsce_args=("$vsce_version"
             --out "$BUILD_DIR/$vsix_target_filename"
             --baseContentUrl=https://github.com/Kotlin/kotlin-lsp/tree/main/kotlin-vscode
             --no-dependencies)
  if [[ -n "$vsce_target" ]]; then
    vsce_args+=(--target "$vsce_target")
  fi

  "$PACKAGE_DIR/node_modules/.bin/vsce" package "${vsce_args[@]}"
  popd > /dev/null

  rm -rf "$extension_dir"

  if [[ ! -f "$BUILD_DIR/$vsix_target_filename" ]]; then
    echo "Error: vsce package produced no output. Expected: $BUILD_DIR/$vsix_target_filename" >&2
    exit 1
  fi

  echo "##teamcity[publishArtifacts '$BUILD_DIR/$vsix_target_filename=>$BUNDLE_TYPE']"
}

pids=()
for productInfo in "$ARTIFACT_DIR"/*.product-info.json; do
    bundle="${productInfo%.product-info.json}"
    echo -e "\033[32mProcessing: $bundle\033[0m"
    artifact_filename="$(basename -- "$bundle")"
    version_arch_ext="${artifact_filename#"${BUNDLE_TYPE}"-}"
    case "$version_arch_ext" in
      *.tar.gz)  version_arch="${version_arch_ext%.tar.gz}"; archive_extension=".tar.gz" ;;
      *.win.zip) version_arch="${version_arch_ext%.win.zip}"; archive_extension=".win.zip" ;;
      *.sit)     version_arch="${version_arch_ext%.sit}"; archive_extension=".sit" ;;
      *)          echo "Error: unsupported bundle archive: $bundle" >&2; exit 1 ;;
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

    version="${VSCE_VERSION:-$version}"

    download_archive_name="$BUNDLE_TYPE-$version"
    if [[ "$arch" != "amd64" ]]; then
      download_archive_name+="-$arch"
    fi
    download_archive_name+="$archive_extension"

    case "$version_arch_ext" in
      *.tar.gz)  platform="linux-$arch"; vsce_os="linux"  ;;
      *.win.zip) platform="win-$arch";   vsce_os="win32"  ;;
      *.sit)     platform="mac-$arch";   vsce_os="darwin" ;;
      *)          echo "Error: unsupported bundle archive: $bundle" >&2; exit 1 ;;
    esac
    case "$arch" in
      amd64)   vsce_arch="x64"   ;;
      aarch64) vsce_arch="arm64" ;;
      *)       echo "Error: unknown arch '$arch' for vsce --target" >&2; exit 1 ;;
    esac
    vsce_target="$vsce_os-$vsce_arch"
    vsix_target_filename="$BUNDLE_TYPE-$version-$platform.vsix"
    if [[ "$THIN_BUNDLE" == "true" ]]; then
      vsix_target_filename="$BUNDLE_TYPE-$version-$platform-thin.vsix"
    fi

    EXTENSION_DIR="$BUILD_DIR/vscode-$platform"

    build_extension "$EXTENSION_DIR" "$vsix_target_filename" "$version" "$vsce_target" "$bundle" "[$platform]" "$download_archive_name" &
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
