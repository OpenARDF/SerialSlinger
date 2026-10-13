#!/usr/bin/env node

import { execFileSync } from "node:child_process";
import { existsSync, readFileSync } from "node:fs";
import { resolve } from "node:path";
import { fileURLToPath } from "node:url";

export function validateReleaseRequest(environment, packageJson, checklist) {
  if (environment.GITHUB_EVENT_NAME !== "workflow_dispatch") {
    throw new Error("SerialSlinger publication must be started with the manual release workflow.");
  }
  if (environment.GITHUB_REF !== "refs/heads/main") {
    throw new Error("SerialSlinger releases must run from the protected main branch.");
  }
  const version = environment.SERIALSLINGER_RELEASE_VERSION || "";
  const tag = environment.SERIALSLINGER_RELEASE_TAG || "";
  if (version !== packageJson.version || tag !== `v${packageJson.version}`) {
    throw new Error(`Requested release ${tag || "<empty>"} does not match package version ${packageJson.version}.`);
  }
  if (checklist.release !== tag || checklist.version !== packageJson.version) {
    throw new Error(`Release checklist identity must match ${tag}.`);
  }
  if (!environment.GITHUB_SHA) {
    throw new Error("The release workflow must identify its exact source commit.");
  }
  return { tag, sourceCommit: environment.GITHUB_SHA, version };
}

export function remoteVersionTagIsAllowed(existingTagCommit, environment, expectedTag) {
  return (
    existingTagCommit.length === 0 ||
    (environment.SERIALSLINGER_RELEASE_WORKFLOW === "1" && environment.SERIALSLINGER_RELEASE_DRY_RUN === "1") ||
    (environment.SERIALSLINGER_RELEASE_WORKFLOW === "1" &&
      environment.SERIALSLINGER_RELEASE_TAG === expectedTag &&
      existingTagCommit === environment.GITHUB_SHA)
  );
}

function main() {
  const packageJson = JSON.parse(readFileSync(resolve("package.json"), "utf8"));
  const checklistPath = resolve(`docs/release-checklist-${packageJson.version}.json`);
  if (!existsSync(checklistPath)) {
    throw new Error(`Release checklist is missing: ${checklistPath}`);
  }
  const checklist = JSON.parse(readFileSync(checklistPath, "utf8"));
  validateReleaseRequest(process.env, packageJson, checklist);

  execFileSync(
    process.execPath,
    [resolve("scripts/check-release-checklist.mjs"), "--file", checklistPath, "--phase", "pre-tag"],
    { stdio: "inherit" },
  );
  execFileSync(process.execPath, [resolve("scripts/check-release-notes.mjs"), "--checklist", checklistPath], {
    stdio: "inherit",
  });
}

if (process.argv[1] && fileURLToPath(import.meta.url) === resolve(process.argv[1])) {
  try {
    main();
  } catch (error) {
    console.error(`ERROR: ${error.message}`);
    process.exit(1);
  }
}
