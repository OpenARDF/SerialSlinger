#!/usr/bin/env bash

set -euo pipefail

fail() {
	echo "ERROR: $*" >&2
	exit 1
}

[[ "${SERIALSLINGER_ALLOW_GITHUB_RELEASE_PUBLISH:-}" == "1" ]] ||
	fail "Set SERIALSLINGER_ALLOW_GITHUB_RELEASE_PUBLISH=1 inside the release workflow."
[[ "${GITHUB_REF_TYPE:-}" == "tag" ]] || fail "GitHub release publication is allowed only for a tag workflow."
[[ -n "${GITHUB_REF_NAME:-}" ]] || fail "GITHUB_REF_NAME is required."
[[ -n "${GITHUB_REPOSITORY:-}" ]] || fail "GITHUB_REPOSITORY is required."
[[ -n "${GH_TOKEN:-}" ]] || fail "GH_TOKEN is required."

for command_name in gh jq; do
	command -v "$command_name" >/dev/null 2>&1 || fail "$command_name is required."
done

release_files_dir="jdeploy/github-release-files"
package_info_path="$release_files_dir/package-info.json"
release_notes_path="$release_files_dir/jdeploy-release-notes.md"
[[ -s "$package_info_path" ]] || fail "$package_info_path is missing or empty."
[[ -s "$release_notes_path" ]] || fail "$release_notes_path is missing or empty."
jq -e 'type == "object"' "$package_info_path" >/dev/null || fail "$package_info_path is not a JSON object."

temporary_dir="$(mktemp -d)"
trap 'rm -rf "$temporary_dir"' EXIT
metadata_release_json="$temporary_dir/jdeploy-release.json"
package_info_backup="$temporary_dir/package-info-2.json"
cp "$package_info_path" "$package_info_backup"

# Require the existing metadata release so a typo cannot replace the application's version history.
gh api "repos/$GITHUB_REPOSITORY/releases/tags/jdeploy" >"$metadata_release_json" ||
	fail "The existing jdeploy metadata release could not be read."
metadata_release_id="$(jq -r '.id // empty' "$metadata_release_json")"
primary_asset_id="$(jq -r '.assets[] | select(.name == "package-info.json") | .id' "$metadata_release_json")"
primary_digest="$(jq -r '.assets[] | select(.name == "package-info.json") | .digest // empty' "$metadata_release_json")"
backup_asset_id="$(jq -r '.assets[] | select(.name == "package-info-2.json") | .id' "$metadata_release_json")"
[[ -n "$metadata_release_id" && -n "$primary_asset_id" && -n "$backup_asset_id" ]] ||
	fail "The jdeploy metadata release is incomplete; both package-info assets must exist."

current_digest="$(gh api "repos/$GITHUB_REPOSITORY/releases/$metadata_release_id" --jq \
	'.assets[] | select(.name == "package-info.json") | .digest // empty')"
if [[ -n "$primary_digest" && "$current_digest" != "$primary_digest" ]]; then
	fail "The jdeploy metadata changed during publication; rerun the workflow."
fi

gh api --method DELETE "repos/$GITHUB_REPOSITORY/releases/assets/$primary_asset_id"
gh release upload jdeploy "$package_info_path" --repo "$GITHUB_REPOSITORY"
gh api --method DELETE "repos/$GITHUB_REPOSITORY/releases/assets/$backup_asset_id"
gh release upload jdeploy "$package_info_backup" --repo "$GITHUB_REPOSITORY"

if ! gh release view "$GITHUB_REF_NAME" --repo "$GITHUB_REPOSITORY" >/dev/null 2>&1; then
	gh release create "$GITHUB_REF_NAME" --repo "$GITHUB_REPOSITORY" --draft --title "$GITHUB_REF_NAME"
fi

shopt -s nullglob
release_files=("$release_files_dir"/*)
((${#release_files[@]} > 0)) || fail "jDeploy did not produce release assets."
gh release upload "$GITHUB_REF_NAME" "${release_files[@]}" --repo "$GITHUB_REPOSITORY" --clobber
gh release edit "$GITHUB_REF_NAME" --repo "$GITHUB_REPOSITORY" --notes-file "$release_notes_path"

echo "Published jDeploy metadata and ${#release_files[@]} versioned release assets."
