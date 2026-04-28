#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd -- "$SCRIPT_DIR/../.." && pwd)"
SOURCE_DIR="$ROOT_DIR/archives/ios-screenshots/iphone-6.9"
ICON_PATH="$ROOT_DIR/keenotes-ios/KeeNotes/Resources/Assets.xcassets/AppIcon.appiconset/AppIcon~ios-marketing.png"
OUTPUT_DIR="$SCRIPT_DIR/output/iphone-6.9"
TMP_DIR="$SCRIPT_DIR/.tmp"

WIDTH=1320
HEIGHT=2868
PANORAMA_WIDTH=$((WIDTH * 3))

TITLE_FONT="/System/Library/Fonts/SFNS.ttf"
BODY_FONT="/System/Library/Fonts/SFNS.ttf"

NOTE_SRC="$SOURCE_DIR/e3e5ca23606476aec699e66eafbb7446.jpg"
REVIEW_SRC="$SOURCE_DIR/75456352fffc6400f8ea18533d7673b9.jpg"
SEARCH_SRC="$SOURCE_DIR/b4cdf05332db268ca74a12a563539c11.jpg"
SETTINGS_SRC="$SOURCE_DIR/610fd1253d9f1c11088076f46ee5ab0b.jpg"
PANORAMA_BG="$TMP_DIR/panorama-background.png"

require_file() {
  local path="$1"
  if [[ ! -f "$path" ]]; then
    printf 'Missing required file: %s\n' "$path" >&2
    exit 1
  fi
}

cleanup() {
  rm -rf "$TMP_DIR"
}

trap cleanup EXIT

mkdir -p "$OUTPUT_DIR" "$TMP_DIR"

require_file "$ICON_PATH"
require_file "$NOTE_SRC"
require_file "$REVIEW_SRC"
require_file "$SEARCH_SRC"
require_file "$SETTINGS_SRC"
require_file "$TITLE_FONT"
require_file "$BODY_FONT"

make_brand_badge() {
  local out="$1"
  local text_color="$2"
  local panel_fill="$3"
  local panel_stroke="$4"

  magick -size 408x96 xc:none \
    \( -size 408x96 xc:none \
       -fill "$panel_fill" \
       -stroke "$panel_stroke" -strokewidth 2 \
       -draw "roundrectangle 1,1 406,94 30,30" \) \
    -composite \
    \( "$ICON_PATH" -resize 68x68 \) -gravity northwest -geometry +14+14 -composite \
    -font "$BODY_FONT" -weight 700 -fill "$text_color" -pointsize 34 \
    -gravity northwest -annotate +100+35 "KeeNotes" \
    "$out"
}

make_pill() {
  local out="$1"
  local label="$2"
  local fill="$3"
  local text_color="$4"
  local width="$5"

  magick -size "${width}x52" xc:none \
    \( -size "${width}x52" xc:none \
       -fill "$fill" \
       -draw "roundrectangle 0,0 $((width - 1)),51 26,26" \) \
    -composite \
    -font "$BODY_FONT" -weight 700 -fill "$text_color" -pointsize 18 \
    -gravity center -annotate +0+0 "$label" \
    "$out"
}

make_text_block() {
  local out="$1"
  local text="$2"
  local font="$3"
  local color="$4"
  local pointsize="$5"
  local weight="$6"
  local interline="$7"
  local kerning="$8"

  magick -background none \
    -fill "$color" \
    -font "$font" \
    -weight "$weight" \
    -pointsize "$pointsize" \
    -interline-spacing "$interline" \
    -kerning "$kerning" \
    label:"$text" \
    "$out"
}

make_shadow_plate() {
  local out="$1"
  local width="$2"
  local height="$3"
  local radius="$4"
  local fill="$5"
  local blur="$6"

  magick -size "${width}x${height}" xc:none \
    -fill "$fill" \
    -draw "roundrectangle 0,0 $((width - 1)),$((height - 1)) ${radius},${radius}" \
    -blur "0x${blur}" \
    "$out"
}

