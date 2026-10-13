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

test("schema version 3 rejects a missing hardened gate", () => {
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

test("automated release evidence removes post-publication checklist commits", () => {
  const checklist = JSON.parse(readFileSync("docs/release-checklist-template.json", "utf8"));
  const ids = checklist.items.map((item) => item.id);
  assert.equal(checklist.schemaVersion, 3);
  assert.equal(ids.includes("tag-pushed"), false);
  assert.equal(ids.includes("workflow-published"), false);
  assert.equal(ids.includes("release-verified"), false);
  assert.equal(ids.includes("final-checklist-audit"), false);
});

test("schema 2 checklists remain required through 2.1.0", () => {
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

test("future checklists require automated evidence schema 3", () => {
  const directory = mkdtempSync(join(tmpdir(), "serialslinger-checklist-"));
  try {
    const checklist = JSON.parse(readFileSync("docs/release-checklist-2.1.0.json", "utf8"));
    checklist.version = "2.1.1";
    checklist.release = "v2.1.1";
    const file = join(directory, "checklist.json");
    writeFileSync(file, `${JSON.stringify(checklist, null, 2)}\n`);

    const result = runChecklist(file, "pre-tag");
    assert.notEqual(result.status, 0);
    assert.match(result.stderr, /schemaVersion 3 is required/u);
  } finally {
    rmSync(directory, { recursive: true, force: true });
  }
});
