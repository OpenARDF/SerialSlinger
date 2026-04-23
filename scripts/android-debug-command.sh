#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
SCRIPT_PATH="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/$(basename "${BASH_SOURCE[0]}")"
DEFAULT_ANDROID_SDK_ROOT="$HOME/Library/Android/sdk"
TARGET_SERIAL="${ANDROID_SERIAL:-}"
DEVICE_NAME=""

usage() {
  cat <<'EOF'
Usage:
  ./scripts/android-debug-command.sh [--serial <adb-serial>] [--device-name <usb-device-name>] get-state
  ./scripts/android-debug-command.sh [--serial <adb-serial>] [--device-name <usb-device-name>] get-snapshot
  ./scripts/android-debug-command.sh [--serial <adb-serial>] [--device-name <usb-device-name>] get-trace
  ./scripts/android-debug-command.sh [--serial <adb-serial>] [--device-name <usb-device-name>] get-log
  ./scripts/android-debug-command.sh [--serial <adb-serial>] [--device-name <usb-device-name>] clear-log
  ./scripts/android-debug-command.sh [--serial <adb-serial>] [--device-name <usb-device-name>] load
  ./scripts/android-debug-command.sh [--serial <adb-serial>] [--device-name <usb-device-name>] set-event-type <value>
  ./scripts/android-debug-command.sh [--serial <adb-serial>] [--device-name <usb-device-name>] set-fox-role <value>
  ./scripts/android-debug-command.sh [--serial <adb-serial>] [--device-name <usb-device-name>] set-station-id <value>
  ./scripts/android-debug-command.sh [--serial <adb-serial>] [--device-name <usb-device-name>] set-id-speed <wpm>
  ./scripts/android-debug-command.sh [--serial <adb-serial>] [--device-name <usb-device-name>] set-pattern-speed <wpm>
  ./scripts/android-debug-command.sh [--serial <adb-serial>] [--device-name <usb-device-name>] set-current-frequency <value>
  ./scripts/android-debug-command.sh [--serial <adb-serial>] [--device-name <usb-device-name>] set-frequency-bank <1|2|3|B> <value>
  ./scripts/android-debug-command.sh [--serial <adb-serial>] [--device-name <usb-device-name>] set-pattern-text <value>
  ./scripts/android-debug-command.sh [--serial <adb-serial>] [--device-name <usb-device-name>] set-current-time <value>
  ./scripts/android-debug-command.sh [--serial <adb-serial>] [--device-name <usb-device-name>] set-start-time <value>
  ./scripts/android-debug-command.sh [--serial <adb-serial>] [--device-name <usb-device-name>] set-finish-time <value>
  ./scripts/android-debug-command.sh [--serial <adb-serial>] [--device-name <usb-device-name>] set-days-to-run <value>
  ./scripts/android-debug-command.sh [--serial <adb-serial>] [--device-name <usb-device-name>] set-time-sequence <device-time> <start-time> <finish-time> <days>
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
    -h|--help)
      usage
      exit 0
      ;;
    *)
      break
      ;;
  esac
done

COMMAND="${1:-}"
ARG1="${2:-}"
ARG2="${3:-}"
ARG3="${4:-}"
ARG4="${5:-}"

if [ -z "$COMMAND" ]; then
  usage >&2
  exit 1
fi

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

run_nested_command() {
  "$SCRIPT_PATH" "${COMMON_ARGS[@]}" "$@"
}

if [ "$COMMAND" = "set-time-sequence" ] || [ "$COMMAND" = "set-schedule-sequence" ]; then
  if [ -z "$ARG1" ] || [ -z "$ARG2" ] || [ -z "$ARG3" ] || [ -z "$ARG4" ]; then
    echo "$COMMAND requires device time, start time, finish time, and days to run." >&2
    exit 1
  fi

  run_nested_command set-current-time "$ARG1"
  run_nested_command set-start-time "$ARG2"
  run_nested_command set-finish-time "$ARG3"
  run_nested_command set-days-to-run "$ARG4"
  run_nested_command load
  exit 0
fi

