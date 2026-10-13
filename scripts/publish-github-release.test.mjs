#!/usr/bin/env node

import assert from "node:assert/strict";
import { execFileSync } from "node:child_process";
import { chmodSync, cpSync, mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join, resolve } from "node:path";
import test from "node:test";

import { createReleaseManifest, manifestFileName } from "./release-manifest.mjs";

const publishScript = resolve("scripts/publish-github-release.sh");

function makeFixture() {
  const root = mkdtempSync(join(tmpdir(), "serialslinger-publish-test-"));
  const releaseDirectory = join(root, "release-files");
  const fakeBin = join(root, "bin");
  const stateDirectory = join(root, "state");
  mkdirSync(releaseDirectory);
  mkdirSync(fakeBin);
  mkdirSync(stateDirectory);
  writeFileSync(join(releaseDirectory, "SerialSlinger.Installer-mac-arm64.tgz"), "installer\n");
  writeFileSync(join(releaseDirectory, "package-info.json"), '{"versions":["2.1.0"]}\n');
  const manifest = createReleaseManifest({
    directory: releaseDirectory,
    version: "2.1.0",
    tag: "v2.1.0",
    sourceCommit: "0123456789abcdef",
    workflowUrl: "https://example.invalid/run/123",
    generatedAt: "2026-10-12T12:00:00.000Z",
  });
  writeFileSync(join(releaseDirectory, manifestFileName), `${JSON.stringify(manifest, null, 2)}\n`);

  const fakeGit = join(fakeBin, "git");
  writeFileSync(
    fakeGit,
    `#!/usr/bin/env bash
set -euo pipefail
printf 'git %s\n' "$*" >> "$FAKE_CALL_LOG"
if [[ "$1" == "rev-parse" ]]; then
  if [[ "\${FAKE_EXISTING_TAG:-0}" == "1" ]]; then printf '%s\n' '0123456789abcdef'; else exit 1; fi
fi
`,
  );
  chmodSync(fakeGit, 0o755);

  const fakeGh = join(fakeBin, "gh");
  writeFileSync(
    fakeGh,
    `#!/usr/bin/env bash
set -euo pipefail
printf 'gh %s\n' "$*" >> "$FAKE_CALL_LOG"
if [[ "$1 $2" == "release view" ]]; then
  [[ -f "$FAKE_STATE/release" ]] || exit 1
  if [[ "$*" == *"--json isDraft,isPrerelease"* ]]; then
    printf 'false\tfalse\n'
  fi
elif [[ "$1 $2" == "release create" ]]; then
  touch "$FAKE_STATE/release"
elif [[ "$1 $2" == "release download" ]]; then
  destination=""
  while (($#)); do
    if [[ "$1" == "-D" ]]; then destination="$2"; break; fi
    shift
  done
  mkdir -p "$destination"
  cp "$FAKE_RELEASE_FILES"/* "$destination/"
fi
`,
  );
  chmodSync(fakeGh, 0o755);
  return {
    root,
    releaseDirectory,
    fakeBin,
    stateDirectory,
    callLog: join(root, "calls.log"),
  };
}

test("creates the tag only after validation and publishes only after readback", () => {
  const fixture = makeFixture();
  try {
    execFileSync("bash", [publishScript, fixture.releaseDirectory], {
      cwd: resolve("."),
      env: {
        ...process.env,
        PATH: `${fixture.fakeBin}:${process.env.PATH}`,
        FAKE_CALL_LOG: fixture.callLog,
        FAKE_RELEASE_FILES: fixture.releaseDirectory,
        FAKE_STATE: fixture.stateDirectory,
        GH_TOKEN: "test-token",
        GITHUB_REPOSITORY: "OpenARDF/SerialSlinger",
        SERIALSLINGER_ALLOW_GITHUB_RELEASE_PUBLISH: "1",
        SERIALSLINGER_RELEASE_COMMIT: "0123456789abcdef",
        SERIALSLINGER_RELEASE_TAG: "v2.1.0",
        SERIALSLINGER_RELEASE_WORKFLOW: "1",
      },
      stdio: "pipe",
    });
    const calls = readFileSync(fixture.callLog, "utf8");
    assert.ok(calls.indexOf("git tag -a") < calls.indexOf("gh release upload"));
    assert.ok(calls.indexOf("gh release upload") < calls.indexOf("gh release download"));
    assert.ok(calls.indexOf("gh release download") < calls.indexOf("gh release edit"));
  } finally {
    rmSync(fixture.root, { recursive: true, force: true });
  }
});

