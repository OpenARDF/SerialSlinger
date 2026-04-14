#!/usr/bin/env bash
set -euo pipefail

if [[ $# -ne 1 ]]; then
  echo "Usage: $0 <release-tag>" >&2
  exit 1
fi

release_tag="$1"
repo="${GITHUB_REPOSITORY:-OpenARDF/SerialSlinger}"

if [[ ! -f icon.png ]]; then
  echo "icon.png must exist in the repository root" >&2
  exit 1
fi

for tool in gh iconutil sips tar; do
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

build_icns() {
  local iconset_dir="$tmp_dir/icon.iconset"
  rm -rf "$iconset_dir"
  mkdir -p "$iconset_dir"

  for size in 16 32 128 256 512; do
    sips -z "$size" "$size" icon.png --out "$iconset_dir/icon_${size}x${size}.png" >/dev/null
    sips -z "$(( size * 2 ))" "$(( size * 2 ))" icon.png --out "$iconset_dir/icon_${size}x${size}@2x.png" >/dev/null
  done

  iconutil -c icns "$iconset_dir" -o "$tmp_dir/icon.icns"
}

patch_installer_asset() {
  local asset_name="$1"
  local asset_dir="$tmp_dir/$asset_name"
  local unpack_dir="$asset_dir/unpack"
  local archive_path="$asset_dir/$asset_name"
  local top_dir
  local app_dir

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

  cp "$tmp_dir/icon.icns" "$app_dir/Contents/Resources/icon.icns"
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

build_icns

# Keep the special jdeploy release aligned with the real app icon so the public
# download page has a canonical icon asset to pick up.
gh release upload jdeploy icon.png --repo "$repo" --clobber >/dev/null
gh release upload "$release_tag" icon.png --repo "$repo" --clobber >/dev/null

installer_assets="$(gh release view "$release_tag" --repo "$repo" --json assets --jq '.assets[].name' | grep -E '^SerialSlinger\.Installer-mac-.*\.tgz$' || true)"
if [[ -z "$installer_assets" ]]; then
  echo "No macOS installer assets found on $release_tag" >&2
  exit 1
fi

while IFS= read -r asset_name; do
  [[ -n "$asset_name" ]] || continue
  patch_installer_asset "$asset_name"
done <<<"$installer_assets"