ACTION=""
EXTRA_ARGS=()
case "$COMMAND" in
  get-state)
    ACTION="com.SerialSlinger.openardf.DEBUG_GET_STATE"
    ;;
  get-snapshot)
    ACTION="com.SerialSlinger.openardf.DEBUG_GET_SNAPSHOT"
    ;;
  get-trace)
    ACTION="com.SerialSlinger.openardf.DEBUG_GET_TRACE"
    ;;
  get-log)
    ACTION="com.SerialSlinger.openardf.DEBUG_GET_LOG"
    ;;
  clear-log)
    ACTION="com.SerialSlinger.openardf.DEBUG_CLEAR_LOG"
    ;;
  load)
    ACTION="com.SerialSlinger.openardf.DEBUG_LOAD"
    ;;
  set-event-type)
    if [ -z "$ARG1" ]; then
      echo "set-event-type requires a value." >&2
      exit 1
    fi
    ACTION="com.SerialSlinger.openardf.DEBUG_SET_EVENT_TYPE"
    EXTRA_ARGS+=(--es event_type "$ARG1")
    ;;
  set-fox-role)
    if [ -z "$ARG1" ]; then
      echo "set-fox-role requires a value." >&2
      exit 1
    fi
    ACTION="com.SerialSlinger.openardf.DEBUG_SET_FOX_ROLE"
    EXTRA_ARGS+=(--es fox_role "$ARG1")
    ;;
  set-station-id)
    if [ -z "$ARG1" ]; then
      echo "set-station-id requires a value." >&2
      exit 1
    fi
    ACTION="com.SerialSlinger.openardf.DEBUG_SET_STATION_ID"
    EXTRA_ARGS+=(--es station_id "$ARG1")
    ;;
  set-id-speed)
    if [ -z "$ARG1" ]; then
      echo "set-id-speed requires a value." >&2
      exit 1
    fi
    ACTION="com.SerialSlinger.openardf.DEBUG_SET_ID_SPEED"
    EXTRA_ARGS+=(--es id_speed_wpm "$ARG1")
    ;;
  set-pattern-speed)
    if [ -z "$ARG1" ]; then
      echo "set-pattern-speed requires a value." >&2
      exit 1
    fi
    ACTION="com.SerialSlinger.openardf.DEBUG_SET_PATTERN_SPEED"
    EXTRA_ARGS+=(--es pattern_speed_wpm "$ARG1")
    ;;
  set-current-frequency)
    if [ -z "$ARG1" ]; then
      echo "set-current-frequency requires a value." >&2
      exit 1
    fi
    ACTION="com.SerialSlinger.openardf.DEBUG_SET_CURRENT_FREQUENCY"
    EXTRA_ARGS+=(--es current_frequency "$ARG1")
    ;;
  set-frequency-bank)
    if [ -z "$ARG1" ] || [ -z "$ARG2" ]; then
      echo "set-frequency-bank requires a bank id and a value." >&2
      exit 1
    fi
    ACTION="com.SerialSlinger.openardf.DEBUG_SET_FREQUENCY_BANK"
    EXTRA_ARGS+=(--es bank_id "$ARG1" --es bank_frequency "$ARG2")
    ;;
  set-pattern-text)
    if [ -z "$ARG1" ]; then
      echo "set-pattern-text requires a value." >&2
      exit 1
    fi
    ACTION="com.SerialSlinger.openardf.DEBUG_SET_PATTERN_TEXT"
    EXTRA_ARGS+=(--es pattern_text "$ARG1")
    ;;
  set-current-time)
    if [ -z "$ARG1" ]; then
      echo "set-current-time requires a value." >&2
      exit 1
    fi
    ACTION="com.SerialSlinger.openardf.DEBUG_SET_CURRENT_TIME"
    EXTRA_ARGS+=(--es current_time "$ARG1")
    ;;
  set-start-time)
    if [ -z "$ARG1" ]; then
      echo "set-start-time requires a value." >&2
      exit 1
    fi
    ACTION="com.SerialSlinger.openardf.DEBUG_SET_START_TIME"
    EXTRA_ARGS+=(--es start_time "$ARG1")
    ;;
  set-finish-time)
    if [ -z "$ARG1" ]; then
      echo "set-finish-time requires a value." >&2
      exit 1
    fi
    ACTION="com.SerialSlinger.openardf.DEBUG_SET_FINISH_TIME"
    EXTRA_ARGS+=(--es finish_time "$ARG1")
    ;;
  set-days-to-run)
    if [ -z "$ARG1" ]; then
      echo "set-days-to-run requires a value." >&2
      exit 1
    fi
    ACTION="com.SerialSlinger.openardf.DEBUG_SET_DAYS_TO_RUN"
    EXTRA_ARGS+=(--es days_to_run "$ARG1")
    ;;
  *)
    echo "Unknown command: $COMMAND" >&2
    usage >&2
    exit 1
    ;;
esac

if [ -n "$DEVICE_NAME" ]; then
  EXTRA_ARGS+=(--es device_name "$DEVICE_NAME")
fi

REMOTE_ARGS=(
  am broadcast
  -a "$ACTION"
  -n com.SerialSlinger.openardf/.AndroidDebugCommandReceiver
  "${EXTRA_ARGS[@]}"
)

printf -v REMOTE_COMMAND '%q ' "${REMOTE_ARGS[@]}"
REMOTE_COMMAND="${REMOTE_COMMAND% }"

adb "${ADB_ARGS[@]}" shell "$REMOTE_COMMAND"
