# SerialSlinger

SerialSlinger is a standalone software project for configuring a SignalSlinger device through a graphical user interface instead of manual serial commands.

## Product Goal

When a SignalSlinger is connected to a serial port, SerialSlinger should:

1. connect to the device
2. read the device's current settings
3. present those settings to the user in a graphical table
4. let the user edit those settings in that table
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

## jDeploy Groundwork

From the repository root:

- `npm install`
- `npm run jdeploy:prepare`
- `npm run jdeploy:package`
- `npm run jdeploy:install-local`
- `npm run jdeploy:verify-install`
- `npm run jdeploy:local`
- `npm run jdeploy:pack-preview`

These tasks prepare and use the executable desktop jar layout that jDeploy expects under:

- `shared/build/jdeploy`

The generated jar is:

- `shared/build/jdeploy/SerialSlinger-jdeploy.jar`

Running `npm install` pulls in the project-local `jdeploy` CLI used by the smoke-test workflow.
`npm run jdeploy:prepare` explicitly rebuilds and verifies the generated desktop jDeploy bundle.
The repo-local npm wrappers are configured so `npm run jdeploy:package`, `npm run jdeploy:install-local`, and `npm run jdeploy:local` automatically prepare that bundle first.
Running `npm run jdeploy:package` creates a local `jdeploy-bundle/` directory for install/publish preparation. That directory is generated output and is ignored by git.
Running `npm run jdeploy:install-local` also creates a local `jdeploy/` working directory while preparing the installer metadata. That directory is also generated output and is ignored by git.
For local prerelease testing before the first real jDeploy publish, prefer `npm run jdeploy:local`. Launching the installed app bundle directly may try to check remote package metadata that does not exist yet, and mixing both launch paths can result in multiple local app instances during testing.
`npm run jdeploy:local` is the repeatable local smoke-test command. It uses the project-local `jdeploy run --install` path and automatically prepares the generated bundle first.
`npm run jdeploy:verify-install` runs jDeploy's installation verification against the current local `package.json`.
`npm run jdeploy:pack-preview` shows the exact npm/jDeploy package payload without publishing anything, using the generated `jdeploy-bundle/` layout that jDeploy publishes.
The repository also includes a macOS-focused GitHub Actions workflow at [.github/workflows/jdeploy-release.yml](/Users/charlesscharlau/Documents/GitHub/SerialSlinger/.github/workflows/jdeploy-release.yml). It publishes jDeploy release artifacts for tags beginning with `v`, which keeps routine branch pushes from creating unwanted public installer releases.
For the first public GitHub-hosted jDeploy release, the intended flow is:

- merge the desired release state to `main`
- create and push a tag like `v0.1.85`
- let the GitHub Actions workflow publish the macOS jDeploy release artifacts for that tag

The workflow is pinned to the GitHub release target rather than npm publishing, so it matches the near-term goal of offering a GitHub-based installation path first.

The repository now also includes a baseline [package.json](/Users/charlesscharlau/Documents/GitHub/SerialSlinger/package.json) for jDeploy that points at that generated jar and uses the baseline app icon. The chosen public package identity is `serialslinger`.

The publish guard is still enforced by [scripts/check-jdeploy-publish.mjs](/Users/charlesscharlau/Documents/GitHub/SerialSlinger/scripts/check-jdeploy-publish.mjs) through `package.json`'s `prepublishOnly` hook. A real publish will stop until you intentionally set `SERIALSLINGER_ALLOW_JDEPLOY_PUBLISH=1` and confirm that the generated jDeploy artifacts are the ones you want to ship.

This jDeploy path is additive. It does not replace the existing `jpackage`-based native packaging flow.

## macOS Packaging

From the repository root on macOS:

- `./gradlew verifyDesktopPackagingEnvironment`
- `./gradlew desktopAppImage`
- `./gradlew desktopDmg`

The generated packaging artifacts are written under:

- `shared/build/packaging/output`

`desktopDmg` currently produces an unsigned `.dmg` for local drag-and-drop testing. Apple signing and notarization can be added later for normal end-user distribution.

Note: the packaged macOS installer version uses a `1.x.y` format because `jpackage` does not accept an app-package version beginning with `0`. The in-app SerialSlinger version shown in the log and window title remains on the project’s `0.1.x` development track, and both values now come from the same version declaration in `build.gradle.kts`.
The packaging preflight task checks that the active JDK actually includes `jpackage` before you try to build an app image or installer.

## Windows Packaging

From the repository root on Windows:

- `gradlew.bat verifyDesktopPackagingEnvironment`
- `gradlew.bat desktopExe`
- `gradlew.bat desktopMsi`

The generated packaging artifacts are written under:

- `shared\\build\\packaging\\output`

Notes:

- `desktopExe` is the simplest Windows installer target to start with.
- `desktopMsi` may require Windows packaging tooling such as WiX depending on the local JDK/jpackage setup.
- Windows installers use the same `1.x.y` package-version track as macOS packaging, while the in-app SerialSlinger version remains on the `0.1.x` development track from the same root version declaration.
