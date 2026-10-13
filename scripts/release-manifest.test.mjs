#!/usr/bin/env node

import assert from "node:assert/strict";
import { mkdirSync, mkdtempSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import test from "node:test";

import { createReleaseManifest, verifyReleaseManifest } from "./release-manifest.mjs";

test("creates and verifies deterministic asset evidence", () => {
  const directory = mkdtempSync(join(tmpdir(), "serialslinger-release-manifest-test-"));
  try {
    writeFileSync(join(directory, "SerialSlinger.Installer-mac-arm64.tgz"), "mac fixture\n");
    writeFileSync(join(directory, "serialslinger-2.2.0.tgz"), "npm fixture\n");
    const manifest = createReleaseManifest({
      directory,
      version: "2.2.0",
      tag: "v2.2.0",
      sourceCommit: "0123456789abcdef",
      workflowUrl: "https://github.com/OpenARDF/SerialSlinger/actions/runs/123",
      generatedAt: "2026-10-12T12:00:00.000Z",
    });
    assert.equal(manifest.assets.length, 2);
    assert.equal(manifest.verification.macos.codesignRoundTripVerified, true);
    assert.doesNotThrow(() => verifyReleaseManifest(directory, manifest));

    writeFileSync(join(directory, "serialslinger-2.2.0.tgz"), "changed\n");
    assert.throws(() => verifyReleaseManifest(directory, manifest), /SHA-256 mismatch/u);
  } finally {
    rmSync(directory, { recursive: true, force: true });
  }
});

test("rejects a release without a verified macOS installer", () => {
  const directory = mkdtempSync(join(tmpdir(), "serialslinger-release-manifest-empty-test-"));
  try {
    mkdirSync(join(directory, "ignored-directory"));
    writeFileSync(join(directory, "serialslinger-2.2.0.tgz"), "npm fixture\n");
    assert.throws(
      () =>
        createReleaseManifest({
          directory,
          version: "2.2.0",
          tag: "v2.2.0",
          sourceCommit: "0123456789abcdef",
          workflowUrl: "https://example.invalid/run",
          generatedAt: "2026-10-12T12:00:00.000Z",
        }),
      /macOS installer/u,
    );
  } finally {
    rmSync(directory, { recursive: true, force: true });
  }
});
