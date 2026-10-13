#!/usr/bin/env node

import assert from "node:assert/strict";
import test from "node:test";

import { remoteVersionTagIsAllowed, validateReleaseRequest } from "./check-release-request.mjs";

const environment = {
  GITHUB_EVENT_NAME: "workflow_dispatch",
  GITHUB_REF: "refs/heads/main",
  GITHUB_SHA: "0123456789abcdef",
  SERIALSLINGER_RELEASE_VERSION: "2.2.0",
  SERIALSLINGER_RELEASE_TAG: "v2.2.0",
};
const packageJson = { name: "serialslinger", version: "2.2.0" };
const checklist = { release: "v2.2.0", version: "2.2.0" };

test("accepts a matching manual release from protected main", () => {
  assert.deepEqual(validateReleaseRequest(environment, packageJson, checklist), {
    tag: "v2.2.0",
    sourceCommit: environment.GITHUB_SHA,
    version: "2.2.0",
  });
});

test("rejects automatic, non-main, and mismatched release requests", () => {
  assert.throws(
    () => validateReleaseRequest({ ...environment, GITHUB_EVENT_NAME: "push" }, packageJson, checklist),
    /manual release workflow/u,
  );
  assert.throws(
    () => validateReleaseRequest({ ...environment, GITHUB_REF: "refs/heads/topic" }, packageJson, checklist),
    /protected main/u,
  );
  assert.throws(
    () => validateReleaseRequest({ ...environment, SERIALSLINGER_RELEASE_VERSION: "2.2.1" }, packageJson, checklist),
    /does not match/u,
  );
});

test("allows an absent tag or an exact same-commit workflow retry", () => {
  assert.equal(remoteVersionTagIsAllowed("", {}, "v2.2.0"), true);
  assert.equal(remoteVersionTagIsAllowed(environment.GITHUB_SHA, {}, "v2.2.0"), false);
  assert.equal(
    remoteVersionTagIsAllowed(
      "different",
      { ...environment, SERIALSLINGER_RELEASE_WORKFLOW: "1", SERIALSLINGER_RELEASE_DRY_RUN: "1" },
      "v2.2.0",
    ),
    true,
  );
  assert.equal(
    remoteVersionTagIsAllowed(
      environment.GITHUB_SHA,
      { ...environment, SERIALSLINGER_RELEASE_WORKFLOW: "1" },
      "v2.2.0",
    ),
    true,
  );
  assert.equal(
    remoteVersionTagIsAllowed(
      "different",
      { ...environment, SERIALSLINGER_RELEASE_WORKFLOW: "1" },
      "v2.2.0",
    ),
    false,
  );
});
