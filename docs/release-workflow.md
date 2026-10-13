# Release Workflow

This document is the standard SerialSlinger release process. Commands assume
the repository root unless stated otherwise.

## Branch And Approval Model

- `main` is the single integration and release branch.
- Start each development slice from synchronized `main` on a focused
  `codex/<feature>` topic branch.
- Merge every change through a pull request into protected `main` after the
  required build-and-test gate passes and review conversations are resolved.
- Protected `main` requires linear history and rejects force pushes and
  deletion. Formal approving reviews remain optional for the single-maintainer
  workflow.
- Release publication is a manually dispatched GitHub workflow. It defaults to
  a dry run and its publication job is guarded by the `production` environment.
- The workflow creates the annotated tag only after the candidate and final
  assets pass validation. Release evidence belongs in the immutable GitHub
  Release manifest and attestations, not later source commits.

## Version Rules

- Normal deployments use the next plain `x.y.z` patch version from the latest
  published release.
- Clear `serialSlingerVersionSuffix` before release. Alphabetic suffixes are
  local-test-only.
- Keep these sources aligned:
  - `build.gradle.kts`: `serialSlingerVersion` and blank suffix
  - `package.json` and `package-lock.json`: npm/jDeploy version
  - `androidApp/build.gradle.kts`: incremented `versionCode`; `versionName`
    continues to follow the shared display version

## Checked Release Inputs

Create these files on the release-candidate branch:

- `docs/release-checklist-X.Y.Z.json`, copied from
  `docs/release-checklist-template.json`
- `docs/release-notes/vX.Y.Z.md`, copied from
  `docs/release-notes-template.md`

Schema 3 checklists contain only evidence known before publication. The hosted
workflow records the exact source commit, run URL, final asset names, sizes,
SHA-256 hashes, and macOS signature verification in `release-manifest.json`.
GitHub artifact attestations bind those files to the workflow and repository.

Use the checklist updater instead of hand-editing JSON evidence:

```sh
just release-checklist-done docs/release-checklist-X.Y.Z.json version-aligned "concrete evidence"
just release-checklist-skip docs/release-checklist-X.Y.Z.json linux-arm64-smoke "Charles Scharlau" "Concrete accepted reason"
```

Validate the template, checklist, and release notes:

```sh
just release-checklist docs/release-checklist-template.json template
just release-checklist docs/release-checklist-X.Y.Z.json pre-tag
just release-notes-check docs/release-checklist-X.Y.Z.json
```

## Candidate Preparation

1. Confirm the topic branch is based on current protected `main`, the working
   tree contains only intended changes, and the latest release/versionCode are
   understood.
2. Align the next plain release version and increment Android `versionCode`.
3. Complete and validate the release notes and schema 3 checklist.
4. Run release gates serially. Do not overlap heavy Gradle processes:

```sh
JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home just release-check
just secret-check
```

5. Run the direct release-package commands. Do not use the `just` jDeploy
   package recipes here because those intentionally increment the local test
   suffix:

```sh
JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home npm run jdeploy:prepare
JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home npm run jdeploy:package
JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home npm run jdeploy:install-local
JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home npm run jdeploy:verify-install
JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home npm run jdeploy:local-smoke
JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home npm run jdeploy:pack-preview
```

6. Complete the remaining acceptance evidence:
   - Play-upload-signed AAB and expected certificate fingerprint
   - Android 16 KB compatibility
   - physical Android instrumentation
   - approved attached-SignalSlinger Android regression and settings readback
   - macOS desktop regression with real hardware
   - native macOS jDeploy installed-package smoke
   - Windows and Linux x64/ARM64 packaged-app smokes, or explicit waivers
7. Run the pre-tag checklist and release-notes guards again, then open the
   release-candidate pull request into protected `main`.
8. After squash merge, wait for the exact resulting `main` commit's required
   `Desktop, Android, lint, and release-script gates` check to pass. Publication
   will reject any other source state.

## Dry Run And Publication

Always run the non-publishing path first:

```sh
gh workflow run "jDeploy Release" --ref main -f version=X.Y.Z -f publish=false
```

The dry run builds the desktop payload, prepares all jDeploy files, customizes
and ad-hoc signs the macOS wrappers locally, verifies a freshly extracted copy
of each archive, creates the release manifest, generates artifact attestations,
and stores the exact proven payload as a 14-day workflow artifact. It does not
create a tag, GitHub Release, or moving jDeploy metadata.

Review the successful run and its manifest. Then start the publishing run:

```sh
gh workflow run "jDeploy Release" --ref main -f version=X.Y.Z -f publish=true
```

The preparation job repeats the deterministic proof from the exact protected
`main` commit. The publication job then waits at the protected `production`
environment. After approval it:

1. creates or verifies the annotated tag at the proven commit;
2. creates a draft GitHub Release;
3. uploads the final assets once;
4. downloads every draft asset and verifies it against the manifest;
5. publishes a stable, non-prerelease release using the checked notes; and
6. updates moving jDeploy metadata last, preserving the prior primary metadata
   as `package-info-2.json` before replacing `package-info.json`.

The scripts are retry-safe when the existing tag points to the same source
commit. They stop rather than moving an existing tag or accepting a different
asset set.

## Final Verification

```sh
gh release view vX.Y.Z --json tagName,isDraft,isPrerelease,publishedAt,url,assets
gh release download vX.Y.Z -D build/release-verification/X.Y.Z
node scripts/release-manifest.mjs verify --directory build/release-verification/X.Y.Z
gh attestation verify build/release-verification/X.Y.Z/serialslinger-X.Y.Z.tgz -R OpenARDF/SerialSlinger
```

The workflow-generated manifest and GitHub run are the final release record; no
tag-evidence or post-publication evidence pull request is required.

## Android Distribution Stages

Keep these outcomes distinct:

1. installed and tested on a physical device;
2. signed Play-ready AAB produced and certificate verified;
3. uploaded to the Play internal-testing track;
4. promoted to production.

The protected `Android Play deployment` workflow handles stage 3 separately
from jDeploy publication. Its default mode validates inputs without committing a
Play edit. Production promotion remains a separate explicit Play Console action
until a dedicated staged-rollout workflow is intentionally approved.