make_screenshot_card() {
  local out="$1"
  local src="$2"
  local width="$3"
  local height="$4"
  local radius="$5"
  local stroke_color="$6"
  local stroke_width="$7"

  local key
  key="$(basename "${out%.png}")"
  local resized="$TMP_DIR/${key}-resized.png"
  local mask="$TMP_DIR/${key}-mask.png"
  local rounded="$TMP_DIR/${key}-rounded.png"
  local stroke="$TMP_DIR/${key}-stroke.png"

  magick "$src" \
    -resize "${width}x${height}^" \
    -gravity center \
    -extent "${width}x${height}" \
    "$resized"

  magick -size "${width}x${height}" xc:none \
    -fill white \
    -draw "roundrectangle 0,0 $((width - 1)),$((height - 1)) ${radius},${radius}" \
    "$mask"

  magick "$resized" "$mask" \
    -alpha off \
    -compose copy_opacity \
    -composite \
    "$rounded"

  magick -size "${width}x${height}" xc:none \
    -fill none \
    -stroke "$stroke_color" \
    -strokewidth "$stroke_width" \
    -draw "roundrectangle 2,2 $((width - 3)),$((height - 3)) $((radius - 1)),$((radius - 1))" \
    "$stroke"

  magick "$rounded" "$stroke" -compose over -composite "$out"
}

make_panorama_background() {
  local out="$1"

  magick -size "${PANORAMA_WIDTH}x${HEIGHT}" xc:"#F4F6FB" \
    \( -size 2050x2300 xc:none \
       -fill "#E7ECF7" \
       -draw "roundrectangle 0,0 2049,2299 140,140" \
       -background none -rotate -4 \) \
    -gravity northwest -geometry -320+80 -composite \
    \( -size 780x780 xc:none \
       -fill "rgba(108,149,255,0.16)" \
       -draw "circle 390,390 390,0" \) \
    -gravity northwest -geometry +1170-110 -composite \
    \( -size 1100x1100 xc:none \
       -fill "rgba(123,164,255,0.16)" \
       -draw "circle 550,550 550,0" \) \
    -gravity northwest -geometry +2970+90 -composite \
    \( -size 720x720 xc:none \
       -fill "rgba(255,255,255,0.70)" \
       -draw "circle 360,360 360,0" \) \
    -gravity northwest -geometry -140+2235 -composite \
    -stroke "rgba(94,133,226,0.08)" -strokewidth 32 -fill none \
    -draw "circle 3020,1820 3880,1820" \
    -draw "circle 3020,1820 3740,1820" \
    -draw "circle 3020,1820 3600,1820" \
    "$out"
}

crop_panorama_slice() {
  local index="$1"
  local out="$2"
  local x_offset=$((index * WIDTH))

  magick "$PANORAMA_BG" -crop "${WIDTH}x${HEIGHT}+${x_offset}+0" +repage "$out"
}

make_dark_background() {
  local out="$1"

  magick -size "${WIDTH}x${HEIGHT}" xc:"#071D4C" \
    \( -size 980x980 xc:none \
       -fill "rgba(145,176,255,0.24)" \
       -draw "circle 490,490 490,0" \) \
    -gravity northeast -geometry -70+110 -composite \
    \( -size 560x560 xc:none \
       -fill "rgba(17,83,215,0.34)" \
       -draw "circle 280,280 280,0" \) \
    -gravity southwest -geometry -70-280 -composite \
    "$out"
}

