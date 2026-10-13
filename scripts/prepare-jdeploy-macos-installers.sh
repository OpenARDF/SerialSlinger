#!/usr/bin/env bash

set -euo pipefail

fail() {
	echo "ERROR: $*" >&2
	exit 1
}

[[ $# -eq 1 ]] || fail "Usage: $0 <release-files-directory>"

repo_root="$(cd "$(dirname "$0")/.." && pwd)"
release_files_dir="$(cd "$1" && pwd)"
icon_png="$repo_root/icon.png"
icon_icns="$repo_root/shared/packaging/icons/SerialSlinger.icns"
launcher_name="SerialSlinger"

[[ -f "$icon_png" ]] || fail "$icon_png must exist."
[[ -f "$icon_icns" ]] || fail "$icon_icns must exist."

for tool in codesign python3 tar; do
	command -v "$tool" >/dev/null 2>&1 || fail "Missing required tool: $tool"
done
[[ -x /usr/libexec/PlistBuddy ]] || fail "/usr/libexec/PlistBuddy is required."

tmp_dir="$(mktemp -d)"
cleanup() {
	rm -rf "$tmp_dir"
}
trap cleanup EXIT

patch_installer_asset() {
	local archive_path="$1"
	local asset_name
	local work_dir
	local unpack_dir
	local verify_dir
	local top_dir
	local app_dir
	local app_xml
	local archived_app

	asset_name="$(basename "$archive_path")"
	work_dir="$tmp_dir/${asset_name%.tgz}"
	unpack_dir="$work_dir/unpack"
	verify_dir="$work_dir/verify"
	mkdir -p "$unpack_dir" "$verify_dir"

	tar -xzf "$archive_path" -C "$unpack_dir"
	top_dir="$(find "$unpack_dir" -mindepth 1 -maxdepth 1 -type d | head -n1)"
	[[ -n "$top_dir" ]] || fail "Could not find the extracted installer directory for $asset_name."
	app_dir="$(find "$top_dir" -name '*.app' -type d | head -n1)"
	[[ -n "$app_dir" ]] || fail "Could not find an app bundle in $asset_name."

	app_xml="$app_dir/Contents/app.xml"
	[[ -f "$app_xml" ]] || fail "Could not find installer app.xml in $asset_name."
	[[ -f "$app_dir/Contents/Info.plist" ]] || fail "Could not find Info.plist in $asset_name."
	mkdir -p "$app_dir/Contents/Resources"
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
pattern = re.compile(r"icon=(['\"])data:image/png;base64,[^'\"]*\1")
if not pattern.search(text):
    raise SystemExit(f"No embedded icon data URI found in {app_xml}")
app_xml.write_text(pattern.sub("icon='" + data_uri + "'", text, count=1))
PY

	if [[ -f "$app_dir/Contents/MacOS/Client4JLauncher" ]]; then
		[[ ! -e "$app_dir/Contents/MacOS/$launcher_name" ]] ||
			fail "$asset_name contains both Client4JLauncher and $launcher_name."
		mv "$app_dir/Contents/MacOS/Client4JLauncher" "$app_dir/Contents/MacOS/$launcher_name"
	fi
	[[ -x "$app_dir/Contents/MacOS/$launcher_name" ]] ||
		fail "$asset_name does not contain an executable $launcher_name launcher."
	/usr/libexec/PlistBuddy -c "Set :CFBundleExecutable $launcher_name" "$app_dir/Contents/Info.plist"

	# jDeploy and this branding step customize a generic wrapper. Apply the final
	# ad-hoc signature only after every bundle mutation, then verify both the live
	# bundle and a fresh extraction of the archive that will actually be uploaded.
	codesign --force --deep --sign - "$app_dir"
	codesign --verify --deep --strict --verbose=2 "$app_dir"
	(
		cd "$unpack_dir"
		tar -czf "$work_dir/patched.tgz" "$(basename "$top_dir")"
	)
	mv "$work_dir/patched.tgz" "$archive_path"

	tar -xzf "$archive_path" -C "$verify_dir"
	archived_app="$(find "$verify_dir" -name '*.app' -type d | head -n1)"
	[[ -n "$archived_app" ]] || fail "The rewritten $asset_name does not contain an app bundle."
	codesign --verify --deep --strict --verbose=2 "$archived_app"
	echo "Prepared and round-trip verified $asset_name"
}

shopt -s nullglob
installer_assets=("$release_files_dir"/SerialSlinger.Installer-mac-*.tgz)
((${#installer_assets[@]} > 0)) || fail "No macOS installer assets were found in $release_files_dir."

for archive_path in "${installer_assets[@]}"; do
	patch_installer_asset "$archive_path"
done

# Include the canonical icon in the immutable versioned assets. The moving
# jDeploy metadata release is updated only after the versioned release succeeds.
cp "$icon_png" "$release_files_dir/icon.png"
