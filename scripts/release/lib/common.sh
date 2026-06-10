#!/usr/bin/env bash
# Shared helpers for local release builds (mirrors .github/workflows/release.yml)

set -euo pipefail

find_repo_root() {
  local start_dir="$1"
  local dir="$start_dir"
  while [[ "$dir" != "/" ]]; do
    if [[ -f "${dir}/pom.xml" && -d "${dir}/scripts/release/lib" ]]; then
      echo "$dir"
      return 0
    fi
    dir="$(dirname "$dir")"
  done
  return 1
}

if [[ -n "${BASH_SOURCE[0]:-}" ]]; then
  RELEASE_SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
else
  RELEASE_SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
fi

REPO_ROOT="$(find_repo_root "$RELEASE_SCRIPT_DIR")" || {
  echo "[release] ERROR: Cannot locate repo root from ${RELEASE_SCRIPT_DIR}" >&2
  exit 1
}

MAIN_JAR="keenotes-mobile-1.0.0-SNAPSHOT.jar"
MAIN_CLASS="org.springframework.boot.loader.launch.JarLauncher"
VENDOR="Keevol"
DIST_DIR="${REPO_ROOT}/dist"

log() {
  echo "[release] $*"
}

die() {
  echo "[release] ERROR: $*" >&2
  exit 1
}

require_cmd() {
  local cmd="$1"
  command -v "$cmd" >/dev/null 2>&1 || die "Required command not found: $cmd"
}

normalize_version() {
  local raw="$1"
  raw="${raw#v}"
  raw="${raw#V}"
  echo "$raw"
}

# jpackage only supports up to 3 version segments (x.y.z)
app_version_from() {
  echo "$1" | cut -d. -f1-3
}

android_version_code_from() {
  local version="$1"
  echo "$version" | awk -F. '{printf "%d%02d%02d", $1+0, $2+0, $3+0}'
}

generate_build_info() {
  local version="$1"
  local build_time
  build_time="$(date -u +"%Y-%m-%dT%H:%M:%SZ")"
  local out_dir="${REPO_ROOT}/src/main/java/cn/keevol/keenotes/mobilefx/generated"
  local out_file="${out_dir}/BuildInfo.java"

  mkdir -p "$out_dir"

  cat >"$out_file" <<EOF
package cn.keevol.keenotes.mobilefx.generated;

/**
 * Auto-generated build information.
 * DO NOT EDIT - This file is generated during build.
 */
public final class BuildInfo {
    public static final String VERSION = "$version";
    public static final String BUILD_TIME = "$build_time";

    private BuildInfo() {}
}
EOF

  log "Generated BuildInfo.java (version=$version, build_time=$build_time)"
}

prepare_dist_dir() {
  mkdir -p "$DIST_DIR"
}

build_desktop_jar() {
  local javafx_platform="$1"
  local mvn_extra_args=()

  if [[ "${SKIP_CLEAN:-0}" != "1" ]]; then
    mvn_extra_args+=(clean)
  fi

  log "Building desktop JAR (javafx.platform=${javafx_platform})..."
  (
    cd "$REPO_ROOT"
    mvn "${mvn_extra_args[@]}" package -Pdesktop -DskipTests "-Djavafx.platform=${javafx_platform}"
  )
}

detect_platform_id() {
  case "$(uname -s)" in
    Darwin)
      if [[ "$(uname -m)" == "arm64" ]]; then
        echo "macos-aarch64"
      else
        echo "macos-x64"
      fi
      ;;
    Linux)
      echo "linux"
      ;;
    MINGW* | MSYS* | CYGWIN*)
      echo "windows"
      ;;
    *)
      die "Unsupported OS: $(uname -s)"
      ;;
  esac
}

platform_label() {
  case "$1" in
    macos-aarch64) echo "macOS Apple Silicon" ;;
    macos-x64) echo "macOS Intel" ;;
    linux) echo "Linux" ;;
    windows) echo "Windows" ;;
    android) echo "Android" ;;
    *) echo "$1" ;;
  esac
}