composite_slide() {
  local base="$1"
  local out="$2"
  shift 2

  local cmd=(magick "$base")
  while [[ $# -gt 0 ]]; do
    local overlay="$1"
    local x="$2"
    local y="$3"
    cmd+=("$overlay" -gravity northwest -geometry "+${x}+${y}" -composite)
    shift 3
  done
  cmd+=("PNG24:$out")
  "${cmd[@]}"
}

render_slide_01() {
  local base="$TMP_DIR/slide-01-bg.png"
  local badge="$TMP_DIR/slide-01-badge.png"
  local eyebrow="$TMP_DIR/slide-01-eyebrow.png"
  local title="$TMP_DIR/slide-01-title.png"
  local body="$TMP_DIR/slide-01-body.png"
  local shot="$TMP_DIR/slide-01-shot.png"
  local shadow="$TMP_DIR/slide-01-shadow.png"
  local out="$OUTPUT_DIR/01-capture-thoughts-before-they-fade.png"

  crop_panorama_slice 0 "$base"
  make_brand_badge "$badge" "#091939" "rgba(255,255,255,0.84)" "rgba(225,232,243,0.90)"
  make_pill "$eyebrow" "QUICK CAPTURE" "rgba(31,97,255,0.10)" "#245BD8" 198
  make_text_block "$title" $'Capture thoughts\nbefore they fade' "$TITLE_FONT" "#071A44" 94 800 -10 -2.0
  make_text_block "$body" $'A calm place for ideas,\nnotes, and fragments.' "$BODY_FONT" "#5F7299" 31 500 8 0
  make_shadow_plate "$shadow" 920 1998 62 "rgba(183,196,221,0.62)" 22
  make_screenshot_card "$shot" "$NOTE_SRC" 920 1998 62 "rgba(255,255,255,0.94)" 4

  composite_slide "$base" "$out" \
    "$badge" 96 114 \
    "$eyebrow" 96 260 \
    "$title" 96 354 \
    "$body" 96 650 \
    "$shadow" 222 888 \
    "$shot" 204 850
}

render_slide_02() {
  local base="$TMP_DIR/slide-02-bg.png"
  local badge="$TMP_DIR/slide-02-badge.png"
  local eyebrow="$TMP_DIR/slide-02-eyebrow.png"
  local title="$TMP_DIR/slide-02-title.png"
  local body="$TMP_DIR/slide-02-body.png"
  local shot="$TMP_DIR/slide-02-shot.png"
  local shadow="$TMP_DIR/slide-02-shadow.png"
  local out="$OUTPUT_DIR/02-review-your-days-at-a-glance.png"

  crop_panorama_slice 1 "$base"
  make_brand_badge "$badge" "#091939" "rgba(255,255,255,0.84)" "rgba(225,232,243,0.90)"
  make_pill "$eyebrow" "TIME VIEW" "rgba(31,97,255,0.10)" "#245BD8" 154
  make_text_block "$title" $'Review your days\nat a glance' "$TITLE_FONT" "#071A44" 90 800 -10 -1.8
  make_text_block "$body" $'Scan recent notes by week,\nmonth, or all time.' "$BODY_FONT" "#5F7299" 31 500 8 0
  make_shadow_plate "$shadow" 920 1998 62 "rgba(183,196,221,0.60)" 22
  make_screenshot_card "$shot" "$REVIEW_SRC" 920 1998 62 "rgba(255,255,255,0.94)" 4

  composite_slide "$base" "$out" \
    "$badge" 96 114 \
    "$eyebrow" 96 260 \
    "$title" 96 354 \
    "$body" 96 646 \
    "$shadow" 228 896 \
    "$shot" 210 858
}

render_slide_03() {
  local base="$TMP_DIR/slide-03-bg.png"
  local badge="$TMP_DIR/slide-03-badge.png"
  local eyebrow="$TMP_DIR/slide-03-eyebrow.png"
  local title="$TMP_DIR/slide-03-title.png"
  local body="$TMP_DIR/slide-03-body.png"
  local shot="$TMP_DIR/slide-03-shot.png"
  local shadow="$TMP_DIR/slide-03-shadow.png"
  local out="$OUTPUT_DIR/03-find-old-notes-in-seconds.png"

  crop_panorama_slice 2 "$base"
  make_brand_badge "$badge" "#091939" "rgba(255,255,255,0.84)" "rgba(225,232,243,0.90)"
  make_pill "$eyebrow" "SEARCH" "rgba(31,97,255,0.10)" "#245BD8" 118
  make_text_block "$title" $'Find old notes\nin seconds' "$TITLE_FONT" "#071A44" 88 800 -10 -1.8
  make_text_block "$body" $'Search your history the moment\nyou need it.' "$BODY_FONT" "#5F7299" 31 500 8 0
  make_shadow_plate "$shadow" 840 1826 58 "rgba(183,196,221,0.56)" 20
  make_screenshot_card "$shot" "$SEARCH_SRC" 840 1826 58 "rgba(255,255,255,0.94)" 4

  composite_slide "$base" "$out" \
    "$badge" 96 114 \
    "$eyebrow" 96 260 \
    "$title" 96 356 \
    "$body" 96 642 \
    "$shadow" 384 1014 \
    "$shot" 362 980
}

render_slide_04() {
  local base="$TMP_DIR/slide-04-bg.png"
  local badge="$TMP_DIR/slide-04-badge.png"
  local eyebrow="$TMP_DIR/slide-04-eyebrow.png"
  local title="$TMP_DIR/slide-04-title.png"
  local body="$TMP_DIR/slide-04-body.png"
  local shot="$TMP_DIR/slide-04-shot.png"
  local shadow="$TMP_DIR/slide-04-shadow.png"
  local out="$OUTPUT_DIR/04-private-notes-by-design.png"

  make_dark_background "$base"
  make_brand_badge "$badge" "#F6F8FF" "rgba(255,255,255,0.10)" "rgba(255,255,255,0.12)"
  make_pill "$eyebrow" "PRIVACY FIRST" "rgba(255,255,255,0.14)" "#F4F7FF" 186
  make_text_block "$title" $'Private notes.\nBy design.' "$TITLE_FONT" "#F7F9FF" 88 800 -10 -1.8
  make_text_block "$body" $'End-to-end encryption\nkeeps your words yours.' "$BODY_FONT" "#CFDAF6" 31 500 8 0
  make_shadow_plate "$shadow" 820 1782 60 "rgba(2,10,28,0.66)" 30
  make_screenshot_card "$shot" "$SETTINGS_SRC" 820 1782 60 "rgba(255,255,255,0.46)" 3

  composite_slide "$base" "$out" \
    "$badge" 96 114 \
    "$eyebrow" 96 260 \
    "$title" 96 430 \
    "$body" 96 704 \
    "$shadow" 448 1018 \
    "$shot" 430 980
}

render_review_sheet() {
  local row_01="$TMP_DIR/review-row-01.png"
  local row_02="$TMP_DIR/review-row-02.png"

  magick \
    \( "$OUTPUT_DIR/01-capture-thoughts-before-they-fade.png" -resize 330x717 -background "#E9EEF8" -gravity center -extent 330x717 -bordercolor "#E9EEF8" -border 24 \) \
    \( "$OUTPUT_DIR/02-review-your-days-at-a-glance.png" -resize 330x717 -background "#E9EEF8" -gravity center -extent 330x717 -bordercolor "#E9EEF8" -border 24 \) \
    +append \
    "$row_01"

  magick \
    \( "$OUTPUT_DIR/03-find-old-notes-in-seconds.png" -resize 330x717 -background "#E9EEF8" -gravity center -extent 330x717 -bordercolor "#E9EEF8" -border 24 \) \
    \( "$OUTPUT_DIR/04-private-notes-by-design.png" -resize 330x717 -background "#E9EEF8" -gravity center -extent 330x717 -bordercolor "#E9EEF8" -border 24 \) \
    +append \
    "$row_02"

  magick \
    \( "$row_01" \) \
    \( "$row_02" \) \
    -append \
    "$SCRIPT_DIR/output/review-sheet.jpg"
}

make_panorama_background "$PANORAMA_BG"
render_slide_01
render_slide_02
render_slide_03
render_slide_04
render_review_sheet

printf 'Generated App Store screenshots in %s\n' "$OUTPUT_DIR"
