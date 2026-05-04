# Packaging And Release

This document collects the build, packaging, and release details that are useful for maintainers and contributors, while keeping the repository `README.md` focused on end users.

## jDeploy Workflow

From the repository root:

- `npm install`
- `npm run jdeploy:prepare`
- `npm run jdeploy:package`
- `npm run jdeploy:install-local`
- `npm run jdeploy:verify-install`
- `npm run jdeploy:local`
- `npm run jdeploy:pack-preview`
- `npm run jdeploy:release-preflight`

These tasks prepare and use the executable desktop jar layout that jDeploy expects under:

- `shared/build/jdeploy`

The generated jar is:

- `shared/build/jdeploy/SerialSlinger-jdeploy.jar`

Notes:

- `npm install` pulls in the project-local `jdeploy` CLI used by the smoke-test workflow.
- `npm run jdeploy:prepare` explicitly rebuilds and verifies the generated desktop jDeploy bundle.
- `npm run jdeploy:package`, `npm run jdeploy:install-local`, and `npm run jdeploy:local` automatically prepare the bundle first.
- `npm run jdeploy:package` creates a local `jdeploy-bundle/` directory for install and publish preparation. It is generated output and ignored by git.
- `npm run jdeploy:install-local` also creates a local `jdeploy/` working directory while preparing installer metadata. It is also generated output and ignored by git.
- For local prerelease testing before the first real jDeploy publish, prefer `npm run jdeploy:local`.
- `npm run jdeploy:verify-install` runs jDeploy's installation verification against the current local `package.json`.
- `npm run jdeploy:pack-preview` shows the exact npm and jDeploy package payload without publishing anything.
- `npm run jdeploy:release-preflight` checks that the Gradle version, npm version, jDeploy workflow, and intended `v*` release tag are aligned before a public release tag is pushed.

## Iterative Test Builds

For testable in-progress Android and desktop builds, keep the release version at `x.y.z` and increment only the alphabetical suffix:

- app-visible version: `1.0.98a`
- npm/jDeploy package version: `1.0.98-a`

Current workflow:

1. leave `serialSlingerVersion` in [build.gradle.kts](/Users/charlesscharlau/Documents/GitHub/SerialSlinger/build.gradle.kts) at the intended release version
2. increment `serialSlingerVersionSuffix` for each new test build: `a`, `b`, `c`, ... then `aa`, `ab`, ...
3. keep `package.json` and `package-lock.json` aligned to the npm-safe form with a hyphen before the suffix
4. clear the suffix before a full release so both app-visible and package versions return to plain `x.y.z`

This keeps Android and desktop builds visibly distinct during testing without accidentally rolling the release version forward.

## GitHub Release Workflow

The repository includes a macOS-focused GitHub Actions workflow at [.github/workflows/jdeploy-release.yml](/Users/charlesscharlau/Documents/GitHub/SerialSlinger/.github/workflows/jdeploy-release.yml).

It:

- publishes jDeploy release artifacts for tags beginning with `v`
- targets GitHub releases rather than npm publishing
- refreshes the special `jdeploy` release icon asset
- repairs the macOS installer wrapper icon after publish so public Mac installers stay branded with the project icon

The intended release flow is:

1. run `npm run jdeploy:release-preflight`
2. review platform parity:
   - ask whether any Android app changes in the release should also be carried into the desktop app
   - ask whether any desktop app changes in the release should also be carried into the Android app
   - either carry the needed changes across or explicitly record why no cross-platform change is needed
3. run the automated Android tablet regression: `./scripts/android-regression.sh --serial <adb-serial>`
4. run the desktop app regression series on macOS with a real attached SignalSlinger
5. merge the desired release state to `main`
6. create and push a tag like `v1.0.93`
7. let the GitHub Actions workflow publish the release artifacts

Do not push a normal public release tag until both real-device regression passes have completed:

- automated Android tablet regression on a real attached SignalSlinger test device. The regression is destructive to the attached device settings and starts from normal Android UI mode before exercising normal-mode load, setting submit, schedule, raw command, log, and clone paths.
- desktop app regression on macOS with the SerialSlinger desktop UI launched against a real attached SignalSlinger. The pass should exercise the actual app window, not only the desktop smoke CLI: connect/load, normal-mode setting edits and submit, relative start-time scheduling, disable event, sync/set time, raw command/log behavior, clone where appropriate, and post-test CLI readback to confirm the device is left in a known acceptable state. Before GUI automation, close any installed SerialSlinger app, launch the checkout with Gradle, and confirm the tested window/log session shows the intended version, process ID, and launch directory so installed-app sessions do not contaminate the regression log.

The chosen public package identity is `serialslinger`, and the shared public and app version line is `1.0.93`.

The publish guard is still enforced by [scripts/check-jdeploy-publish.mjs](/Users/charlesscharlau/Documents/GitHub/SerialSlinger/scripts/check-jdeploy-publish.mjs) through `package.json`'s `prepublishOnly` hook. A real publish will stop until `SERIALSLINGER_ALLOW_JDEPLOY_PUBLISH=1` is intentionally set.

## macOS Packaging

From the repository root on macOS:

- `./gradlew verifyDesktopPackagingEnvironment`
- `./gradlew desktopAppImage`
- `./gradlew desktopDmg`

The generated packaging artifacts are written under:

- `shared/build/packaging/output`

Notes:

- `desktopDmg` currently produces an unsigned `.dmg` for local drag-and-drop testing.
- Apple signing and notarization can be added later for normal end-user distribution.
- The packaged macOS installer version, the in-app SerialSlinger version, and the jDeploy/npm version all come from the same version declaration in `build.gradle.kts`.
- The packaging preflight task checks that the active JDK actually includes `jpackage` before packaging starts.

## Windows Packaging

From the repository root on Windows:

- `gradlew.bat verifyDesktopPackagingEnvironment`
- `gradlew.bat desktopExe`
- `gradlew.bat desktopMsi`

The generated packaging artifacts are written under:

- `shared\\build\\packaging\\output`

Notes:

- `desktopExe` is the simplest Windows installer target to start with.
- `desktopMsi` may require Windows packaging tooling such as WiX depending on the local JDK and `jpackage` setup.
- Windows installers use the same shared version line as macOS packaging, the in-app SerialSlinger version, and the jDeploy/npm package.

## Android Signed Bundles

SerialSlinger's Android module can load release signing inputs from either:

- a local `keystore.properties` file in the repository root
- environment variables prefixed with `SERIALSLINGER_UPLOAD_`

The recommended local setup is:

1. copy [keystore.properties.example](/Users/charlesscharlau/Documents/GitHub/SerialSlinger/keystore.properties.example) to `keystore.properties`
2. fill in:
   - `storeFile`
   - `storePassword`
   - `keyAlias`
   - `keyPassword`

The real `keystore.properties` file is ignored by git and should not be committed.

Useful commands:

- `./gradlew printAndroidReleaseSigningStatus`
- `./gradlew :androidApp:bundleRelease`

If signing inputs are present, `bundleRelease` produces a signed release bundle. If they are absent, Gradle still builds the release bundle, but it will not be configured with the local upload key.
