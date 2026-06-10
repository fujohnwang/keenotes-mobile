#!/usr/bin/env bash
# macOS local release build (mirrors .github/workflows/release.yml build-macos job)

set -euo pipefail

source "$(dirname "${BASH_SOURCE[0]}")/common.sh"

macos_release() {
  local version="$1"
  local app_version
  app_version="$(app_version_from "$version")"

  local arch name_suffix sqlite_arch javafx_platform
  case "$(detect_platform_id)" in
    macos-aarch64)
      arch="mac-aarch64"
      name_suffix="AppleSilicon"
      sqlite_arch="aarch64"
      javafx_platform="mac-aarch64"
      ;;
    macos-x64)
      arch="mac"
      name_suffix="Intel"
      sqlite_arch="x86_64"
      javafx_platform="mac"
      ;;
    *)
      die "macos.sh must run on macOS"
      ;;
  esac

  require_cmd mvn
  require_cmd jpackage
  require_cmd codesign
  require_cmd hdiutil

  prepare_dist_dir
  generate_build_info "$version"
  build_desktop_jar "$javafx_platform"

  local signing_identity="${MACOS_SIGNING_IDENTITY:-}"
  local do_sign=0
  if [[ -n "$signing_identity" && "${SKIP_SIGN:-0}" != "1" ]]; then
    do_sign=1
    log "Code signing enabled: $signing_identity"
  else
    log "Code signing disabled (set MACOS_SIGNING_IDENTITY to enable)"
  fi

  if [[ "$do_sign" == "1" ]]; then
    macos_build_signed_app "$app_version" "$sqlite_arch" "$signing_identity"
    macos_build_signed_dmg "$version" "$name_suffix" "$signing_identity"

    if [[ "${SKIP_NOTARIZE:-0}" != "1" ]]; then
      macos_notarize_dmg "$version" "$name_suffix"
    else
      log "Skipping notarization (--skip-notarize or SKIP_NOTARIZE=1)"
    fi
  else
    macos_build_unsigned_dmg "$app_version" "$version" "$name_suffix"
  fi

  log "macOS artifacts in ${DIST_DIR}:"
  ls -la "$DIST_DIR"
}

