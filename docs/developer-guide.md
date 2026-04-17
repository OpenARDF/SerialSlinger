# Developer Guide

SerialSlinger is a Kotlin Multiplatform project for configuring SignalSlinger devices through a desktop UI instead of raw serial commands.

## Local Run

From the repository root:

- `./gradlew shared:desktopTest`
- `./gradlew desktopSmokeRun --args="list"`
- `./gradlew desktopAppRun`
- `./gradlew :androidApp:assembleDebug`
- `adb devices`
- `./gradlew :androidApp:installDebug`
- `./scripts/android-install-debug.sh`
- `./scripts/android-install-debug.sh <adb-serial>`
- `./scripts/android-debug-command.sh --serial <adb-serial> get-state`
- `./scripts/android-debug-command.sh --serial <adb-serial> get-snapshot`
- `./scripts/android-debug-command.sh --serial <adb-serial> get-trace`
- `./scripts/android-debug-command.sh --serial <adb-serial> get-log`
- `./scripts/android-debug-command.sh --serial <adb-serial> clear-log`
- `./scripts/android-debug-command.sh --serial <adb-serial> load`
- `./scripts/android-debug-command.sh --serial <adb-serial> set-event-type CLASSIC`
- `./scripts/android-debug-command.sh --serial <adb-serial> set-fox-role "FREQ 2"`
- `./scripts/android-debug-command.sh --serial <adb-serial> set-station-id TEST123`
- `./scripts/android-debug-command.sh --serial <adb-serial> set-id-speed 12`
- `./scripts/android-debug-command.sh --serial <adb-serial> set-pattern-speed 14`
- `./scripts/android-debug-command.sh --serial <adb-serial> set-current-frequency "3.560 MHz"`
- `./scripts/android-debug-command.sh --serial <adb-serial> set-frequency-bank 1 "3.520 MHz"`
- `./scripts/android-debug-command.sh --serial <adb-serial> set-pattern-text MO5`
- `./scripts/android-debug-command.sh --serial <adb-serial> set-current-time "2026-04-16 15:30:00"`
- `./scripts/android-debug-command.sh --serial <adb-serial> set-start-time "2026-04-16 14:00:00"`
- `./scripts/android-debug-command.sh --serial <adb-serial> set-finish-time "2026-04-16 18:00:00"`
- `./scripts/android-debug-command.sh --serial <adb-serial> set-days-to-run 3`
- `./scripts/android-debug-command.sh --serial <adb-serial> set-time-sequence "2026-04-16 15:30:00" "2026-04-16 14:00:00" "2026-04-16 18:00:00" 3`

## Android Development Setup

The local Android CLI tooling on this machine is expected at:

- `ANDROID_SDK_ROOT=$HOME/Library/Android/sdk`
- `ANDROID_HOME=$ANDROID_SDK_ROOT`

Open a fresh terminal session after shell-profile changes so `sdkmanager` and `adb` are on `PATH`.

The current Android scaffold is intentionally narrow:

- `:androidApp` is the Android launcher app module
- `:shared` now includes an Android target for shared business logic
- Android USB discovery scaffolding lives in `shared/src/androidMain`
- FTDI-capable Android USB serial transport now works through `usb-serial-for-android`
- The Android app can load and display a read-only SignalSlinger snapshot from real hardware
- The debug build exposes adb-broadcast automation hooks for loading state and safely exercising a few editable fields
- The Android app now keeps a lightweight per-day session log in app storage, and the debug helper can fetch the current log wirelessly with `get-log`
- The debug helper script can apply the full time-setting sequence in device order: `Device Time`, `Start Time`, `Finish Time`, then `Days To Run`, and then does a fresh `load` so firmware-side coupled values are reread
- The Android UI currently exposes an `Apply Time Sequence In Order` action as a temporary development affordance so multi-field time edits can follow the same device-first workflow

## Notes To Keep

- `EVT` and `FOX` rereads are intentionally conservative right now. Future cleanup: avoid rereading `FRE 1`/`FRE 2`/`FRE 3`/`FRE B` after event or role changes unless the user explicitly edits a bank or requests a full refresh.
- Keep desktop and Android feature sets aligned as Android grows. The desktop menu-row areas `View`, `Settings`, and `Tools` should each have Android equivalents over time, even if Android presents them through different navigation patterns and those patterns evolve with user feedback.
- Revisit Android logging policy before wider release use. Desktop-style always-on logging is acceptable there, but Android should likely keep diagnostics logging off by default in release builds, allow temporary user-enabled logging windows, and prune or auto-delete old logs so tablet storage stays bounded.
- Revisit Android session-log polish. `get-log` should ideally start on a clean line or section boundary instead of sometimes beginning mid-line when the tail is truncated, and auto-probe entries should eventually distinguish whether they were triggered by USB attach, app resume, or background rediscovery.
- Revisit time-setting UX/helpfulness. SignalSlinger applies time settings in sequence: `Device Time` first, then `Start Time`, then `Finish Time`, then `Days To Run` last. After device time is set, changing `Start Time` or `Finish Time` resets `Days To Run` to `1` because the user has stepped back in that sequence. The app should likely surface a clear notification when that happens, but the exact guidance/UX needs more vetting before implementation.
- Revisit final Android time UX. The eventual Android UI should show device time as a live once-per-second display rather than a static snapshot, and it should stop exposing a dedicated `Apply Time Sequence In Order` button once the app can automatically enforce the correct apply order behind normal field submissions.
- For the currently attached bench-safe SignalSlinger, command traffic is considered safe while RF output is effectively disabled. Use the latest battery readback as a heuristic: if `External Battery` is greater than `Internal Battery`, RF output is enabled; otherwise output is disabled and normal configuration commands are safe to exercise. Reconfirm this assumption if the hardware setup changes.

## Current Code Scaffold

The repository currently includes:

- a shared Kotlin Multiplatform core for settings, protocol, transport, and session logic
- editable field state and validation support
- protocol parsing and command generation
- verified write-plan generation
- desktop serial transport and SignalSlinger probing
- Android USB-device discovery scaffolding
- a Swing-based desktop UI
- a minimal Android UI shell
- unit tests around the shared model and desktop integration helpers

## Architecture Direction

The software is organized around a structured settings model:

- the UI edits settings objects, not raw command strings
- a device service reads and writes those settings
- the protocol layer translates between settings objects and SignalSlinger commands
- the shared core is designed for test-first development

## Platform Targets

Current target platforms are:

- macOS
- Windows
- Linux
- Android

iOS is currently out of scope for direct USB or serial support. If SignalSlinger ever exposes a transport that fits Apple's supported accessory model, that decision can be revisited later.

## Design Docs

- [design-goals.md](/Users/charlesscharlau/Documents/GitHub/SerialSlinger/docs/design-goals.md)
- [high-level-design.md](/Users/charlesscharlau/Documents/GitHub/SerialSlinger/docs/high-level-design.md)
- [stack-decision.md](/Users/charlesscharlau/Documents/GitHub/SerialSlinger/docs/stack-decision.md)
- [first-settings-table.md](/Users/charlesscharlau/Documents/GitHub/SerialSlinger/docs/first-settings-table.md)
- [domain-model.md](/Users/charlesscharlau/Documents/GitHub/SerialSlinger/docs/domain-model.md)
- [initial-backlog.md](/Users/charlesscharlau/Documents/GitHub/SerialSlinger/docs/initial-backlog.md)
