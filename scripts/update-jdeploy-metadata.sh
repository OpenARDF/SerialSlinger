#!/usr/bin/env bash

set -euo pipefail

fail() {
	echo "ERROR: $*" >&2
	exit 1
}

[[ $# -eq 1 ]] || fail "Usage: $0 <release-files-directory>"
[[ "${SERIALSLINGER_ALLOW_GITHUB_RELEASE_PUBLISH:-}" == "1" ]] ||
	fail "Set SERIALSLINGER_ALLOW_GITHUB_RELEASE_PUBLISH=1 inside the protected publication job."
[[ "${SERIALSLINGER_RELEASE_WORKFLOW:-}" == "1" ]] || fail "The guarded release workflow is required."
[[ -n "${SERIALSLINGER_RELEASE_TAG:-}" ]] || fail "SERIALSLINGER_RELEASE_TAG is required."
[[ -n "${GITHUB_REPOSITORY:-}" ]] || fail "GITHUB_REPOSITORY is required."
[[ -n "${GH_TOKEN:-}" ]] || fail "GH_TOKEN is required."

for command_name in gh jq shasum; do
	command -v "$command_name" >/dev/null 2>&1 || fail "$command_name is required."
done

release_files_dir="$(cd "$1" && pwd)"
package_info_path="$release_files_dir/package-info.json"
icon_path="$release_files_dir/icon.png"
[[ -s "$package_info_path" ]] || fail "$package_info_path is missing or empty."
[[ -s "$icon_path" ]] || fail "$icon_path is missing or empty."
jq -e 'type == "object"' "$package_info_path" >/dev/null || fail "$package_info_path is not a JSON object."

release_state="$(gh release view "$SERIALSLINGER_RELEASE_TAG" --repo "$GITHUB_REPOSITORY" --json isDraft,isPrerelease --jq '[.isDraft,.isPrerelease] | @tsv')"
[[ "$release_state" == $'false\tfalse' ]] || fail "The immutable versioned release must be public before metadata changes."

temporary_dir="$(mktemp -d)"
cleanup() {
	rm -rf "$temporary_dir"
}
trap cleanup EXIT

metadata_json="$temporary_dir/jdeploy-release.json"
gh api "repos/$GITHUB_REPOSITORY/releases/tags/jdeploy" >"$metadata_json" ||
	fail "The existing jdeploy metadata release could not be read."
metadata_release_id="$(jq -r '.id // empty' "$metadata_json")"
primary_asset_id="$(jq -r '.assets[] | select(.name == "package-info.json") | .id' "$metadata_json")"
primary_digest="$(jq -r '.assets[] | select(.name == "package-info.json") | .digest // empty' "$metadata_json")"
backup_asset_id="$(jq -r '.assets[] | select(.name == "package-info-2.json") | .id' "$metadata_json")"
[[ -n "$metadata_release_id" && -n "$primary_asset_id" && -n "$backup_asset_id" ]] ||
	fail "The jdeploy metadata release is incomplete; both package-info assets must exist."

old_primary="$temporary_dir/package-info.json"
mkdir -p "$temporary_dir/current"
gh release download jdeploy --repo "$GITHUB_REPOSITORY" -p package-info.json -D "$temporary_dir/current"
mv "$temporary_dir/current/package-info.json" "$old_primary"
current_digest="$(gh api "repos/$GITHUB_REPOSITORY/releases/$metadata_release_id" --jq '.assets[] | select(.name == "package-info.json") | .digest // empty')"
if [[ -n "$primary_digest" && "$current_digest" != "$primary_digest" ]]; then
	fail "The jdeploy metadata changed during publication; rerun the workflow."
fi

# Preserve the last known-good primary as the fallback before replacing the
# primary pointer. Clients therefore retain an old usable metadata copy even if
# the primary upload is interrupted and the job must be retried.
backup_path="$temporary_dir/package-info-2.json"
old_primary_sha256="$(shasum -a 256 "$old_primary" | cut -d ' ' -f1)"
new_primary_sha256="$(shasum -a 256 "$package_info_path" | cut -d ' ' -f1)"
metadata_changed=false
if [[ "$old_primary_sha256" != "$new_primary_sha256" ]]; then
	cp "$old_primary" "$backup_path"
	gh release upload jdeploy "$backup_path" --repo "$GITHUB_REPOSITORY" --clobber
	gh release upload jdeploy "$package_info_path" --repo "$GITHUB_REPOSITORY" --clobber
	metadata_changed=true
fi
gh release upload jdeploy "$icon_path" --repo "$GITHUB_REPOSITORY" --clobber

verification_dir="$temporary_dir/verification"
mkdir -p "$verification_dir"
gh release download jdeploy --repo "$GITHUB_REPOSITORY" -p package-info.json -p package-info-2.json -D "$verification_dir"
[[ "$(shasum -a 256 "$verification_dir/package-info.json" | cut -d ' ' -f1)" == "$(shasum -a 256 "$package_info_path" | cut -d ' ' -f1)" ]] ||
	fail "The published primary jDeploy metadata does not match the new release."
if [[ "$metadata_changed" == true ]]; then
	[[ "$(shasum -a 256 "$verification_dir/package-info-2.json" | cut -d ' ' -f1)" == "$old_primary_sha256" ]] ||
		fail "The published jDeploy fallback does not preserve the prior primary metadata."
else
	jq -e 'type == "object"' "$verification_dir/package-info-2.json" >/dev/null ||
		fail "The existing jDeploy fallback is not valid JSON metadata."
fi

echo "Updated jDeploy metadata after successful publication; the prior primary remains as package-info-2.json."
