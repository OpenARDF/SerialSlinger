import fs from "node:fs";
import path from "node:path";
import process from "node:process";
import { execFileSync } from "node:child_process";

const repoRoot = process.cwd();
const packageJsonPath = path.join(repoRoot, "package.json");
const packageLockPath = path.join(repoRoot, "package-lock.json");
const buildGradlePath = path.join(repoRoot, "build.gradle.kts");
const workflowPath = path.join(repoRoot, ".github", "workflows", "jdeploy-release.yml");

function fail(message) {
  console.error(message);
  process.exit(1);
}

function readFile(filePath, missingMessage) {
  if (!fs.existsSync(filePath)) {
    fail(missingMessage);
  }

  return fs.readFileSync(filePath, "utf8");
}

function extractGradleVersion(buildGradleText) {
  const match = buildGradleText.match(/serialSlingerVersion\s*=\s*"([^"]+)"/);
  if (!match) {
    fail("Could not find serialSlingerVersion in build.gradle.kts.");
  }
  return match[1];
}

function ensure(condition, message) {
  if (!condition) {
    fail(message);
  }
}

const packageJson = JSON.parse(readFile(packageJsonPath, "package.json is missing."));
const packageLock = JSON.parse(readFile(packageLockPath, "package-lock.json is missing."));
const buildGradleText = readFile(buildGradlePath, "build.gradle.kts is missing.");
const workflowText = readFile(workflowPath, "The jDeploy release workflow is missing.");

const gradleVersion = extractGradleVersion(buildGradleText);
const expectedTag = `v${packageJson.version}`;

ensure(packageJson.name === "serialslinger", `Expected package.json name to be 'serialslinger', found '${packageJson.name}'.`);
ensure(/^[a-z0-9-]+$/.test(packageJson.name), "package.json name must stay lowercase and npm-safe.");
ensure(packageJson.version === gradleVersion, `package.json version '${packageJson.version}' does not match build.gradle.kts version '${gradleVersion}'.`);
ensure(packageLock.version === packageJson.version, `package-lock.json version '${packageLock.version}' does not match package.json version '${packageJson.version}'.`);
ensure(packageLock.packages?.[""]?.version === packageJson.version, "package-lock.json root package version does not match package.json.");
ensure(packageJson.devDependencies?.jdeploy, "package.json is missing the local jdeploy devDependency.");
ensure(workflowText.includes('tags:\n      - "v*"'), "The jDeploy release workflow is not configured for v* tags.");
ensure(workflowText.includes("deploy_target: github"), "The jDeploy release workflow is not pinned to the GitHub release target.");
ensure(workflowText.includes(`jdeploy_version: "${packageJson.devDependencies.jdeploy}"`), "The jDeploy workflow version does not match package.json's jdeploy devDependency.");

const existingTag = execFileSync("git", ["ls-remote", "--tags", "origin", `refs/tags/${expectedTag}`], {
  cwd: repoRoot,
  encoding: "utf8",
}).trim();

ensure(existingTag.length === 0, `Git tag '${expectedTag}' already exists on origin.`);

console.log(
  [
    "jDeploy release preflight passed.",
    `Package: ${packageJson.name}@${packageJson.version}`,
    `Gradle version: ${gradleVersion}`,
    `Target tag: ${expectedTag}`,
    "Workflow target: GitHub releases",
  ].join("\n"),
);
