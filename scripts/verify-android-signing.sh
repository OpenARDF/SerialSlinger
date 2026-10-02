#!/usr/bin/env bash

set -euo pipefail

repo_root="$(cd "$(dirname "$0")/.." && pwd)"
bundle_path="${1:-$repo_root/androidApp/build/outputs/bundle/release/androidApp-release.aab}"
expected_sha256="${SERIALSLINGER_UPLOAD_CERT_SHA256:-}"

if [[ -z "$expected_sha256" && -f "$repo_root/keystore.properties" ]]; then
	expected_sha256="$(sed -n 's/^certificateSha256[[:space:]]*=[[:space:]]*//p' "$repo_root/keystore.properties" | tail -n 1)"
fi
if [[ -z "$expected_sha256" && -f "$repo_root/docs/android-upload-certificate.sha256" ]]; then
	expected_sha256="$(head -n 1 "$repo_root/docs/android-upload-certificate.sha256")"
fi

if [[ ! -f "$bundle_path" ]]; then
	echo "Android App Bundle does not exist: $bundle_path" >&2
	exit 1
fi
verification_output="$(jarsigner -verify -verbose -certs "$bundle_path" 2>&1)"
if ! grep -q '^jar verified\.$' <<<"$verification_output"; then
	echo "$verification_output" >&2
	echo "Android App Bundle signature verification failed." >&2
	exit 1
fi

actual_sha256="$({ keytool -printcert -jarfile "$bundle_path" 2>/dev/null || true; } | sed -n 's/^[[:space:]]*SHA256:[[:space:]]*//p' | head -n 1)"
normalize_fingerprint() {
	tr '[:lower:]' '[:upper:]' <<<"$1" | tr -d '[:space:]:'
}

if [[ -z "$actual_sha256" ]]; then
	echo "Could not read a signing certificate from $bundle_path." >&2
	exit 1
fi
if [[ -z "$expected_sha256" ]]; then
	echo "Expected upload certificate is not configured." >&2
	echo "Signed bundle certificate SHA-256: $actual_sha256" >&2
	echo "Confirm it against Play Console, then record it in docs/android-upload-certificate.sha256." >&2
	exit 1
fi
if [[ "$(normalize_fingerprint "$actual_sha256")" != "$(normalize_fingerprint "$expected_sha256")" ]]; then
	echo "Android upload certificate mismatch." >&2
	echo "Expected SHA-256: $expected_sha256" >&2
	echo "Actual SHA-256:   $actual_sha256" >&2
	exit 1
fi

echo "Android App Bundle signature and upload certificate verified."
echo "Upload certificate SHA-256: $actual_sha256"
