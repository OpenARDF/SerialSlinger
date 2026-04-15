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

## GitHub Release Workflow

The repository includes a macOS-focused GitHub Actions workflow at [.github/workflows/jdeploy-release.yml](/Users/charlesscharlau/Documents/GitHub/SerialSlinger/.github/workflows/jdeploy-release.yml).

It:

- publishes jDeploy release artifacts for tags beginning with `v`
- targets GitHub releases rather than npm publishing
- refreshes the special `jdeploy` release icon asset
- repairs the macOS installer wrapper icon after publish so public Mac installers stay branded with the project icon

The intended release flow is:

1. run `npm run jdeploy:release-preflight`
2. merge the desired release state to `main`
3. create and push a tag like `v1.0.89`
4. let the GitHub Actions workflow publish the release artifacts

The chosen public package identity is `serialslinger`, and the shared public and app version line is `1.0.89`.

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