test("refuses publication without the protected-job opt-in", () => {
  const fixture = makeFixture();
  try {
    assert.throws(
      () =>
        execFileSync("bash", [publishScript, fixture.releaseDirectory], {
          cwd: resolve("."),
          env: { ...process.env, PATH: `${fixture.fakeBin}:${process.env.PATH}` },
          stdio: "pipe",
        }),
      /Command failed/u,
    );
  } finally {
    rmSync(fixture.root, { recursive: true, force: true });
  }
});

test("a successful retry verifies but never mutates an existing public release", () => {
  const fixture = makeFixture();
  try {
    writeFileSync(join(fixture.stateDirectory, "release"), "public\n");
    execFileSync("bash", [publishScript, fixture.releaseDirectory], {
      cwd: resolve("."),
      env: {
        ...process.env,
        PATH: `${fixture.fakeBin}:${process.env.PATH}`,
        FAKE_CALL_LOG: fixture.callLog,
        FAKE_EXISTING_TAG: "1",
        FAKE_RELEASE_FILES: fixture.releaseDirectory,
        FAKE_STATE: fixture.stateDirectory,
        GH_TOKEN: "test-token",
        GITHUB_REPOSITORY: "OpenARDF/SerialSlinger",
        SERIALSLINGER_ALLOW_GITHUB_RELEASE_PUBLISH: "1",
        SERIALSLINGER_RELEASE_COMMIT: "0123456789abcdef",
        SERIALSLINGER_RELEASE_TAG: "v2.1.0",
        SERIALSLINGER_RELEASE_WORKFLOW: "1",
      },
      stdio: "pipe",
    });
    const calls = readFileSync(fixture.callLog, "utf8");
    assert.match(calls, /gh release download/u);
    assert.doesNotMatch(calls, /gh release upload/u);
    assert.doesNotMatch(calls, /gh release edit/u);
    assert.doesNotMatch(calls, /git push/u);
  } finally {
    rmSync(fixture.root, { recursive: true, force: true });
  }
});

test("rejects a self-consistent public release that differs from this run's proven manifest", () => {
  const fixture = makeFixture();
  const publicDirectory = join(fixture.root, "public-release");
  try {
    cpSync(fixture.releaseDirectory, publicDirectory, { recursive: true });
    writeFileSync(join(publicDirectory, "SerialSlinger.Installer-mac-arm64.tgz"), "different installer\n");
    const differentManifest = createReleaseManifest({
      directory: publicDirectory,
      version: "2.1.0",
      tag: "v2.1.0",
      sourceCommit: "0123456789abcdef",
      workflowUrl: "https://example.invalid/run/older",
      generatedAt: "2026-10-11T12:00:00.000Z",
    });
    writeFileSync(join(publicDirectory, manifestFileName), `${JSON.stringify(differentManifest, null, 2)}\n`);
    writeFileSync(join(fixture.stateDirectory, "release"), "public\n");
    assert.throws(
      () =>
        execFileSync("bash", [publishScript, fixture.releaseDirectory], {
          cwd: resolve("."),
          env: {
            ...process.env,
            PATH: `${fixture.fakeBin}:${process.env.PATH}`,
            FAKE_CALL_LOG: fixture.callLog,
            FAKE_EXISTING_TAG: "1",
            FAKE_RELEASE_FILES: publicDirectory,
            FAKE_STATE: fixture.stateDirectory,
            GH_TOKEN: "test-token",
            GITHUB_REPOSITORY: "OpenARDF/SerialSlinger",
            SERIALSLINGER_ALLOW_GITHUB_RELEASE_PUBLISH: "1",
            SERIALSLINGER_RELEASE_COMMIT: "0123456789abcdef",
            SERIALSLINGER_RELEASE_TAG: "v2.1.0",
            SERIALSLINGER_RELEASE_WORKFLOW: "1",
          },
          stdio: "pipe",
        }),
      /Command failed/u,
    );
    const calls = readFileSync(fixture.callLog, "utf8");
    assert.match(calls, /gh release download/u);
    assert.doesNotMatch(calls, /gh release upload/u);
    assert.doesNotMatch(calls, /gh release edit/u);
  } finally {
    rmSync(fixture.root, { recursive: true, force: true });
  }
});
