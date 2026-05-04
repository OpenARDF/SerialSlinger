#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
TARGET_SERIAL="${ANDROID_SERIAL:-}"
DEVICE_NAME=""
OUTPUT_DIR="$ROOT_DIR/build/android-regression/$(date +%Y%m%d-%H%M%S)"
APP_ID="com.SerialSlinger.openardf"
MAIN_ACTIVITY="com.SerialSlinger.openardf.MainActivity"
DEFAULT_ANDROID_SDK_ROOT="$HOME/Library/Android/sdk"

usage() {
	cat <<'EOF'
Usage:
  ./scripts/android-regression.sh [--serial <adb-serial>] [--device-name <usb-device-name>] [--output-dir <path>]

Runs a destructive Android tablet regression pass against the attached
SignalSlinger. The pass starts from normal mode by clearing the Android UI
preference file, then exercises normal-mode load, setting submit, schedule,
raw command, log, and clone paths through the debug receiver.
EOF
}

while [ "$#" -gt 0 ]; do
	case "$1" in
	--serial)
		TARGET_SERIAL="${2:-}"
		shift 2
		;;
	--device-name)
		DEVICE_NAME="${2:-}"
		shift 2
		;;
	--output-dir)
		OUTPUT_DIR="${2:-}"
		shift 2
		;;
	-h | --help)
		usage
		exit 0
		;;
	*)
		echo "Unknown argument: $1" >&2
		usage >&2
		exit 1
		;;
	esac
done

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
	echo "No authorized Android device is connected." >&2
	exit 1
fi

if [ "$DEVICE_COUNT" -gt 1 ] && [ -z "$TARGET_SERIAL" ]; then
	echo "More than one Android device is connected. Pass --serial or set ANDROID_SERIAL." >&2
	exit 1
fi

COMMON_ARGS=()
if [ -n "$TARGET_SERIAL" ]; then
	COMMON_ARGS+=(--serial "$TARGET_SERIAL")
fi
if [ -n "$DEVICE_NAME" ]; then
	COMMON_ARGS+=(--device-name "$DEVICE_NAME")
fi

mkdir -p "$OUTPUT_DIR"
SUMMARY_FILE="$OUTPUT_DIR/summary.tsv"
printf 'step\tstatus\tdetails\n' >"$SUMMARY_FILE"

record_pass() {
	printf '%s\tpass\t%s\n' "$1" "${2:-ok}" >>"$SUMMARY_FILE"
}

record_fail() {
	printf '%s\tfail\t%s\n' "$1" "${2:-failed}" >>"$SUMMARY_FILE"
}

run_debug_command() {
	./scripts/android-debug-command.sh "${COMMON_ARGS[@]}" "$@"
}

run_step() {
	local name="$1"
	shift
	local output_file="$OUTPUT_DIR/${name// /-}.txt"

	if "$@" >"$output_file" 2>&1; then
		record_pass "$name" "$output_file"
	else
		record_fail "$name" "$output_file"
		echo "Regression step failed: $name" >&2
		echo "Output: $output_file" >&2
		exit 1
	fi
}

load_with_retry() {
	local output_file="$1"
	for _attempt in 1 2 3 4 5; do
		if run_debug_command load >"$output_file" 2>&1; then
			return 0
		fi
		if ! rg -q "Probe already in progress" "$output_file"; then
			return 1
		fi
		sleep 3
	done
	return 1
}

snapshot_value() {
	local key="$1"
	local snapshot_file="$2"
	awk -F= -v key="$key" '$1 == key { print $2; exit }' "$snapshot_file"
}

future_compact_minutes() {
	local minutes="$1"
	node -e '
const minutes = Number(process.argv[1]);
const d = new Date(Date.now() + minutes * 60 * 1000);
const pad = (value) => String(value).padStart(2, "0");
process.stdout.write(
  pad(d.getFullYear() % 100) +
  pad(d.getMonth() + 1) +
  pad(d.getDate()) +
  pad(d.getHours()) +
  pad(d.getMinutes()) +
  "00"
);
' "$minutes"
}

current_compact_time() {
	node -e '
const d = new Date();
const pad = (value) => String(value).padStart(2, "0");
process.stdout.write(
  pad(d.getFullYear() % 100) +
  pad(d.getMonth() + 1) +
  pad(d.getDate()) +
  pad(d.getHours()) +
  pad(d.getMinutes()) +
  pad(d.getSeconds())
);
'
}

