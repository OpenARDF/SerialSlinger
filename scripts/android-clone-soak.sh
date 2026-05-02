#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
TARGET_SERIAL="${ANDROID_SERIAL:-}"
DEVICE_NAME=""
COUNT=10
DELAY_SECONDS=2
DELAY_SPECIFIED=0
FAST_MODE=0
OUTPUT_DIR="$ROOT_DIR/build/android-clone-soak/$(date +%Y%m%d-%H%M%S)"
LOGCAT_PID=""
LOAD_ATTEMPTS=5
LOAD_RETRY_SECONDS=3
LOAD_EACH_ITERATION=1
LOG_EACH_ITERATION=1
CLEAR_LOG_EACH_ITERATION=1

usage() {
	cat <<'EOF'
Usage:
  ./scripts/android-clone-soak.sh [--serial <adb-serial>] [--device-name <usb-device-name>] [--count <n>] [--delay <seconds>] [--fast] [--output-dir <path>]

Runs repeated debug-only Clone attempts against one Android app/device target.
Each iteration clears the app log, loads the attached SignalSlinger, runs Clone,
saves the completed broadcast output and app log, and records failures or warning text.

Use --fast to load once, skip per-iteration app-log export unless a failure is
detected, and remove the inter-iteration delay unless --delay is specified.
Continuous logcat is still saved.
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
	--count)
		COUNT="${2:-}"
		shift 2
		;;
	--delay)
		DELAY_SECONDS="${2:-}"
		DELAY_SPECIFIED=1
		shift 2
		;;
	--fast)
		FAST_MODE=1
		LOAD_EACH_ITERATION=0
		LOG_EACH_ITERATION=0
		CLEAR_LOG_EACH_ITERATION=0
		shift
		;;
	--load-each)
		LOAD_EACH_ITERATION=1
		shift
		;;
	--no-load-each)
		LOAD_EACH_ITERATION=0
		shift
		;;
	--log-each)
		LOG_EACH_ITERATION=1
		shift
		;;
	--no-log-each)
		LOG_EACH_ITERATION=0
		shift
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

if [ "$FAST_MODE" -eq 1 ] && [ "$DELAY_SPECIFIED" -eq 0 ]; then
	DELAY_SECONDS=0
fi

case "$COUNT" in
'' | *[!0-9]*)
	echo "--count must be a positive integer." >&2
	exit 1
	;;
esac

if [ "$COUNT" -lt 1 ]; then
	echo "--count must be at least 1." >&2
	exit 1
fi

cd "$ROOT_DIR"
mkdir -p "$OUTPUT_DIR"

COMMON_ARGS=()
if [ -n "$TARGET_SERIAL" ]; then
	COMMON_ARGS+=(--serial "$TARGET_SERIAL")
fi
if [ -n "$DEVICE_NAME" ]; then
	COMMON_ARGS+=(--device-name "$DEVICE_NAME")
fi

run_debug_command() {
	./scripts/android-debug-command.sh "${COMMON_ARGS[@]}" "$@"
}

count_matches() {
	local pattern="$1"
	local file="$2"
	local count
	count="$(rg -c "$pattern" "$file" || true)"
	printf '%s\n' "${count:-0}"
}

scan_for_clone_failure_text() {
	local file="$1"

	rg -q "Clone failed|did not provide timely replies|Firmware clone failed|success=false|statusIsError=true" "$file"
}

run_load_with_retry() {
	local output_file="$1"
	local attempt

	for attempt in $(seq 1 "$LOAD_ATTEMPTS"); do
		if run_debug_command load >"$output_file" 2>&1; then
			return 0
		fi

		if ! rg -q "Probe already in progress" "$output_file"; then
			return 1
		fi

		if [ "$attempt" -lt "$LOAD_ATTEMPTS" ]; then
			sleep "$LOAD_RETRY_SECONDS"
		fi
	done

	return 1
}

cleanup() {
	if [ -n "$LOGCAT_PID" ]; then
		kill "$LOGCAT_PID" 2>/dev/null || true
		wait "$LOGCAT_PID" 2>/dev/null || true
	fi
}
trap cleanup EXIT

