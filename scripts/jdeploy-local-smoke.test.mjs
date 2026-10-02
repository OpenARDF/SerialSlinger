#!/usr/bin/env node

import assert from "node:assert/strict";
import { mkdtempSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import test from "node:test";

import {
  localInstallPath,
  parseSmokeArguments,
  validateEvidence
} from "./jdeploy-local-smoke.mjs";

test("resolves native jDeploy launcher paths on supported platforms", () => {
  const home = join("", "test-home");
  assert.equal(
    localInstallPath("darwin", home),
    join(home, "Applications", "SerialSlinger.app", "Contents", "MacOS", "Client4JLauncher")
  );
  assert.equal(
    localInstallPath("win32", home),
    join(home, ".jdeploy", "apps", "serialslinger", "SerialSlinger.exe")
  );
  assert.equal(
    localInstallPath("linux", home),
    join(home, ".jdeploy", "apps", "serialslinger", "serialslinger")
  );
  assert.equal(localInstallPath("freebsd", home), null);
  assert.equal(
    localInstallPath("linux", home, "https://github.com/OpenARDF/SerialSlinger"),
    join(
      home,
      ".jdeploy",
      "apps",
      "07b6a0ef1f9231c5e7a3317232bb0aaa.serialslinger",
      "serialslinger"
    )
  );
  assert.equal(
    localInstallPath("win32", home, "https://github.com/OpenARDF/SerialSlinger"),
    join(
      home,
      ".jdeploy",
      "apps",
      "07b6a0ef1f9231c5e7a3317232bb0aaa.serialslinger",
      "SerialSlinger.exe"
    )
  );
});

test("validates evidence from the installed application main class", () => {
  const directory = mkdtempSync(join(tmpdir(), "serialslinger-evidence-test-"));
  const evidence = join(directory, "evidence.txt");
  try {
    writeFileSync(
      evidence,
      "SerialSlinger installed package smoke\npackageVersion=2.1.0\ndisplayVersion=2.1.0\njavaVersion=17\nosName=Test\n"
    );
    assert.doesNotThrow(() => validateEvidence(evidence, "2.1.0"));
    assert.throws(() => validateEvidence(evidence, "2.1.1"), /did not report package version/);
  } finally {
    rmSync(directory, { recursive: true, force: true });
  }
});

test("parses the release-installed probe-only mode conservatively", () => {
  assert.deepEqual(parseSmokeArguments([]), { probeOnlyVersion: null, packageSource: null });
  assert.deepEqual(parseSmokeArguments(["--probe-only", "2.0.24"]), {
    probeOnlyVersion: "2.0.24",
    packageSource: null
  });
  assert.deepEqual(
    parseSmokeArguments([
      "--probe-only",
      "2.0.24",
      "--source",
      "https://github.com/OpenARDF/SerialSlinger/"
    ]),
    {
      probeOnlyVersion: "2.0.24",
      packageSource: "https://github.com/OpenARDF/SerialSlinger"
    }
  );
  assert.throws(
    () => parseSmokeArguments(["--probe-only", "2.0.24", "--source", "http://example.test"]),
    /must use HTTPS/
  );
  assert.throws(
    () => parseSmokeArguments(["--probe-only", "2.0.24", "--source", "not-a-url"]),
    /Invalid URL/
  );
  assert.throws(() => parseSmokeArguments(["--probe-only"]), /Usage:/);
  assert.throws(() => parseSmokeArguments(["--probe-only", "v2.0.24"]), /Usage:/);
  assert.throws(() => parseSmokeArguments(["--unexpected", "2.0.24"]), /Usage:/);
});
