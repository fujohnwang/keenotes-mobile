#!/usr/bin/env bash
# Linux local release build (mirrors .github/workflows/release.yml build-linux job)

set -euo pipefail

source "$(dirname "${BASH_SOURCE[0]}")/common.sh"

linux_release() {
  local version="$1"
  local app_version
  app_version="$(app_version_from "$version")"

  require_cmd mvn
  require_cmd jpackage

  prepare_dist_dir
  generate_build_info "$version"
  build_desktop_jar "linux"

  log "Creating DEB package..."
  jpackage \
    --input "${REPO_ROOT}/target" \
    --name keenotes \
    --main-jar "$MAIN_JAR" \
    --main-class "$MAIN_CLASS" \
    --type deb \
    --app-version "$app_version" \
    --vendor "$VENDOR" \
    --icon "${REPO_ROOT}/src/main/resources/icons/icon-512.png" \
    --java-options "-Xmx512m" \
    --linux-shortcut \
    --dest "$DIST_DIR"

  log "Creating RPM package..."
  jpackage \
    --input "${REPO_ROOT}/target" \
    --name keenotes \
    --main-jar "$MAIN_JAR" \
    --main-class "$MAIN_CLASS" \
    --type rpm \
    --app-version "$app_version" \
    --vendor "$VENDOR" \
    --icon "${REPO_ROOT}/src/main/resources/icons/icon-512.png" \
    --java-options "-Xmx512m" \
    --linux-shortcut \
    --dest "$DIST_DIR"

  log "Linux artifacts in ${DIST_DIR}:"
  ls -la "$DIST_DIR"
}

if [[ "${BASH_SOURCE[0]}" == "${0}" ]]; then
  linux_release "${1:?version required}"
fi
