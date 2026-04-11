# SerialSlinger

SerialSlinger is a standalone software project for configuring a SignalSlinger device through a graphical user interface instead of manual serial commands.

## Product Goal

When a SignalSlinger is connected to a serial port, SerialSlinger should:

1. connect to the device
2. read the device's current settings
3. present those settings to the user in a graphical table
4. let the user edit the settings in that table
5. write the requested changes back to the device when the user clicks Submit

The user should not need to memorize or understand raw SignalSlinger serial commands.

## Design Direction

This repository starts clean. It does not import legacy application code from the existing SignalSlinger repository.

The software is being organized around a structured settings model:

- the UI edits settings objects, not raw command strings
- a device service reads and writes those settings
- the protocol layer translates between settings objects and SignalSlinger commands
- the shared core is designed for test-first development

## Current Scope

The project now includes:

- a shared Kotlin Multiplatform core for settings, protocol, transport, and session logic
- a desktop smoke-test CLI for talking to real hardware
- a first desktop UI for loading, editing, and submitting settings
- desktop-side serial-port discovery and SignalSlinger probing to reduce manual port selection

## Platform Targets

Current target platforms are:

- macOS
- Windows
- Linux
- Android

iOS is currently treated as out of scope for direct USB/serial support. If SignalSlinger ever exposes a transport that fits Apple's supported accessory model, that decision can be revisited later.

## Key Docs

- docs/design-goals.md
- docs/high-level-design.md
- docs/stack-decision.md
- docs/first-settings-table.md
- docs/domain-model.md
- docs/initial-backlog.md

## Current Code Scaffold

The repository includes a Kotlin Multiplatform shared module for:

- settings and domain models
- editable field state
- protocol parsing and command generation
- verified write-plan generation
- desktop serial transport and device probing
- a first Swing-based desktop UI
- unit tests around the shared model and desktop integration helpers

## Local Run

From the repository root:

- `./gradlew shared:desktopTest`
- `./gradlew desktopSmokeRun --args="list"`
- `./gradlew desktopAppRun`

## macOS Packaging

From the repository root on macOS:

- `./gradlew desktopAppImage`
- `./gradlew desktopDmg`

The generated packaging artifacts are written under:

- `shared/build/packaging/output`

`desktopDmg` currently produces an unsigned `.dmg` for local drag-and-drop testing. Apple signing and notarization can be added later for normal end-user distribution.

Note: the packaged macOS installer version uses a `1.x.y` format because `jpackage` does not accept an app-package version beginning with `0`. The in-app SerialSlinger version shown in the log and window title remains on the project’s `0.1.x` development track.

## Windows Packaging

From the repository root on Windows:

- `gradlew.bat desktopExe`
- `gradlew.bat desktopMsi`

The generated packaging artifacts are written under:

- `shared\\build\\packaging\\output`

Notes:

- `desktopExe` is the simplest Windows installer target to start with.
- `desktopMsi` may require Windows packaging tooling such as WiX depending on the local JDK/jpackage setup.
- Windows installers use the same `1.x.y` package-version track as macOS packaging, while the in-app SerialSlinger version remains on the `0.1.x` development track.
