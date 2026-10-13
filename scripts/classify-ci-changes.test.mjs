#!/usr/bin/env node

import assert from "node:assert/strict";
import test from "node:test";

import { requiresHeavyValidation } from "./classify-ci-changes.mjs";

test("uses lightweight validation only for immutable release evidence", () => {
  assert.equal(
    requiresHeavyValidation([
      "docs/release-checklist-2.1.0.json",
      "docs/release-notes/v2.1.0.md",
      "docs/release-verification-2.1.0-arm64.md",
    ]),
    false,
  );
});

test("uses full validation for source, workflow, script, and general documentation changes", () => {
  for (const path of [
    "shared/src/commonMain/kotlin/Example.kt",
    ".github/workflows/jdeploy-release.yml",
    "scripts/check-release-request.mjs",
    "docs/release-workflow.md",
  ]) {
    assert.equal(requiresHeavyValidation([path]), true, path);
  }
  assert.equal(requiresHeavyValidation([]), true);
});
