#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
APP_ID="com.openardf.serialslinger.androidapp"
MAIN_ACTIVITY="com.openardf.serialslinger.androidapp.MainActivity"
DEFAULT_ANDROID_SDK_ROOT="$HOME/Library/Android/sdk"
TARGET_SERIAL="${1:-${ANDROID_SERIAL:-}}"
APK_PATH="$ROOT_DIR/androidApp/build/outputs/apk/debug/androidApp-debug.apk"

cd "$ROOT_DIR"

if ! command -v adb >/dev/null 2>&1; then
  if [ -x "$DEFAULT_ANDROID_SDK_ROOT/platform-tools/adb" ]; then
    export ANDROID_SDK_ROOT="${ANDROID_SDK_ROOT:-$DEFAULT_ANDROID_SDK_ROOT}"
    export ANDROID_HOME="${ANDROID_HOME:-$ANDROID_SDK_ROOT}"
    export PATH="$ANDROID_SDK_ROOT/platform-tools:$PATH"
  else
    echo "adb was not found on PATH, and no SDK was found at $DEFAULT_ANDROID_SDK_ROOT." >&2
    exit 1
  fi
fi

DEVICE_COUNT="$(adb devices | awk 'NR > 1 && $2 == "device" { count++ } END { print count + 0 }')"

ADB_ARGS=()
if [ -n "$TARGET_SERIAL" ]; then
  ADB_ARGS=(-s "$TARGET_SERIAL")
fi

if [ "$DEVICE_COUNT" -eq 0 ]; then
  echo "No authorized Android device is connected. Plug in the tablet, enable USB debugging, and accept the RSA prompt." >&2
  exit 1
fi

if [ "$DEVICE_COUNT" -gt 1 ] && [ -z "$TARGET_SERIAL" ]; then
  echo "More than one Android device is connected. Pass an adb serial as the first argument or set ANDROID_SERIAL." >&2
  exit 1
fi

./gradlew :androidApp:assembleDebug
adb "${ADB_ARGS[@]}" install -r "$APK_PATH" >/dev/null
adb "${ADB_ARGS[@]}" shell am start -n "${APP_ID}/${MAIN_ACTIVITY}" >/dev/null
echo "SerialSlinger debug app installed and launched."
