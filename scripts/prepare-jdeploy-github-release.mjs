#!/usr/bin/env node

import { execFileSync } from "node:child_process";
import { existsSync, readFileSync, writeFileSync } from "node:fs";
import { resolve } from "node:path";
import { fileURLToPath } from "node:url";

const githubPackageName = "serialslinger";
const requiredReleaseFiles = ["jdeploy-release-notes.md", "package-info.json", "package.json"];

export function validateReleaseContext(environment, packageJson) {
  if (environment.GITHUB_REF_TYPE !== "tag") {
    throw new Error("jDeploy GitHub release preparation is allowed only for a tag workflow.");
  }
  const tag = environment.GITHUB_REF_NAME || "";
  if (!/^[A-Za-z0-9._-]+$/.test(tag) || tag.length > 16) {
    throw new Error(`Release tag ${tag || "<empty>"} is not a supported jDeploy tag name.`);
  }
  if (tag !== `v${packageJson.version}`) {
    throw new Error(`Release tag ${tag} does not match package version ${packageJson.version}.`);
  }
  if (packageJson.name !== githubPackageName) {
    throw new Error(`GitHub jDeploy package name must be ${githubPackageName}.`);
  }
  if (!environment.GITHUB_SHA || !environment.GITHUB_REPOSITORY || !environment.GH_TOKEN) {
    throw new Error("GitHub release preparation requires GITHUB_SHA, GITHUB_REPOSITORY, and GH_TOKEN.");
  }
  return tag;
}

export function withGithubReleaseMetadata(packageJson, environment) {
  const jdeployVersion = packageJson.devDependencies?.jdeploy || packageJson.dependencies?.jdeploy;
  if (!jdeployVersion) {
    throw new Error("package.json must pin the jDeploy dependency used for release preparation.");
  }
  return {
    ...packageJson,
    jdeploy: {
      ...packageJson.jdeploy,
      jdeployVersion,
      commitHash: environment.GITHUB_SHA,
      gitTag: environment.GITHUB_REF_NAME
    }
  };
}

export function validateReleaseFiles(releaseDirectory) {
  for (const fileName of requiredReleaseFiles) {
    const filePath = resolve(releaseDirectory, fileName);
    if (!existsSync(filePath)) {
      throw new Error(`jDeploy did not produce required release file ${filePath}.`);
    }
  }
  const packageInfo = JSON.parse(readFileSync(resolve(releaseDirectory, "package-info.json"), "utf8"));
  if (!packageInfo || typeof packageInfo !== "object" || Array.isArray(packageInfo)) {
    throw new Error("jDeploy package-info.json must contain a JSON object.");
  }
}

function main() {
  const packagePath = resolve("package.json");
  const packageJson = JSON.parse(readFileSync(packagePath, "utf8"));
  validateReleaseContext(process.env, packageJson);

  writeFileSync(
    packagePath,
    `${JSON.stringify(withGithubReleaseMetadata(packageJson, process.env), null, 2)}\n`
  );
  // Reuse the repository's JDK-aware launcher so local and hosted jDeploy execution stay aligned.
  execFileSync(process.execPath, [resolve("scripts/jdeploy-run.mjs"), "github-prepare-release"], {
    env: process.env,
    stdio: "inherit"
  });
  validateReleaseFiles(resolve("jdeploy/github-release-files"));
}

if (process.argv[1] && fileURLToPath(import.meta.url) === resolve(process.argv[1])) {
  try {
    main();
  } catch (error) {
    console.error(`ERROR: ${error.message}`);
    process.exit(1);
  }
}
