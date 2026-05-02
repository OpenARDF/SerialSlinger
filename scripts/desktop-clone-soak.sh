#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
PORT=""
COUNT=25
DELAY_SECONDS=2
OUTPUT_DIR="$ROOT_DIR/build/desktop-clone-soak/$(date +%Y%m%d-%H%M%S)"

usage() {
	cat <<'EOF'
Usage:
  ./scripts/desktop-clone-soak.sh --port <serial-port> [--count <n>] [--delay <seconds>] [--output-dir <path>]

Runs repeated desktop MAS Clone attempts against one USB serial SignalSlinger.
Each iteration loads the target, runs the desktop smoke CLI clone command,
saves output, and records failures or full-session retries.
EOF
}

while [ "$#" -gt 0 ]; do
	case "$1" in
	--port)
		PORT="${2:-}"
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

if [ -z "$PORT" ]; then
	echo "--port is required." >&2
	usage >&2
	exit 1
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

count_matches() {
	local pattern="$1"
	local file="$2"
	local count
	count="$(rg -c "$pattern" "$file" || true)"
	printf '%s\n' "${count:-0}"
}

SUMMARY_FILE="$OUTPUT_DIR/summary.tsv"
printf 'iteration\tstatus\tcategory\tlog_file\tnotes\n' >"$SUMMARY_FILE"

for iteration in $(seq 1 "$COUNT"); do
	printf 'Desktop clone soak %d/%d\n' "$iteration" "$COUNT"
	ITERATION_LOG="$OUTPUT_DIR/$(printf '%03d' "$iteration")-clone.txt"
	STATUS="pass"
	NOTES=()

	if ! ./gradlew -q desktopSmokeRun --args="clone $PORT" >"$ITERATION_LOG" 2>&1; then
		STATUS="fail"
		NOTES+=("clone command failed")
	fi

	if rg -q "succeeded=false|Firmware clone failed|SignalSlinger did not|Exception|BUILD FAILED" "$ITERATION_LOG"; then
		STATUS="fail"
		NOTES+=("failure text found")
	fi

	RST_COUNT="$(count_matches "TX RST" "$ITERATION_LOG")"
	CATEGORY="clean single-session clone"
	if [ "$RST_COUNT" -gt 1 ]; then
		CATEGORY="full-session retry"
		NOTES+=("full-session retry")
	fi

	printf '%d\t%s\t%s\t%s\t%s\n' \
		"$iteration" \
		"$STATUS" \
		"$CATEGORY" \
		"$ITERATION_LOG" \
		"$(
			IFS=', '
			echo "${NOTES[*]:-ok}"
		)" \
		>>"$SUMMARY_FILE"

	if [ "$iteration" -lt "$COUNT" ]; then
		sleep "$DELAY_SECONDS"
	fi
done

echo "Desktop clone soak complete."
echo "Summary: $SUMMARY_FILE"
