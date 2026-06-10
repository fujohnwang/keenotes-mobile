#!/usr/bin/env bash
# Local release build — equivalent to .github/workflows/release.yml for the current machine.
#
# Detects OS/CPU and builds desktop installers for this platform only.
# Optional Android build and GitHub release upload.
#
# Usage:
#   ./scripts/release-local.sh --version 1.2.3
#   ./scripts/release-local.sh --version 1.2.3 --publish
#   ./scripts/release-local.sh --version 1.2.3 --android --publish
#
# Environment (optional):
#   MACOS_SIGNING_IDENTITY          Enable macOS code signing
#   MACOS_NOTARIZATION_APPLE_ID     Apple ID for notarization
#   MACOS_NOTARIZATION_PWD          App-specific password
#   MACOS_NOTARIZATION_TEAM_ID      Apple Team ID
#   KEYSTORE_FILE / KEYSTORE_PASSWORD / KEY_ALIAS / KEY_PASSWORD   Android signing
#   GH_TOKEN or RELEASE_TOKEN       GitHub token for --publish
#   KNS_ADMIN_TOKEN                 Update version API after publish
#   RELEASE_REPO                    Default: fujohnwang/keenotes-releases
#
# Prerequisites:
#   - JDK 21 (desktop) / JDK 17 (Android)
#   - Maven, jpackage (included in JDK 21+)
#   - macOS: Xcode command line tools (for signed/notarized builds)
#   - gh CLI (for --publish)

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
LIB_DIR="${SCRIPT_DIR}/release/lib"

source "${LIB_DIR}/common.sh"

VERSION=""
DO_PUBLISH=0
DO_ANDROID=0
SKIP_SIGN=0
SKIP_NOTARIZE=0
SKIP_CLEAN=0

usage() {
  cat <<'EOF'
KeeNotes local release build — equivalent to .github/workflows/release.yml for this machine.

Detects OS/CPU and builds desktop installers for the current platform only.
Optional Android build and GitHub release upload.

Environment (optional):
  MACOS_SIGNING_IDENTITY, MACOS_NOTARIZATION_*   macOS signing & notarization
  KEYSTORE_FILE, KEYSTORE_PASSWORD, KEY_ALIAS, KEY_PASSWORD   Android signing
  GH_TOKEN or RELEASE_TOKEN                      GitHub token for --publish
  KNS_ADMIN_TOKEN                                Update version API after publish
  RELEASE_REPO                                   Default: fujohnwang/keenotes-releases

Prerequisites: JDK 21, Maven, jpackage; gh CLI for --publish

EOF
  cat <<'EOF'

Options:
  --version VERSION   Release version (required unless current git tag is v*)
  --publish           Upload artifacts to GitHub releases repo
  --android           Also build signed Android APK/AAB (requires keystore env)
  --skip-sign         Skip macOS code signing even if identity is configured
  --skip-notarize     Skip macOS notarization
  --no-clean          Skip `mvn clean` (faster incremental builds)
  -h, --help          Show this help

Examples:
  ./scripts/release-local.sh --version 1.0.0
  ./scripts/release-local.sh --version 1.0.0 --publish
  MACOS_SIGNING_IDENTITY="Developer ID Application: ..." \
    ./scripts/release-local.sh --version 1.0.0 --publish
EOF
}

resolve_version() {
  if [[ -n "$VERSION" ]]; then
    normalize_version "$VERSION"
    return
  fi

  if git -C "$REPO_ROOT" describe --tags --exact-match HEAD 2>/dev/null; then
    local tag
    tag="$(git -C "$REPO_ROOT" describe --tags --exact-match HEAD)"
    normalize_version "$tag"
    return
  fi

  die "Version required. Pass --version or checkout a git tag (v*)."
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --version)
      VERSION="${2:?--version requires a value}"
      shift 2
      ;;
    --publish)
      DO_PUBLISH=1
      shift
      ;;
    --android)
      DO_ANDROID=1
      shift
      ;;
    --skip-sign)
      SKIP_SIGN=1
      shift
      ;;
    --skip-notarize)
      SKIP_NOTARIZE=1
      shift
      ;;
    --no-clean)
      SKIP_CLEAN=1
      shift
      ;;
    -h | --help)
      usage
      exit 0
      ;;
    *)
      if [[ -z "$VERSION" && "$1" != --* ]]; then
        VERSION="$1"
        shift
      else
        die "Unknown option: $1 (use --help)"
      fi
      ;;
  esac
done

export SKIP_SIGN SKIP_NOTARIZE SKIP_CLEAN

VERSION="$(resolve_version)"
APP_VERSION="$(app_version_from "$VERSION")"
PLATFORM="$(detect_platform_id)"

log "KeeNotes local release"
log "  platform:    $(platform_label "$PLATFORM") ($PLATFORM)"
log "  version:     $VERSION (app_version=$APP_VERSION)"
log "  publish:     $([ "$DO_PUBLISH" == "1" ] && echo yes || echo no)"
log "  android:     $([ "$DO_ANDROID" == "1" ] && echo yes || echo no)"
log "  repo root:   $REPO_ROOT"
echo

case "$PLATFORM" in
  macos-aarch64 | macos-x64)
    source "${LIB_DIR}/macos.sh"
    macos_release "$VERSION"
    ;;
  linux)
    source "${LIB_DIR}/linux.sh"
    linux_release "$VERSION"
    ;;
  windows)
    if command -v pwsh >/dev/null 2>&1; then
      pwsh -File "${LIB_DIR}/windows.ps1" -Version "$VERSION"
    elif command -v powershell >/dev/null 2>&1; then
      powershell -File "${LIB_DIR}/windows.ps1" -Version "$VERSION"
    else
      die "PowerShell required for Windows release build"
    fi
    ;;
  *)
    die "Unsupported platform: $PLATFORM"
    ;;
esac

if [[ "$DO_ANDROID" == "1" ]]; then
  source "${LIB_DIR}/android.sh"
  export INCLUDE_ANDROID=1
  android_release "$VERSION"
fi

if [[ "$DO_PUBLISH" == "1" ]]; then
  source "${LIB_DIR}/publish.sh"
  publish_release "$VERSION"
fi

log "Done."
