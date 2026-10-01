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
  sourceCommit: environment.GITHUB_SHA
};

test("accepts a matching tag, package, checklist, and source commit", () => {
  assert.equal(validateReleaseTag(environment, packageJson, checklist), "v2.1.0");
});

test("rejects a mismatched release identity", () => {
  assert.throws(
    () => validateReleaseTag({ ...environment, GITHUB_REF_NAME: "v2.1.1" }, packageJson, checklist),
    /does not match package version/
  );
  assert.throws(
    () => validateReleaseTag(environment, packageJson, { ...checklist, sourceCommit: "different" }),
    /sourceCommit/
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
