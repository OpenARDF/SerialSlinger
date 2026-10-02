#!/usr/bin/env bash

set -euo pipefail

repo_root="$(cd "$(dirname "$0")/.." && pwd)"
properties_file="$repo_root/keystore.properties"
login_keychain="$HOME/Library/Keychains/login.keychain-db"
store_service="SerialSlingerUploadStorePassword"
keychain_account="$(id -un)"

cleanup() {
	unset SERIALSLINGER_UPLOAD_STORE_PASSWORD
	unset SERIALSLINGER_UPLOAD_KEY_PASSWORD
}

trap cleanup EXIT
cd "$repo_root"

# Prefer a complete ignored, owner-readable properties file. It avoids a
# Keychain authorization dialog while keeping credentials outside version control.
properties_passwords_complete=false
if [[ -f "$properties_file" ]]; then
	if grep -Eq '^storePassword[[:space:]]*=[[:space:]]*[^[:space:]]' "$properties_file" &&
		grep -Eq '^keyPassword[[:space:]]*=[[:space:]]*[^[:space:]]' "$properties_file"; then
		properties_passwords_complete=true
	fi
fi

if [[ "$properties_passwords_complete" == false ]]; then
	export SERIALSLINGER_UPLOAD_STORE_FILE="${SERIALSLINGER_UPLOAD_STORE_FILE:-$HOME/.android/keystores/SerialSlinger-upload.jks}"
	export SERIALSLINGER_UPLOAD_KEY_ALIAS="${SERIALSLINGER_UPLOAD_KEY_ALIAS:-serialslinger-upload}"

	if [[ ! -f "$SERIALSLINGER_UPLOAD_STORE_FILE" ]]; then
		echo "Android upload keystore was not found: $SERIALSLINGER_UPLOAD_STORE_FILE" >&2
		exit 1
	fi

	if [[ -z "${SERIALSLINGER_UPLOAD_STORE_PASSWORD:-}" ]]; then
		if [[ "$(uname -s)" != "Darwin" ]]; then
			echo "Set SERIALSLINGER_UPLOAD_STORE_PASSWORD when keystore.properties lacks passwords." >&2
			exit 1
		fi
		if ! SERIALSLINGER_UPLOAD_STORE_PASSWORD="$(security find-generic-password \
			-a "$keychain_account" -s "$store_service" -w "$login_keychain" 2>/dev/null)"; then
			echo "Keychain entry '$store_service' for account '$keychain_account' could not be read." >&2
			exit 1
		fi
		export SERIALSLINGER_UPLOAD_STORE_PASSWORD
	fi

	if [[ -z "${SERIALSLINGER_UPLOAD_KEY_PASSWORD:-}" ]]; then
		# The established PKCS#12 upload keystore uses one password for both the
		# store and private key. A future split-password key can override this.
		export SERIALSLINGER_UPLOAD_KEY_PASSWORD="$SERIALSLINGER_UPLOAD_STORE_PASSWORD"
	fi
fi

./gradlew printAndroidReleaseSigningStatus
./gradlew :androidApp:bundleRelease "$@"
./scripts/verify-android-signing.sh
