#!/usr/bin/env bash
set -euo pipefail

if [[ $# -ne 1 ]]; then
  echo "Usage: $0 <release-tag>" >&2
  exit 1
fi

release_tag="$1"
repo="${GITHUB_REPOSITORY:-OpenARDF/SerialSlinger}"

icon_png="icon.png"
icon_icns="shared/packaging/icons/SerialSlinger.icns"

if [[ ! -f "$icon_png" ]]; then
  echo "$icon_png must exist in the repository root" >&2
  exit 1
fi

if [[ ! -f "$icon_icns" ]]; then
  echo "$icon_icns must exist in the repository" >&2
  exit 1
fi

for tool in gh python3 tar; do
  if ! command -v "$tool" >/dev/null 2>&1; then
    echo "Missing required tool: $tool" >&2
    exit 1
  fi
done

tmp_dir="$(mktemp -d)"
launcher_name="SerialSlinger"
cleanup() {
  rm -rf "$tmp_dir"
}
trap cleanup EXIT

patch_installer_asset() {
  local asset_name="$1"
  local asset_dir="$tmp_dir/$asset_name"
  local unpack_dir="$asset_dir/unpack"
  local archive_path="$asset_dir/$asset_name"
  local top_dir
  local app_dir
  local app_xml

  mkdir -p "$asset_dir" "$unpack_dir"

  gh release download "$release_tag" --repo "$repo" -p "$asset_name" -D "$asset_dir" >/dev/null
  tar -xzf "$archive_path" -C "$unpack_dir"

  top_dir="$(find "$unpack_dir" -mindepth 1 -maxdepth 1 -type d | head -n1)"
  if [[ -z "$top_dir" ]]; then
    echo "Could not find extracted installer directory for $asset_name" >&2
    exit 1
  fi

  app_dir="$(find "$top_dir" -name '*.app' -type d | head -n1)"
  if [[ -z "$app_dir" ]]; then
    echo "Could not find installer app bundle for $asset_name" >&2
    exit 1
  fi

  app_xml="$app_dir/Contents/app.xml"
  if [[ ! -f "$app_xml" ]]; then
    echo "Could not find installer app.xml for $asset_name" >&2
    exit 1
  fi

  cp "$icon_icns" "$app_dir/Contents/Resources/icon.icns"
  python3 - "$app_xml" "$icon_png" <<'PY'
import base64
import re
import sys
from pathlib import Path

app_xml = Path(sys.argv[1])
icon_png = Path(sys.argv[2])
data_uri = "data:image/png;base64," + base64.b64encode(icon_png.read_bytes()).decode("ascii")
text = app_xml.read_text()
updated = re.sub(r"icon=(['\"])data:image/png;base64,[^'\"]*\1", "icon='" + data_uri + "'", text, count=1)
if updated == text:
    raise SystemExit(f"No embedded icon data URI found in {app_xml}")
app_xml.write_text(updated)
PY
  if [[ -f "$app_dir/Contents/MacOS/Client4JLauncher" ]]; then
    mv "$app_dir/Contents/MacOS/Client4JLauncher" "$app_dir/Contents/MacOS/$launcher_name"
    /usr/libexec/PlistBuddy -c "Set :CFBundleExecutable $launcher_name" "$app_dir/Contents/Info.plist"
  fi
  (
    cd "$unpack_dir"
    tar -czf "$asset_dir/patched.tgz" "$(basename "$top_dir")"
  )
  mv "$asset_dir/patched.tgz" "$archive_path"
  gh release upload "$release_tag" "$archive_path" --repo "$repo" --clobber >/dev/null
  echo "Patched $asset_name"
}

# Keep the special jdeploy release aligned with the real app icon so the public
# download page has a canonical icon asset to pick up.
gh release upload jdeploy "$icon_png" --repo "$repo" --clobber >/dev/null
gh release upload "$release_tag" "$icon_png" --repo "$repo" --clobber >/dev/null

installer_assets="$(gh release view "$release_tag" --repo "$repo" --json assets --jq '.assets[].name' | grep -E '^SerialSlinger\.Installer-mac-.*\.tgz$' || true)"
if [[ -z "$installer_assets" ]]; then
  echo "No macOS installer assets found on $release_tag" >&2
  exit 1
fi

while IFS= read -r asset_name; do
  [[ -n "$asset_name" ]] || continue
  patch_installer_asset "$asset_name"
done <<<"$installer_assets"
