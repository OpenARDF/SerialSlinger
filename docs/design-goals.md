# Design Goals

## Primary Goal

Make SignalSlinger configuration approachable through a graphical interface so users can inspect and change settings without learning the serial command set.

## User Workflow

1. The user connects a SignalSlinger to the computer or device.
2. SerialSlinger detects or opens the selected serial connection.
3. SerialSlinger reads the current settings from the attached device.
4. SerialSlinger displays those settings in a clear editable table.
5. The user updates one or more values.
6. The user clicks `Submit`.
7. SerialSlinger validates the edited values and writes the changes to the device.
8. SerialSlinger reports success, failure, or partial failure clearly.

## High-Level Product Requirements

- The UI must reflect the current device settings before the user edits them.
- The UI must hide raw serial command details from the user.
- The software must validate edits before sending changes.
- The software should write only the settings that actually changed.
- The software should make it easy to understand which values came from the device and which values were edited locally.
- The software should provide clear error feedback when communication fails or the device rejects a value.

## Non-Goals For The First Cut

- exposing a terminal-like command console as the primary experience
- requiring the user to type SignalSlinger commands manually
- importing legacy UI code from other repositories
- iOS direct USB/serial support

## Important Open Questions

- What exact settings belong in the first editable table?
- Should the first release support one device profile or multiple firmware/hardware variants?
- What transport paths matter beyond desktop serial, especially for mobile platforms?

## Platform Scope

The first usable release should target:

- macOS
- Windows
- Linux
- Android

iOS is deferred for now because the project depends on practical USB/serial device access, and that is not a good fit for the current iOS accessory model.
