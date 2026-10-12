#!/usr/bin/env node

import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import test from "node:test";

const releaseWorkflow = readFileSync("docs/release-workflow.md", "utf8");
const packagingWorkflow = readFileSync("docs/packaging-and-release.md", "utf8");
const buildAndTestWorkflow = readFileSync(".github/workflows/build-and-test.yml", "utf8");
const checklistTemplate = JSON.parse(readFileSync("docs/release-checklist-template.json", "utf8"));

function checklistDescription(id) {
  return checklistTemplate.items.find((item) => item.id === id)?.description ?? "";
}

test("release instructions preserve protected Development1 history", () => {
  const releaseDocs = `${releaseWorkflow}\n${packagingWorkflow}`;

  // A release must never depend on weakening the branch controls it is meant to prove.
  assert.doesNotMatch(
    releaseDocs,
    /temporar(?:ily|y)\s+(?:suspend|relax|disable).*?(?:protect|required-pull-request|required-status-check)/isu,
  );
  assert.doesNotMatch(releaseDocs, /direct-push exception.*Development1/isu);
  assert.match(releaseWorkflow, /Keep `Development1` protections enabled throughout a release\./u);
  assert.match(releaseWorkflow, /Do not commit release evidence directly on `main`\./u);
  assert.match(releaseWorkflow, /post-release evidence topic branch from `Development1`/u);
  assert.match(releaseWorkflow, /completed tag push under `tag-pushed`/u);
  assert.match(releaseWorkflow, /git merge --ff-only Development1/u);
});

test("required CI exercises the release workflow policy", () => {
  assert.match(buildAndTestWorkflow, /scripts\/check-release-workflow\.test\.mjs/u);
});

test("release checklist requires protected pull-request evidence", () => {
  assert.match(checklistDescription("merged-to-main"), /pull request into protected Development1/u);
  assert.match(checklistDescription("tag-pushed"), /checklist-only tag-evidence pull request into protected Development1/u);
  assert.match(checklistDescription("final-checklist-audit"), /protected pull request into Development1/u);
});
