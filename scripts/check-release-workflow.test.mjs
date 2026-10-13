#!/usr/bin/env node

import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import test from "node:test";

const releaseWorkflow = readFileSync("docs/release-workflow.md", "utf8");
const packagingWorkflow = readFileSync("docs/packaging-and-release.md", "utf8");
const buildAndTestWorkflow = readFileSync(".github/workflows/build-and-test.yml", "utf8");
const releaseAutomation = readFileSync(".github/workflows/jdeploy-release.yml", "utf8");
const checklistTemplate = JSON.parse(readFileSync("docs/release-checklist-template.json", "utf8"));
const renovateConfig = JSON.parse(readFileSync("renovate.json", "utf8"));

function checklistDescription(id) {
  return checklistTemplate.items.find((item) => item.id === id)?.description ?? "";
}

test("release instructions use one protected main branch", () => {
  const releaseDocs = `${releaseWorkflow}\n${packagingWorkflow}`;

  // A release must never depend on weakening the branch controls it is meant to prove
  // or on manually copying commits between long-lived branches.
  assert.doesNotMatch(
    releaseDocs,
    /temporar(?:ily|y)\s+(?:suspend|relax|disable).*?(?:protect|required-pull-request|required-status-check)/isu,
  );
  assert.doesNotMatch(releaseDocs, /Development1/u);
  assert.doesNotMatch(releaseDocs, /post-release evidence topic branch/u);
  assert.doesNotMatch(releaseDocs, /git merge --ff-only/u);
  assert.match(releaseWorkflow, /focused\s+`codex\/<feature>` topic branch/u);
  assert.match(releaseWorkflow, /pull request into protected `main`/u);
  assert.deepEqual(renovateConfig.baseBranchPatterns, ["main"]);
});

test("required CI exercises the release workflow policy", () => {
  assert.match(buildAndTestWorkflow, /scripts\/check-release-workflow\.test\.mjs/u);
});

test("release checklist ends at the protected main candidate", () => {
  assert.match(checklistDescription("merged-to-main"), /pull request into protected main/u);
  assert.equal(checklistDescription("tag-pushed"), "");
  assert.equal(checklistDescription("final-checklist-audit"), "");
});

test("release automation defaults to a dry run and moves metadata last", () => {
  assert.match(releaseAutomation, /workflow_dispatch:/u);
  assert.match(releaseAutomation, /default: false/u);
  assert.match(releaseAutomation, /environment: production/u);
  assert.match(releaseAutomation, /actions\/attest@/u);
  assert.doesNotMatch(releaseAutomation, /push:\s*\n\s*tags:/u);
  assert.ok(
    releaseAutomation.indexOf("publish-github-release.sh") <
      releaseAutomation.indexOf("update-jdeploy-metadata.sh"),
  );
});

test("Play deployment verifies the identity embedded in the built bundle", () => {
  const playAutomation = readFileSync(".github/workflows/android-play-deploy.yml", "utf8");
  assert.match(playAutomation, /check-android-native-compatibility\.mjs/u);
  assert.match(playAutomation, /--expected-version/u);
  assert.match(playAutomation, /--expected-package/u);
});