SUMMARY_FILE="$OUTPUT_DIR/summary.tsv"
printf 'iteration\tstatus\tcategory\tlog_file\tnotes\n' >"$SUMMARY_FILE"

ADB_ARGS=()
if [ -n "$TARGET_SERIAL" ]; then
	ADB_ARGS+=("-s" "$TARGET_SERIAL")
fi
adb "${ADB_ARGS[@]}" logcat -c || true
adb "${ADB_ARGS[@]}" logcat -v threadtime >"$OUTPUT_DIR/logcat.txt" 2>&1 &
LOGCAT_PID="$!"

if [ "$LOAD_EACH_ITERATION" -eq 0 ]; then
	printf 'Initial load\n'
	if ! run_load_with_retry "$OUTPUT_DIR/initial-load.txt"; then
		printf '0\tfail\tinitial load\t%s\tload failed\n' "$OUTPUT_DIR/initial-load.txt" >>"$SUMMARY_FILE"
		echo "Initial load failed."
		echo "Summary: $SUMMARY_FILE"
		exit 1
	fi
fi

for iteration in $(seq 1 "$COUNT"); do
	printf 'Clone soak %d/%d\n' "$iteration" "$COUNT"
	ITERATION_PREFIX="$OUTPUT_DIR/$(printf '%03d' "$iteration")"
	STATUS="pass"
	NOTES=()
	LOG_FILE="${ITERATION_PREFIX}-log.txt"
	LOG_CAPTURED=0

	if [ "$CLEAR_LOG_EACH_ITERATION" -eq 1 ]; then
		run_debug_command clear-log >"${ITERATION_PREFIX}-clear.txt" 2>&1 || true
	fi

	if [ "$LOAD_EACH_ITERATION" -eq 1 ] && ! run_load_with_retry "${ITERATION_PREFIX}-load.txt"; then
		STATUS="fail"
		NOTES+=("load failed")
	elif ! run_debug_command clone-wait >"${ITERATION_PREFIX}-clone.txt" 2>&1; then
		STATUS="fail"
		NOTES+=("clone failed")
	fi

	if scan_for_clone_failure_text "${ITERATION_PREFIX}-clone.txt"; then
		STATUS="fail"
		NOTES+=("failure or warning text found")
	fi

	if [ "$LOG_EACH_ITERATION" -eq 1 ] || [ "$STATUS" = "fail" ]; then
		if run_debug_command get-log >"$LOG_FILE" 2>&1; then
			LOG_CAPTURED=1
		else
			STATUS="fail"
			NOTES+=("log capture failed")
		fi
	fi

	if [ "$LOG_CAPTURED" -eq 1 ] && scan_for_clone_failure_text "$LOG_FILE"; then
		STATUS="fail"
		NOTES+=("failure or warning text found")
	fi

	RST_COUNT=0
	if [ "$LOG_CAPTURED" -eq 1 ]; then
		RST_COUNT="$(count_matches "TX RST" "$LOG_FILE")"
	fi
	CATEGORY="clean single-session clone"
	if [ "$RST_COUNT" -gt 1 ]; then
		CATEGORY="full-session retry"
		NOTES+=("full-session retry")
	fi

	printf '%d\t%s\t%s\t%s\t%s\n' \
		"$iteration" \
		"$STATUS" \
		"$CATEGORY" \
		"$([ "$LOG_CAPTURED" -eq 1 ] && printf '%s' "$LOG_FILE" || printf '%s' "${ITERATION_PREFIX}-clone.txt")" \
		"$(
			IFS=', '
			echo "${NOTES[*]:-ok}"
		)" \
		>>"$SUMMARY_FILE"

	if [ "$iteration" -lt "$COUNT" ]; then
		sleep "$DELAY_SECONDS"
	fi
done

if [ "$LOG_EACH_ITERATION" -eq 0 ]; then
	run_debug_command get-log >"$OUTPUT_DIR/final-log.txt" 2>&1 || true
fi

echo "Clone soak complete."
echo "Summary: $SUMMARY_FILE"
