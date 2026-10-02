# Release Workflow

This document captures the standard SerialSlinger release process. Commands
assume the repository root unless stated otherwise.

## Branch Roles

- `main` is the stable release branch.
- `Development1` is the active development branch.
- Start each development slice from a clean, synchronized `Development1` on a
  focused `codex/<feature>` topic branch. Merge the topic branch back through a
  pull request after the required build-and-test gate passes; formal approving
  reviews are optional for the single-maintainer workflow.
- Protected branches require linear history and reject force pushes and
  deletion. GitHub automatically deletes merged remote topic branches.
- The exact-history synchronization required by a full release is the only
  direct-push exception for `Development1`; the controlled procedure below
  temporarily relaxes and immediately restores its pull-request gates.
- A full deployment synchronizes `main` and `Development1` to the same final
  release-evidence commit, then leaves `Development1` checked out.
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
git rev-list --left-right --count main...Development1
```

2. Prepare the next plain patch version and increment Android `versionCode`.
   Do not use local-test suffixes for deployment.
3. Create and complete the checked release-notes file. The `Android release
   notes` section should be terse and suitable for Play Console.
4. Run release validation gates serially. Do not run heavy Gradle gates in
   parallel. The repository wrappers cover desktop, Android host/unit tests,
   lint-as-error, 16 KB bundle compatibility, the release bundle,
   release-script tests, actionlint, ShellCheck, shfmt, Git whitespace checks,
   a zero-vulnerability npm audit, jDeploy preflight, and the secret scan.

```sh
JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home just release-check
just secret-check
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
JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home npm run jdeploy:local-smoke
JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home npm run jdeploy:pack-preview
git diff --check
```

6. Build and verify the Play-upload-ready Android bundle. `just
   android-signing-check` requires the existing upload keystore, its credentials,
   and the expected Play upload-certificate SHA-256 fingerprint. An unsigned AAB
   does not satisfy this gate.
7. Record physical and packaged acceptance separately:
   - complete Android instrumentation suite on a physical device, including the
     device model, Android version, test count, and result
   - destructive Android attached-SignalSlinger regression with the approved
     target and final readback, or an explicit waiver with requester
   - macOS desktop regression on real hardware, or explicit evidence that
     hardware testing already passed for this release.
   - macOS native jDeploy install and isolated installed-package probe
   - Windows Intel x64, Windows ARM64, Linux Intel x64, and Linux ARM64 packaged
     smokes when hosts are available. Record skipped checks with requester and
     concrete reason.
8. Run the release-notes and pre-tag checklist guards:

```sh
just release-notes-check docs/release-checklist-X.Y.Z.json
just release-checklist docs/release-checklist-X.Y.Z.json pre-tag
```

9. Prepare the release candidate on a focused `codex/release-X.Y.Z` topic
   branch, open a pull request into `Development1`, and squash-merge it after
   the required build-and-test gate passes. Fast-forward `main` to the merged
   candidate, record the main-sync evidence, and commit that evidence on
   `main`.
10. Set the checklist `sourceCommit` to the verified main-sync commit and commit
   only that checklist update. Create the annotated tag at this checklist-only
   child commit. The hosted workflow requires `sourceCommit` to equal the tagged
   commit's sole parent and rejects any other file change in the tagged commit:

```sh
git tag -a vX.Y.Z -m "SerialSlinger X.Y.Z"
git push origin main vX.Y.Z
```

11. Watch the GitHub Actions `jDeploy Release` workflow through completion:

```sh
gh run list --workflow "jDeploy Release" --limit 5
gh run watch <run-id> --exit-status
```

12. Verify the release:

```sh
gh release view vX.Y.Z --json tagName,targetCommitish,isDraft,isPrerelease,publishedAt,url,assets
curl -L -I https://github.com/OpenARDF/SerialSlinger/releases/download/vX.Y.Z/serialslinger-X.Y.Z.tgz
```

13. Record final checklist evidence, run the final checklist guard, commit the
    post-tag evidence update, and push `main`. To retain the exact tag and
    evidence commits on both long-lived branches, temporarily suspend only
    `Development1`'s required-pull-request and required-status-check settings,
    fast-forward it to `main`, and push it. Immediately restore and read back
    those settings, confirm force pushes and deletion remain disabled, and
    leave `Development1` checked out.

## Publication Notes

- The GitHub workflow publishes on `v*` tags, targets GitHub releases, uploads
  the canonical npm tarball, repairs macOS jDeploy branding, and publishes the
  release.
- If `docs/release-notes/vX.Y.Z.md` exists in the tagged source, the workflow
  uses it as the GitHub release body.
- GitHub release assets, not npm registry publication, are the release target.
- Android upload-key signing is machine-specific. `:androidApp:bundleRelease`
  may create an unsigned artifact when credentials are absent, so only
  `just android-signing-check` establishes Play-upload readiness by verifying
  both the JAR signature and the expected upload-certificate fingerprint.