macos_build_signed_app() {
  local app_version="$1"
  local sqlite_arch="$2"
  local signing_identity="$3"

  cat >"${REPO_ROOT}/entitlements.plist" <<'EOF'
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
    <key>com.apple.security.cs.allow-jit</key>
    <true/>
    <key>com.apple.security.cs.disable-library-validation</key>
    <true/>
</dict>
</plist>
EOF

  log "Creating signed app bundle..."
  jpackage \
    --input "${REPO_ROOT}/target" \
    --name "KeeNotes" \
    --main-jar "$MAIN_JAR" \
    --main-class "$MAIN_CLASS" \
    --type app-image \
    --app-version "$app_version" \
    --vendor "$VENDOR" \
    --icon "${REPO_ROOT}/src/main/resources/icons/keenotes.icns" \
    --mac-package-identifier "cn.keevol.keenotes" \
    --mac-package-name "KeeNotes" \
    --java-options "-Xmx512m" \
    --java-options "-Xdock:name=KeeNotes" \
    --java-options '-Dorg.sqlite.lib.path=$APPDIR/../Frameworks' \
    --dest "$DIST_DIR"

  local app_path="${DIST_DIR}/KeeNotes.app"
  local frameworks_dir="${app_path}/Contents/Frameworks"
  mkdir -p "$frameworks_dir"

  local main_jar="${app_path}/Contents/app/${MAIN_JAR}"
  log "Patching SQLite native library into app bundle..."

  unzip -p "$main_jar" "BOOT-INF/lib/sqlite-jdbc-*.jar" >"${REPO_ROOT}/sqlite-temp.jar"

  if [[ -s "${REPO_ROOT}/sqlite-temp.jar" ]]; then
    unzip -p "${REPO_ROOT}/sqlite-temp.jar" "org/sqlite/native/Mac/${sqlite_arch}/libsqlitejdbc.dylib" \
      >"${frameworks_dir}/libsqlitejdbc.dylib"

    if [[ -s "${frameworks_dir}/libsqlitejdbc.dylib" ]]; then
      zip -d "${REPO_ROOT}/sqlite-temp.jar" "org/sqlite/native/Mac/*" 2>/dev/null || true

      local sqlite_internal_path
      sqlite_internal_path="$(unzip -l "$main_jar" | grep "sqlite-jdbc-.*\.jar" | awk '{print $4}')"

      local temp_update_dir="${REPO_ROOT}/update_tmp"
      mkdir -p "${temp_update_dir}/$(dirname "$sqlite_internal_path")"
      mv "${REPO_ROOT}/sqlite-temp.jar" "${temp_update_dir}/${sqlite_internal_path}"

      (
        cd "$temp_update_dir"
        zip -0 -u "$main_jar" "$sqlite_internal_path"
      )
      rm -rf "$temp_update_dir"
      log "Patched main JAR with store-mode sqlite jar"
    else
      rm -f "${REPO_ROOT}/sqlite-temp.jar"
      die "Failed to extract libsqlitejdbc.dylib for architecture ${sqlite_arch}"
    fi
  else
    rm -f "${REPO_ROOT}/sqlite-temp.jar"
    log "WARNING: sqlite-jdbc jar not found inside main JAR"
  fi

  log "Signing binaries..."
  local jli_link="${app_path}/Contents/runtime/Contents/MacOS/libjli.dylib"
  if [[ -f "$jli_link" ]]; then
    rm "$jli_link"
    ln -s "../Home/lib/libjli.dylib" "$jli_link"
  fi

  if [[ -f "${frameworks_dir}/libsqlitejdbc.dylib" ]]; then
    codesign --force --sign "$signing_identity" --timestamp --options runtime \
      "${frameworks_dir}/libsqlitejdbc.dylib"
  fi

  find "${app_path}/Contents" -type f \( -name "*.dylib" -o -name "*.jnilib" \) | while read -r lib; do
    if [[ "$lib" != *"/Frameworks/"* ]]; then
      codesign --force --sign "$signing_identity" --timestamp --options runtime "$lib"
    fi
  done

  find "${app_path}/Contents/runtime" -type f -perm +111 | while read -r exe; do
    if file "$exe" | grep -q "Mach-O"; then
      codesign --force --sign "$signing_identity" --timestamp --options runtime \
        --entitlements "${REPO_ROOT}/entitlements.plist" "$exe"
    fi
  done

  codesign --force --sign "$signing_identity" --timestamp --options runtime \
    --entitlements "${REPO_ROOT}/entitlements.plist" "${app_path}/Contents/MacOS/KeeNotes"
  codesign --force --sign "$signing_identity" --timestamp --options runtime \
    --entitlements "${REPO_ROOT}/entitlements.plist" "$app_path"

  codesign -vvv --deep --strict "$app_path"
}

macos_build_signed_dmg() {
  local version="$1"
  local name_suffix="$2"
  local signing_identity="$3"

  local volname="KeeNotes"
  local dmg_temp="${DIST_DIR}/KeeNotes-temp.dmg"
  local dmg_rw="${DIST_DIR}/KeeNotes-rw.dmg"
  local dmg_final="${DIST_DIR}/KeeNotes-${name_suffix}-${version}.dmg"
  local source_dir="${DIST_DIR}/dmg-source"

  mkdir -p "$source_dir"
  cp -R "${DIST_DIR}/KeeNotes.app" "$source_dir/"
  ln -sf /Applications "${source_dir}/Applications"

  hdiutil create -volname "$volname" -srcfolder "$source_dir" -ov -format UDRW "$dmg_rw"

  local mount_dir
  mount_dir="$(hdiutil attach "$dmg_rw" | grep Volumes | awk '{print $3}')"

  osascript <<EOF
tell application "Finder"
  tell disk "$volname"
    open
    set current view of container window to icon view
    set toolbar visible of container window to false
    set statusbar visible of container window to false
    set the bounds of container window to {100, 100, 600, 400}
    set viewOptions to the icon view options of container window
    set arrangement of viewOptions to not arranged
    set icon size of viewOptions to 100
    set position of item "KeeNotes.app" of container window to {150, 150}
    set position of item "Applications" of container window to {350, 150}
    update without registering applications
    delay 2
    close
  end tell
end tell
EOF

  hdiutil detach "$mount_dir"
  hdiutil convert "$dmg_rw" -format UDZO -o "$dmg_temp"
  rm -f "$dmg_rw"
  rm -rf "$source_dir"

  codesign --force --sign "$signing_identity" --timestamp "$dmg_temp"
  mv "$dmg_temp" "$dmg_final"
  codesign -vvv "$dmg_final"
  log "Created signed DMG: $dmg_final"
}

