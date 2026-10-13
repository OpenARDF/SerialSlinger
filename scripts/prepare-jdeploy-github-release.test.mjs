#!/usr/bin/env node

import assert from "node:assert/strict";
import { mkdtempSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import test from "node:test";

import {
  validateReleaseContext,
  validateReleaseFiles,
  withGithubReleaseMetadata
} from "./prepare-jdeploy-github-release.mjs";

const environment = {
  SERIALSLINGER_RELEASE_WORKFLOW: "1",
  SERIALSLINGER_RELEASE_TAG: "v2.1.0",
  SERIALSLINGER_RELEASE_COMMIT: "0123456789abcdef",
  GITHUB_REPOSITORY: "OpenARDF/SerialSlinger",
  GH_TOKEN: "test-token"
};
const packageJson = {
  name: "serialslinger",
  version: "2.1.0",
  devDependencies: { jdeploy: "6.1.7" },
  jdeploy: { javaVersion: "17" }
};

test("validates a matching tag release context", () => {
  assert.equal(validateReleaseContext(environment, packageJson), "v2.1.0");
  assert.throws(
    () => validateReleaseContext({ ...environment, SERIALSLINGER_RELEASE_WORKFLOW: "0" }, packageJson),
    /guarded release workflow/
  );
  assert.throws(
    () => validateReleaseContext({ ...environment, GH_TOKEN: "" }, packageJson),
    /requires an exact release commit, repository, and token/
  );
});

test("adds only jDeploy release metadata", () => {
  const prepared = withGithubReleaseMetadata(packageJson, environment);
  assert.equal(prepared.jdeploy.jdeployVersion, "6.1.7");
  assert.equal(prepared.jdeploy.commitHash, environment.SERIALSLINGER_RELEASE_COMMIT);
  assert.equal(prepared.jdeploy.gitTag, environment.SERIALSLINGER_RELEASE_TAG);
  assert.equal(packageJson.jdeploy.commitHash, undefined);
});

test("requires every release file consumed by publication", () => {
  const directory = mkdtempSync(join(tmpdir(), "serialslinger-jdeploy-test-"));
  try {
    writeFileSync(join(directory, "jdeploy-release-notes.md"), "## Installers\n");
    writeFileSync(join(directory, "package.json"), "{}\n");
    assert.throws(() => validateReleaseFiles(directory), /package-info.json/);
    writeFileSync(join(directory, "package-info.json"), "{}\n");
    assert.doesNotThrow(() => validateReleaseFiles(directory));
  } finally {
    rmSync(directory, { recursive: true, force: true });
  }
});