adb "${ADB_ARGS[@]}" shell am force-stop "$APP_ID" >/dev/null || true
adb "${ADB_ARGS[@]}" shell "run-as $APP_ID rm -f shared_prefs/serialslinger_android_ui.xml" >/dev/null 2>&1 || true
adb "${ADB_ARGS[@]}" shell am start -n "$APP_ID/$MAIN_ACTIVITY" >/dev/null
sleep 8
# If the attached SignalSlinger is running, normal UI mode shows an
# event-in-progress confirmation during the startup auto-probe. Dismiss it so
# the automated pass can continue while still exercising the normal-mode dialog.
adb "${ADB_ARGS[@]}" shell input tap 978 588 >/dev/null || true
sleep 8

run_step "normal-load" load_with_retry "$OUTPUT_DIR/normal-load.txt"
run_step "normal-clear-log" run_debug_command clear-log
run_step "normal-get-state" run_debug_command get-state
run_step "normal-get-snapshot" run_debug_command get-snapshot

SNAPSHOT_FILE="$OUTPUT_DIR/normal-get-snapshot.txt"
ORIGINAL_STATION_ID="$(snapshot_value stationId "$SNAPSHOT_FILE")"
ORIGINAL_ID_SPEED="$(snapshot_value idCodeSpeedWpm "$SNAPSHOT_FILE")"
ORIGINAL_PATTERN_SPEED="$(snapshot_value patternCodeSpeedWpm "$SNAPSHOT_FILE")"

if [ -z "$ORIGINAL_STATION_ID" ]; then
	echo "Could not read stationId from $SNAPSHOT_FILE." >&2
	exit 1
fi

REGRESSION_STATION_ID="REGTST1"
if [ "$ORIGINAL_STATION_ID" = "$REGRESSION_STATION_ID" ]; then
	REGRESSION_STATION_ID="REGTST2"
fi

REGRESSION_ID_SPEED=18
if [ "$ORIGINAL_ID_SPEED" = "18" ]; then
	REGRESSION_ID_SPEED=17
fi

REGRESSION_PATTERN_SPEED=9
if [ "$ORIGINAL_PATTERN_SPEED" = "9" ]; then
	REGRESSION_PATTERN_SPEED=8
fi

run_step "normal-set-station-id" run_debug_command set-station-id "$REGRESSION_STATION_ID"
run_step "normal-restore-station-id" run_debug_command set-station-id "$ORIGINAL_STATION_ID"
run_step "normal-set-id-speed" run_debug_command set-id-speed "$REGRESSION_ID_SPEED"
run_step "normal-restore-id-speed" run_debug_command set-id-speed "$ORIGINAL_ID_SPEED"
run_step "normal-set-pattern-speed" run_debug_command set-pattern-speed "$REGRESSION_PATTERN_SPEED"
run_step "normal-restore-pattern-speed" run_debug_command set-pattern-speed "$ORIGINAL_PATTERN_SPEED"
run_step "normal-set-event-type" run_debug_command set-event-type CLASSIC
run_step "normal-set-fox-role" run_debug_command set-fox-role BEACON

CURRENT_TIME="$(current_compact_time)"
run_step "normal-set-current-time" run_debug_command set-current-time "$CURRENT_TIME"
NON_BOUNDARY_START="$(future_compact_minutes 30)"
run_step "normal-set-non-boundary-start" run_debug_command set-start-time "$NON_BOUNDARY_START"
run_step "normal-set-days-to-run" run_debug_command set-days-to-run 1
run_step "normal-load-after-schedule" load_with_retry "$OUTPUT_DIR/normal-load-after-schedule.txt"

run_step "normal-raw-go-1" run_debug_command raw-command "GO 1"
run_step "normal-raw-go-0" run_debug_command raw-command "GO 0"
run_step "normal-raw-clk-t" run_debug_command raw-command "CLK T"

run_step "normal-log-export" run_debug_command get-log
run_step "normal-clone" run_debug_command clone-wait
run_step "normal-final-load" load_with_retry "$OUTPUT_DIR/normal-final-load.txt"

if rg -q "statusIsError=true|failed verification|did not provide timely replies|No reply received|Exception|FATAL" "$OUTPUT_DIR"; then
	record_fail "scan-output" "failure text found under $OUTPUT_DIR"
	echo "Regression output contains failure text. Summary: $SUMMARY_FILE" >&2
	exit 1
fi

record_pass "scan-output" "no failure text found"
echo "Android regression complete."
echo "Summary: $SUMMARY_FILE"
