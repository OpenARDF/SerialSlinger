#!/usr/bin/env node

import assert from "node:assert/strict";
import { mkdtempSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { spawnSync } from "node:child_process";
import test from "node:test";

function runChecklist(file, phase) {
  return spawnSync(process.execPath, ["scripts/check-release-checklist.mjs", "--file", file, "--phase", phase], {
    cwd: process.cwd(),
    encoding: "utf8",
  });
}

test("hardened template contains every required release gate", () => {
  const result = runChecklist("docs/release-checklist-template.json", "template");
  assert.equal(result.status, 0, result.stderr);
});

test("schema version 2 rejects a missing hardened gate", () => {
  const directory = mkdtempSync(join(tmpdir(), "serialslinger-checklist-"));
  try {
    const checklist = JSON.parse(readFileSync("docs/release-checklist-template.json", "utf8"));
    checklist.items = checklist.items.filter((item) => item.id !== "android-signing");
    const file = join(directory, "checklist.json");
    writeFileSync(file, `${JSON.stringify(checklist, null, 2)}\n`);

    const result = runChecklist(file, "template");
    assert.notEqual(result.status, 0);
    assert.match(result.stderr, /Missing checklist item: android-signing/);
  } finally {
    rmSync(directory, { recursive: true, force: true });
  }
});

test("historical checklists remain valid under their original schema", () => {
  const result = runChecklist("docs/release-checklist-2.0.23.json", "final");
  assert.equal(result.status, 0, result.stderr);
});

test("future checklists cannot bypass hardened gates by removing schemaVersion", () => {
  const directory = mkdtempSync(join(tmpdir(), "serialslinger-checklist-"));
  try {
    const checklist = JSON.parse(readFileSync("docs/release-checklist-2.0.23.json", "utf8"));
    checklist.version = "2.0.24";
    checklist.release = "v2.0.24";
    const file = join(directory, "checklist.json");
    writeFileSync(file, `${JSON.stringify(checklist, null, 2)}\n`);

    const result = runChecklist(file, "pre-tag");
    assert.notEqual(result.status, 0);
    assert.match(result.stderr, /schemaVersion 2 is required/);
    assert.match(result.stderr, /Missing checklist item: android-signing/);
  } finally {
    rmSync(directory, { recursive: true, force: true });
  }
});