macos_notarize_dmg() {
  local version="$1"
  local name_suffix="$2"
  local dmg_final="${DIST_DIR}/KeeNotes-${name_suffix}-${version}.dmg"

  local apple_id="${MACOS_NOTARIZATION_APPLE_ID:-${APPLE_ID:-}}"
  local apple_password="${MACOS_NOTARIZATION_PWD:-${APPLE_PASSWORD:-}}"
  local team_id="${MACOS_NOTARIZATION_TEAM_ID:-${APPLE_TEAM_ID:-}}"

  [[ -n "$apple_id" ]] || die "Notarization requires MACOS_NOTARIZATION_APPLE_ID or APPLE_ID"
  [[ -n "$apple_password" ]] || die "Notarization requires MACOS_NOTARIZATION_PWD or APPLE_PASSWORD"
  [[ -n "$team_id" ]] || die "Notarization requires MACOS_NOTARIZATION_TEAM_ID or APPLE_TEAM_ID"

  require_cmd xcrun

  log "Submitting DMG for notarization..."
  local submission_output
  submission_output="$(xcrun notarytool submit "$dmg_final" \
    --apple-id "$apple_id" \
    --password "$apple_password" \
    --team-id "$team_id" \
    --wait \
    --timeout 30m \
    --output-format json)"

  echo "$submission_output"

  local status
  status="$(echo "$submission_output" | grep -o '"status":"[^"]*"' | cut -d'"' -f4)"

  if [[ "$status" == "Accepted" ]]; then
    log "Notarization successful, stapling ticket..."
    xcrun stapler staple "$dmg_final"
  else
    die "Notarization failed with status: ${status:-unknown}"
  fi
}

macos_build_unsigned_dmg() {
  local app_version="$1"
  local version="$2"
  local name_suffix="$3"

  log "Creating unsigned DMG (local dev build)..."
  jpackage \
    --input "${REPO_ROOT}/target" \
    --name "KeeNotes" \
    --main-jar "$MAIN_JAR" \
    --main-class "$MAIN_CLASS" \
    --type dmg \
    --app-version "$app_version" \
    --vendor "$VENDOR" \
    --icon "${REPO_ROOT}/src/main/resources/icons/keenotes.icns" \
    --mac-package-identifier "cn.keevol.keenotes" \
    --mac-package-name "KeeNotes" \
    --java-options "-Xmx512m" \
    --java-options "-Xdock:name=KeeNotes" \
    --dest "$DIST_DIR"

  local built_dmg="${DIST_DIR}/KeeNotes-${app_version}.dmg"
  local final_dmg="${DIST_DIR}/KeeNotes-${name_suffix}-${version}.dmg"
  mv "$built_dmg" "$final_dmg"

  cat >"${DIST_DIR}/INSTALL-MACOS.txt" <<'EOF'
KeeNotes for macOS - Installation Instructions
===============================================

This is an unsigned local build for testing purposes.

If you see "KeeNotes.app is damaged" error, run this command in Terminal:

    xattr -cr /Applications/KeeNotes.app

Or right-click the app and select "Open" (first time only).

For signed releases, download from the Releases page.
EOF

  log "Created unsigned DMG: $final_dmg"
}

if [[ "${BASH_SOURCE[0]}" == "${0}" ]]; then
  macos_release "${1:?version required}"
fi
