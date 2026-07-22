#!/usr/bin/env bash
# Android local release build (mirrors .github/workflows/release.yml build-android job)

set -euo pipefail

source "$(dirname "${BASH_SOURCE[0]}")/common.sh"

android_release() {
  local version="$1"
  local version_code
  version_code="$(android_version_code_from "$version")"

  require_cmd java

  local gradle_cmd="gradle"
  if ! command -v gradle >/dev/null 2>&1; then
    if [[ -x "${REPO_ROOT}/keenotes-android/gradlew" ]]; then
      gradle_cmd="${REPO_ROOT}/keenotes-android/gradlew"
    else
      die "Gradle not found. Install Gradle 9.3.1+ or use the project gradlew."
    fi
  fi

  local keystore_file="${KEYSTORE_FILE:-}"
  [[ -n "$keystore_file" ]] || die "Android release requires KEYSTORE_FILE"

  local keystore_password="${KEYSTORE_PASSWORD:-}"
  local key_alias="${KEY_ALIAS:-}"
  local key_password="${KEY_PASSWORD:-}"

  [[ -n "$keystore_password" ]] || die "Android release requires KEYSTORE_PASSWORD"
  [[ -n "$key_alias" ]] || die "Android release requires KEY_ALIAS"
  [[ -n "$key_password" ]] || die "Android release requires KEY_PASSWORD"

  [[ -f "$keystore_file" ]] || die "Keystore not found: $keystore_file"

  if [[ -z "${ANDROID_HOME:-}" && -z "${ANDROID_SDK_ROOT:-}" ]]; then
    die "Android SDK not found. Set ANDROID_HOME or ANDROID_SDK_ROOT."
  fi

  log "Building signed Android APK (version=${version}, versionCode=${version_code})..."
  (
    cd "${REPO_ROOT}/keenotes-android"
    "$gradle_cmd" assembleRelease --no-daemon \
      -PKEYSTORE_FILE="$keystore_file" \
      -PKEYSTORE_PASSWORD="$keystore_password" \
      -PKEY_ALIAS="$key_alias" \
      -PKEY_PASSWORD="$key_password" \
      -PversionName="$version" \
      -PversionCode="$version_code"
  )

  log "Building signed Android AAB..."
  (
    cd "${REPO_ROOT}/keenotes-android"
    "$gradle_cmd" bundleRelease --no-daemon \
      -PKEYSTORE_FILE="$keystore_file" \
      -PKEYSTORE_PASSWORD="$keystore_password" \
      -PKEY_ALIAS="$key_alias" \
      -PKEY_PASSWORD="$key_password" \
      -PversionName="$version" \
      -PversionCode="$version_code"
  )

  log "Android artifacts:"
  ls -la "${REPO_ROOT}/keenotes-android/app/build/outputs/apk/release/"*.apk 2>/dev/null || true
  ls -la "${REPO_ROOT}/keenotes-android/app/build/outputs/bundle/release/"*.aab 2>/dev/null || true
}

if [[ "${BASH_SOURCE[0]}" == "${0}" ]]; then
  android_release "${1:?version required}"
fi
