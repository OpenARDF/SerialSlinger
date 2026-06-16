# Stack Decision

## Decision

Use **Kotlin Multiplatform** for the shared core of SerialSlinger.

For the first implementation slice, start with a **shared library module** and keep the UI native or undecided for now.

## Why

SerialSlinger needs to stay viable across:

- macOS
- Windows
- Linux
- Android

Kotlin Multiplatform is a strong fit because it is designed to share code across Android and desktop targets while still allowing platform-specific implementations where needed.

For SerialSlinger, that means we can keep the following logic in one place:

- settings model
- validation rules
- dirty-state tracking
- settings diff logic
- write-plan generation
- protocol parsing and encoding
- higher-level device workflow logic

Then we can keep these parts platform-specific:

- serial port access
- USB or accessory integration
- platform permissions
- native UI details

## Why Not Plain Java

Java would be reasonable for desktop and maybe Android, but Kotlin Multiplatform still gives us a cleaner shared-core path as Android support grows.

## Why iOS Is Deferred

iOS is not a practical near-term target for this project's direct USB/serial workflow.

For SerialSlinger, the critical path is reliable access to attached USB serial hardware. Android supports USB host APIs directly, while iOS generally requires Apple-supported accessory paths rather than broad direct USB serial access.

That leaves the current platform plan as:

- keep desktop support active across macOS, Windows, and Linux
- keep Android in scope as the first mobile target
- defer iOS unless SignalSlinger adopts a transport model that fits Apple's supported frameworks

## Why Not Start With Shared UI

The biggest near-term risk is not the table widget. It is:

- defining the settings model correctly
- validating values correctly
- translating between user-facing fields and device protocol correctly
- handling platform-specific device access

So the first slice should share **domain and protocol logic**, not UI.

## Local Tooling Reality

As of May 31, 2026, local macOS development uses a registered full JDK 17
resolved through `/usr/libexec/java_home`. This machine currently uses Eclipse
Temurin 17 for Gradle, desktop packaging, and jDeploy preparation.

The stack decision remains the same: Kotlin Multiplatform is still the right
strategic fit, and the immediate local blocker noted in the original spike has
been removed.

## First TDD Scope

The first TDD slice should prove the shared core model works before we touch transport or UI:

1. `SettingsField` dirty-state behavior
2. conversion from editable fields to validated settings
3. diffing old and new settings into a stable `WritePlan`
4. capability-based filtering of unsupported fields

## Sources

- JetBrains: Kotlin Multiplatform overview
- Kotlin docs: platform-specific APIs with expect/actual
- Android Developers: Kotlin Multiplatform environment setup
