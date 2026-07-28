# Release Workflow

This document captures the standard SerialSlinger release process. Commands
assume the repository root unless stated otherwise.

## Branch Roles

- `main` is the stable release branch.
- `Development_Android` is the active development branch.
- A full deployment synchronizes `main` and `Development_Android` to the same
  final release-evidence commit, then leaves `Development_Android` checked out.
- Before making release changes, confirm the current branch and working tree.
  Existing unrelated changes must be intentionally excluded from the release.

## Version Rules

- Normal deployments use the next plain `x.y.z` patch version from the latest
  published GitHub release.
- Clear `serialSlingerVersionSuffix` before tagging. Alphabetic suffixes are
  local-test-only.
- Keep these sources aligned:
  - `build.gradle.kts`: `serialSlingerVersion` and blank suffix
  - `package.json`: npm/jDeploy version
  - `package-lock.json`: root package version
  - `androidApp/build.gradle.kts`: incremented `versionCode`
- Android `versionName` follows the shared root display version.

## Release Files

For each release, create:

- `docs/release-checklist-X.Y.Z.json` from
  `docs/release-checklist-template.json`
- `docs/release-notes/vX.Y.Z.md` from `docs/release-notes-template.md`

The release notes file is the checked source for GitHub release notes and the
Android Play Console copy. Validate it before tagging:

```sh
just release-notes-check docs/release-checklist-X.Y.Z.json
```

Use the checklist updater instead of hand-editing JSON when recording evidence:

```sh
just release-checklist-done docs/release-checklist-X.Y.Z.json version-aligned "concrete evidence"
just release-checklist-skip docs/release-checklist-X.Y.Z.json linux-arm64-smoke "Charles Scharlau" "No Linux ARM64 host is available in this release session."
```

Validate the template and release checklist phases:

```sh
just release-checklist docs/release-checklist-template.json template
just release-checklist docs/release-checklist-X.Y.Z.json pre-tag
just release-checklist docs/release-checklist-X.Y.Z.json final
```

## Standard Release Flow

1. Confirm branch, status, branch divergence, and latest GitHub release:

```sh
git status --short --branch
git fetch origin --prune --tags
gh release list --limit 5
git rev-list --left-right --count main...Development_Android
```

2. Prepare the next plain patch version and increment Android `versionCode`.
   Do not use local-test suffixes for deployment.
3. Create and complete the checked release-notes file. The `Android release
   notes` section should be terse and suitable for Play Console.
4. Run release validation gates serially. Do not run heavy Gradle gates in
   parallel.

```sh
JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home npm run jdeploy:release-preflight
JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home ./gradlew check
JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home ./gradlew prepareDesktopJdeployBundle verifyDesktopJdeployBundle
JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home ./gradlew androidApp:compileDebugKotlin
JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home ./gradlew printAndroidReleaseSigningStatus :androidApp:bundleRelease
```

5. Run the release-package checks with direct npm commands, not the `just`
   jDeploy package recipes. The `just jdeploy-prepare`, `just jdeploy-package`,
   `just jdeploy-install-local`, `just jdeploy-local`, and
   `just jdeploy-pack-preview` recipes intentionally run `local-version-bump`
   for test builds.

```sh
JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home npm run jdeploy:prepare
JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home npm run jdeploy:package
JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home npm run jdeploy:install-local
JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home npm run jdeploy:verify-install
JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home npm run jdeploy:local
JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home npm run jdeploy:pack-preview
gitleaks detect . --no-banner
git diff --check
```

6. Record hardware validation:
   - Android tablet regression on real hardware, or explicit evidence that
     hardware testing already passed for this release.
   - macOS desktop regression on real hardware, or explicit evidence that
     hardware testing already passed for this release.
   - Windows Intel x64, Windows ARM64, Linux Intel x64, and Linux ARM64 packaged
     smokes when hosts are available. Record skipped checks with requester and
     concrete reason.
7. Run the release-notes and pre-tag checklist guards:

```sh
just release-notes-check docs/release-checklist-X.Y.Z.json
just release-checklist docs/release-checklist-X.Y.Z.json pre-tag
```

8. Commit the release candidate on `Development_Android`, fast-forward `main`,
   record the main-sync evidence, and commit that evidence on `main`.
9. Create and push the annotated tag from the verified main-sync commit:

```sh
git tag -a vX.Y.Z -m "SerialSlinger X.Y.Z"
git push origin main vX.Y.Z
```

10. Watch the GitHub Actions `jDeploy Release` workflow through completion:

```sh
gh run list --workflow "jDeploy Release" --limit 5
gh run watch <run-id> --exit-status
```

11. Verify the release:

```sh
gh release view vX.Y.Z --json tagName,targetCommitish,isDraft,isPrerelease,publishedAt,url,assets
curl -L -I https://github.com/OpenARDF/SerialSlinger/releases/download/vX.Y.Z/serialslinger-X.Y.Z.tgz
```

12. Record final checklist evidence, run the final checklist guard, commit the
    post-tag evidence update, push `main`, fast-forward `Development_Android` to
    the same commit, push it, and leave `Development_Android` checked out.

## Publication Notes

- The GitHub workflow publishes on `v*` tags, targets GitHub releases, uploads
  the canonical npm tarball, repairs macOS jDeploy branding, and publishes the
  release.
- If `docs/release-notes/vX.Y.Z.md` exists in the tagged source, the workflow
  uses it as the GitHub release body.
- GitHub release assets, not npm registry publication, are the release target.
- Android upload-key signing is machine-specific. `:androidApp:bundleRelease`
  should run during release validation, but Play upload readiness depends on
  local `keystore.properties` or `SERIALSLINGER_UPLOAD_*` environment variables.
