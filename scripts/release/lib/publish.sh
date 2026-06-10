#!/usr/bin/env bash
# Upload local release artifacts to GitHub (mirrors release.yml publish steps)

set -euo pipefail

source "$(dirname "${BASH_SOURCE[0]}")/common.sh"

publish_release() {
  local version="$1"
  local tag="v${version}"
  tag="${tag//vv/v}"

  local release_repo="${RELEASE_REPO:-fujohnwang/keenotes-releases}"
  local include_android="${INCLUDE_ANDROID:-0}"

  require_cmd gh

  if [[ -n "${GH_TOKEN:-}" ]]; then
    export GH_TOKEN
  elif [[ -n "${RELEASE_TOKEN:-}" ]]; then
    export GH_TOKEN="$RELEASE_TOKEN"
  fi

  local files=()
  if [[ -d "$DIST_DIR" ]]; then
    while IFS= read -r -d '' f; do
      files+=("$f")
    done < <(find "$DIST_DIR" -maxdepth 1 -type f ! -name '.DS_Store' -print0)
  fi

  if [[ "$include_android" == "1" ]]; then
    while IFS= read -r -d '' f; do
      files+=("$f")
    done < <(find "${REPO_ROOT}/keenotes-android/app/build/outputs" \
      \( -path '*/apk/release/*.apk' -o -path '*/bundle/release/*.aab' \) -type f -print0 2>/dev/null || true)
  fi

  if [[ ${#files[@]} -eq 0 ]]; then
    die "No release artifacts found to upload"
  fi

  log "Publishing ${#files[@]} file(s) to ${release_repo} tag ${tag}..."
  for f in "${files[@]}"; do
    log "  - $(basename "$f")"
  done

  if gh release view "$tag" --repo "$release_repo" >/dev/null 2>&1; then
    gh release upload "$tag" "${files[@]}" --repo "$release_repo" --clobber
  else
    gh release create "$tag" "${files[@]}" \
      --repo "$release_repo" \
      --title "KeeNotes ${version}" \
      --notes "Local release build for $(platform_label "$(detect_platform_id)")"
  fi

  if [[ -n "${KNS_ADMIN_TOKEN:-}" ]]; then
    local release_url="https://github.com/${release_repo}/releases/tag/${tag}"
    log "Updating version info API..."
    curl -fsS -X POST "https://kns.afoo.me/version/latest" \
      -H "Authorization: Bearer ${KNS_ADMIN_TOKEN}" \
      -H "Content-Type: application/json" \
      -d "{\"version\":\"${version}\",\"url\":\"${release_url}\"}"
    log "Version API updated"
  else
    log "Skipping version API update (set KNS_ADMIN_TOKEN to enable)"
  fi

  log "Published to https://github.com/${release_repo}/releases/tag/${tag}"
}

if [[ "${BASH_SOURCE[0]}" == "${0}" ]]; then
  publish_release "${1:?version required}"
fi
