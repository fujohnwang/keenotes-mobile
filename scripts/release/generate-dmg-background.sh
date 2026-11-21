#!/usr/bin/env bash
# Regenerate the 500x300 Finder background with 1x and 2x representations.
set -euo pipefail

ASSET_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/assets" && pwd)"
DMG_ASSET_TMP="$(mktemp -d "${TMPDIR:-/tmp}/keenotes-dmg-assets.XXXXXX")"
trap 'rm -rf "$DMG_ASSET_TMP"' EXIT

# Quick Look renders a square thumbnail. Center the source canvas in that
# square before sips' center crop, otherwise the arrow moves up by 100 points.
sed 's/height="300" viewBox="0 0 500 300"/height="500" viewBox="0 -100 500 500"/' \
  "$ASSET_DIR/dmg-background.svg" > "$DMG_ASSET_TMP/background-square.svg"
qlmanage -t -s 1000 -o "$DMG_ASSET_TMP" "$DMG_ASSET_TMP/background-square.svg"
sips --cropToHeightWidth 600 1000 "$DMG_ASSET_TMP/background-square.svg.png" \
  --out "$DMG_ASSET_TMP/background-2x.png"
sips -s format tiff \
  "$DMG_ASSET_TMP/background-2x.png" --out "$DMG_ASSET_TMP/background-2x.tiff"
sips -z 300 500 -s format tiff \
  "$DMG_ASSET_TMP/background-2x.png" --out "$DMG_ASSET_TMP/background-1x.tiff"
# Set DPI after conversion; conversion can otherwise reset it to 72.
sips -s dpiWidth 144 -s dpiHeight 144 "$DMG_ASSET_TMP/background-2x.tiff"
sips -s dpiWidth 72 -s dpiHeight 72 "$DMG_ASSET_TMP/background-1x.tiff"
tiffutil -lzw "$DMG_ASSET_TMP/background-1x.tiff" -out "$DMG_ASSET_TMP/compressed-1x.tiff"
tiffutil -lzw "$DMG_ASSET_TMP/background-2x.tiff" -out "$DMG_ASSET_TMP/compressed-2x.tiff"
# Preserve each representation's DPI instead of normalizing both to 72.
tiffutil -catnosizecheck "$DMG_ASSET_TMP/compressed-1x.tiff" \
  "$DMG_ASSET_TMP/compressed-2x.tiff" -out "$ASSET_DIR/dmg-background.tiff"
