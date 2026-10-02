import assert from "node:assert/strict";
import { mkdtemp, writeFile } from "node:fs/promises";
import os from "node:os";
import path from "node:path";
import test from "node:test";

import { verifyInstrumentationResults } from "./check-android-instrumentation-results.mjs";

async function fixture(summary) {
  const directory = await mkdtemp(path.join(os.tmpdir(), "serialslinger-instrumentation-"));
  await writeFile(path.join(directory, "TEST-device.xml"), `<testsuites ${summary} />`);
  return directory;
}

test("accepts a complete non-empty instrumentation report", async () => {
  const directory = await fixture('tests="4" failures="0" errors="0" skipped="0"');
  assert.deepEqual(await verifyInstrumentationResults(directory), {
    tests: 4,
    failures: 0,
    errors: 0,
    skipped: 0,
  });
});

test("rejects the zero-test false green produced by a runner crash", async () => {
  const directory = await fixture('tests="0" failures="0" errors="0" skipped="0"');
  await assert.rejects(verifyInstrumentationResults(directory), /executed zero tests/);
});

test("rejects incomplete instrumentation results", async () => {
  const directory = await fixture('tests="4" failures="1" errors="0" skipped="1"');
  await assert.rejects(verifyInstrumentationResults(directory), /was incomplete/);
});
