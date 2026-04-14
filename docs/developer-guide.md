# Developer Guide

SerialSlinger is a Kotlin Multiplatform project for configuring SignalSlinger devices through a desktop UI instead of raw serial commands.

## Local Run

From the repository root:

- `./gradlew shared:desktopTest`
- `./gradlew desktopSmokeRun --args="list"`
- `./gradlew desktopAppRun`

## Current Code Scaffold

The repository currently includes:

- a shared Kotlin Multiplatform core for settings, protocol, transport, and session logic
- editable field state and validation support
- protocol parsing and command generation
- verified write-plan generation
- desktop serial transport and SignalSlinger probing
- a Swing-based desktop UI
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
