#!/usr/bin/env node

import { execFileSync } from "node:child_process";
import { existsSync, readFileSync } from "node:fs";
import { resolve } from "node:path";
import { fileURLToPath } from "node:url";

export function validateReleaseTag(environment, packageJson, checklist) {
  if (environment.GITHUB_REF_TYPE !== "tag") {
    throw new Error("SerialSlinger publication is allowed only for a tag workflow.");
  }
  const tag = environment.GITHUB_REF_NAME || "";
  const expectedTag = `v${packageJson.version}`;
  if (tag !== expectedTag) {
    throw new Error(`Release tag ${tag || "<empty>"} does not match package version ${packageJson.version}.`);
  }
  if (checklist.release !== tag || checklist.version !== packageJson.version) {
    throw new Error(`Release checklist identity must match ${tag}.`);
  }
  if (!environment.GITHUB_SHA || checklist.sourceCommit !== environment.GITHUB_SHA) {
    throw new Error("Release checklist sourceCommit must equal the tagged GitHub commit.");
  }
  return tag;
}

export function remoteVersionTagIsAllowed(existingTag, environment, expectedTag) {
  return (
    existingTag.length === 0 ||
    (environment.GITHUB_REF_TYPE === "tag" && environment.GITHUB_REF_NAME === expectedTag)
  );
}

function main() {
  const packageJson = JSON.parse(readFileSync(resolve("package.json"), "utf8"));
  const checklistPath = resolve(`docs/release-checklist-${packageJson.version}.json`);
  if (!existsSync(checklistPath)) {
    throw new Error(`Release checklist is missing: ${checklistPath}`);
  }
  const checklist = JSON.parse(readFileSync(checklistPath, "utf8"));
  validateReleaseTag(process.env, packageJson, checklist);

  execFileSync(process.execPath, [
    resolve("scripts/check-release-checklist.mjs"),
    "--file",
    checklistPath,
    "--phase",
    "pre-tag"
  ], { stdio: "inherit" });
  execFileSync(process.execPath, [
    resolve("scripts/check-release-notes.mjs"),
    "--checklist",
    checklistPath
  ], { stdio: "inherit" });
}

if (process.argv[1] && fileURLToPath(import.meta.url) === resolve(process.argv[1])) {
  try {
    main();
  } catch (error) {
    console.error(`ERROR: ${error.message}`);
    process.exit(1);
  }
}
