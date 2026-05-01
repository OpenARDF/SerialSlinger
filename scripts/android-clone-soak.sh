#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
TARGET_SERIAL="${ANDROID_SERIAL:-}"
DEVICE_NAME=""
COUNT=10
DELAY_SECONDS=2
OUTPUT_DIR="$ROOT_DIR/build/android-clone-soak/$(date +%Y%m%d-%H%M%S)"
LOGCAT_PID=""
CLONE_TIMEOUT_SECONDS=120
POLL_SECONDS=2

usage() {
	cat <<'EOF'
Usage:
  ./scripts/android-clone-soak.sh [--serial <adb-serial>] [--device-name <usb-device-name>] [--count <n>] [--delay <seconds>] [--output-dir <path>]

Runs repeated debug-only Clone attempts against one Android app/device target.
Each iteration clears the app log, loads the attached SignalSlinger, runs Clone,
saves the broadcast output and app log, and records failures or warning text.
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

wait_for_clone_completion() {
	local log_file="$1"
	local deadline=$((SECONDS + CLONE_TIMEOUT_SECONDS))

	while [ "$SECONDS" -lt "$deadline" ]; do
		run_debug_command get-log >"$log_file" 2>&1 || return 1

		if rg -q "== clone ==" "$log_file"; then
			if rg -q "source=adb success=true|Clone succeeded|RX MAS ACK" "$log_file"; then
				return 0
			fi

			if rg -q "source=adb success=false|Clone failed|did not provide timely replies|Firmware clone failed|success=false" "$log_file"; then
				return 1
			fi
		fi

		sleep "$POLL_SECONDS"
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
printf 'iteration\tstatus\tlog_file\tnotes\n' >"$SUMMARY_FILE"

ADB_ARGS=()
if [ -n "$TARGET_SERIAL" ]; then
	ADB_ARGS+=("-s" "$TARGET_SERIAL")
fi
adb "${ADB_ARGS[@]}" logcat -c || true
adb "${ADB_ARGS[@]}" logcat -v threadtime >"$OUTPUT_DIR/logcat.txt" 2>&1 &
LOGCAT_PID="$!"

for iteration in $(seq 1 "$COUNT"); do
	printf 'Clone soak %d/%d\n' "$iteration" "$COUNT"
	ITERATION_PREFIX="$OUTPUT_DIR/$(printf '%03d' "$iteration")"
	STATUS="pass"
	NOTES=()

	run_debug_command clear-log >"${ITERATION_PREFIX}-clear.txt" 2>&1 || true

	if ! run_debug_command load >"${ITERATION_PREFIX}-load.txt" 2>&1; then
		STATUS="fail"
		NOTES+=("load failed")
	elif ! run_debug_command clone >"${ITERATION_PREFIX}-clone.txt" 2>&1; then
		STATUS="fail"
		NOTES+=("clone broadcast failed")
	elif ! wait_for_clone_completion "${ITERATION_PREFIX}-log.txt"; then
		STATUS="fail"
		NOTES+=("clone completion not confirmed")
	fi

	run_debug_command get-log >"${ITERATION_PREFIX}-log.txt" 2>&1 || {
		STATUS="fail"
		NOTES+=("log capture failed")
	}

	if rg -q "Clone failed|did not provide timely replies|Firmware clone failed|success=false" "${ITERATION_PREFIX}-log.txt"; then
		STATUS="fail"
		NOTES+=("failure or warning text found")
	fi

	if rg -q "Write commands sent: (1[89]|[2-9][0-9]|[1-9][0-9]{2,})" "${ITERATION_PREFIX}-log.txt"; then
		NOTES+=("clone used retry")
	fi

	printf '%d\t%s\t%s\t%s\n' \
		"$iteration" \
		"$STATUS" \
		"${ITERATION_PREFIX}-log.txt" \
		"$(
			IFS=', '
			echo "${NOTES[*]:-ok}"
		)" \
		>>"$SUMMARY_FILE"

	if [ "$iteration" -lt "$COUNT" ]; then
		sleep "$DELAY_SECONDS"
	fi
done

echo "Clone soak complete."
echo "Summary: $SUMMARY_FILE"
