#!/usr/bin/env node

import assert from "node:assert/strict";
import test from "node:test";

import { remoteVersionTagIsAllowed, validateReleaseTag } from "./check-release-tag.mjs";

const environment = {
  GITHUB_REF_TYPE: "tag",
  GITHUB_REF_NAME: "v2.1.0",
  GITHUB_SHA: "0123456789abcdef"
};
const packageJson = { name: "serialslinger", version: "2.1.0" };
const checklist = {
  release: "v2.1.0",
  version: "2.1.0",
  sourceCommit: "fedcba9876543210"
};
const tagCommit = {
  parentSha: checklist.sourceCommit,
  changedFiles: ["docs/release-checklist-2.1.0.json"]
};

test("accepts a matching tag and checklist-only commit over the verified source", () => {
  assert.equal(validateReleaseTag(environment, packageJson, checklist, tagCommit), "v2.1.0");
});

test("rejects a mismatched release identity", () => {
  assert.throws(
    () => validateReleaseTag({ ...environment, GITHUB_REF_NAME: "v2.1.1" }, packageJson, checklist, tagCommit),
    /does not match package version/
  );
  assert.throws(
    () => validateReleaseTag(environment, packageJson, { ...checklist, sourceCommit: "different" }, tagCommit),
    /sourceCommit/
  );
});

test("rejects tagged checklist commits that include other changes", () => {
  assert.throws(
    () => validateReleaseTag(environment, packageJson, checklist, {
      ...tagCommit,
      changedFiles: [...tagCommit.changedFiles, "shared/src/commonMain/kotlin/Unexpected.kt"]
    }),
    /may change only/
  );
});

test("allows an existing version tag only inside its exact tag workflow", () => {
  const expectedTag = "v2.0.22-d";
  assert.equal(remoteVersionTagIsAllowed("", {}, expectedTag), true);
  assert.equal(remoteVersionTagIsAllowed("remote-ref", {}, expectedTag), false);
  assert.equal(
    remoteVersionTagIsAllowed(
      "remote-ref",
      { GITHUB_REF_TYPE: "tag", GITHUB_REF_NAME: expectedTag },
      expectedTag
    ),
    true
  );
  assert.equal(
    remoteVersionTagIsAllowed(
      "remote-ref",
      { GITHUB_REF_TYPE: "tag", GITHUB_REF_NAME: "v2.0.22-e" },
      expectedTag
    ),
    false
  );
});
