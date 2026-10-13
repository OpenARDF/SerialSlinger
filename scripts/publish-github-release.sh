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
[[ -n "${SERIALSLINGER_RELEASE_COMMIT:-}" ]] || fail "SERIALSLINGER_RELEASE_COMMIT is required."
[[ -n "${GITHUB_REPOSITORY:-}" ]] || fail "GITHUB_REPOSITORY is required."
[[ -n "${GH_TOKEN:-}" ]] || fail "GH_TOKEN is required."

for command_name in gh git node; do
	command -v "$command_name" >/dev/null 2>&1 || fail "$command_name is required."
done

release_files_dir="$(cd "$1" && pwd)"
release_tag="$SERIALSLINGER_RELEASE_TAG"
release_commit="$SERIALSLINGER_RELEASE_COMMIT"
release_notes_path="docs/release-notes/$release_tag.md"
[[ -s "$release_files_dir/release-manifest.json" ]] || fail "release-manifest.json is missing."
[[ -s "$release_notes_path" ]] || fail "$release_notes_path is missing or empty."
node scripts/release-manifest.mjs verify --directory "$release_files_dir"

git cat-file -e "$release_commit^{commit}" || fail "Release commit $release_commit is not available."
git merge-base --is-ancestor "$release_commit" origin/main ||
	fail "Release commit $release_commit is not part of protected main."

existing_tag_commit="$(git rev-parse -q --verify "refs/tags/$release_tag^{commit}" 2>/dev/null || true)"
if [[ -n "$existing_tag_commit" ]]; then
	[[ "$existing_tag_commit" == "$release_commit" ]] ||
		fail "$release_tag already points to $existing_tag_commit instead of $release_commit."
else
	git config user.name "github-actions[bot]"
	git config user.email "41898282+github-actions[bot]@users.noreply.github.com"
	git tag -a "$release_tag" "$release_commit" -m "SerialSlinger ${release_tag#v}"
	git push origin "refs/tags/$release_tag"
fi

existing_release_state="$(gh release view "$release_tag" --repo "$GITHUB_REPOSITORY" --json isDraft,isPrerelease --jq '[.isDraft,.isPrerelease] | @tsv' 2>/dev/null || true)"
if [[ "$existing_release_state" == $'false\tfalse' ]]; then
	readback_dir="$(mktemp -d)"
	trap 'rm -rf "$readback_dir"' EXIT
	gh release download "$release_tag" --repo "$GITHUB_REPOSITORY" -D "$readback_dir"
	node scripts/release-manifest.mjs verify --directory "$readback_dir"
	echo "$release_tag is already public and matches the proven manifest; no immutable assets were changed."
	exit 0
fi
if [[ -z "$existing_release_state" ]]; then
	gh release create "$release_tag" \
		--repo "$GITHUB_REPOSITORY" \
		--verify-tag \
		--draft \
		--title "$release_tag" \
		--notes-file "$release_notes_path"
elif [[ "$existing_release_state" != $'true\tfalse' ]]; then
	fail "$release_tag exists in an unsupported draft/prerelease state: $existing_release_state"
fi

shopt -s nullglob
release_files=("$release_files_dir"/*)
((${#release_files[@]} > 0)) || fail "No staged release assets were found."
gh release upload "$release_tag" "${release_files[@]}" --repo "$GITHUB_REPOSITORY" --clobber

readback_dir="$(mktemp -d)"
cleanup() {
	rm -rf "$readback_dir"
}
trap cleanup EXIT
gh release download "$release_tag" --repo "$GITHUB_REPOSITORY" -D "$readback_dir"
node scripts/release-manifest.mjs verify --directory "$readback_dir"

gh release edit "$release_tag" \
	--repo "$GITHUB_REPOSITORY" \
	--draft=false \
	--prerelease=false \
	--notes-file "$release_notes_path"

release_state="$(gh release view "$release_tag" --repo "$GITHUB_REPOSITORY" --json isDraft,isPrerelease --jq '[.isDraft,.isPrerelease] | @tsv')"
[[ "$release_state" == $'false\tfalse' ]] || fail "$release_tag did not become a stable public release."
echo "Published and independently read back $release_tag from $release_commit."
