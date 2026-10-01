import fs from "node:fs";
import path from "node:path";
import process from "node:process";
import { execFileSync } from "node:child_process";
import { platform } from "node:os";
import { remoteVersionTagIsAllowed } from "./check-release-tag.mjs";

const repoRoot = process.cwd();
const packageJsonPath = path.join(repoRoot, "package.json");
const packageLockPath = path.join(repoRoot, "package-lock.json");
const buildGradlePath = path.join(repoRoot, "build.gradle.kts");
const workflowPath = path.join(repoRoot, ".github", "workflows", "jdeploy-release.yml");
const buildWorkflowPath = path.join(repoRoot, ".github", "workflows", "build-and-test.yml");
const linuxSmokeWorkflowPath = path.join(repoRoot, ".github", "workflows", "linux-desktop-smoke.yml");
const windowsSmokeWorkflowPath = path.join(repoRoot, ".github", "workflows", "windows-desktop-smoke.yml");
const dependencyTodoPath = path.join(repoRoot, "docs", "dependency-update-todo.md");

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

function extractGradleVersions(buildGradleText) {
  const baseMatch = buildGradleText.match(/serialSlingerVersion\s*=\s*"([^"]+)"/);
  if (!baseMatch) {
    fail("Could not find serialSlingerVersion in build.gradle.kts.");
  }
  const suffixMatch = buildGradleText.match(/serialSlingerVersionSuffix\s*=\s*"([^"]*)"/);
  if (!suffixMatch) {
    fail("Could not find serialSlingerVersionSuffix in build.gradle.kts.");
  }
  const baseVersion = baseMatch[1];
  const displayVersion = baseVersion + suffixMatch[1];
  const packageVersion = suffixMatch[1].length === 0 ? baseVersion : `${baseVersion}-${suffixMatch[1]}`;
  return { baseVersion, displayVersion, packageVersion };
}

function ensure(condition, message) {
  if (!condition) {
    fail(message);
  }
}

function pathSeparator() {
  return platform() === "win32" ? ";" : ":";
}

function gradleCommand() {
  return platform() === "win32" ? path.join(repoRoot, "gradlew.bat") : path.join(repoRoot, "gradlew");
}

function runGradle(args) {
  let javaHome = process.env.JAVA_HOME;
  if (!javaHome && platform() === "darwin" && fs.existsSync("/usr/libexec/java_home")) {
    javaHome = execFileSync("/usr/libexec/java_home", ["-v", "17"], { encoding: "utf8" }).trim();
  }
  ensure(Boolean(javaHome), "Set JAVA_HOME to a full JDK 17 installation.");

  const gradle = gradleCommand();
  ensure(fs.existsSync(gradle), `Gradle wrapper was not found at ${gradle}.`);

  const options = {
    cwd: repoRoot,
    stdio: "inherit",
    env: {
      ...process.env,
      JAVA_HOME: javaHome,
      PATH: `${path.join(javaHome, "bin")}${pathSeparator()}${process.env.PATH || ""}`,
    },
  };

  if (platform() === "win32") {
    execFileSync("cmd.exe", ["/d", "/c", "call", gradle, ...args], options);
    return;
  }
  execFileSync(gradle, args, options);
}

const packageJson = JSON.parse(readFile(packageJsonPath, "package.json is missing."));
const packageLock = JSON.parse(readFile(packageLockPath, "package-lock.json is missing."));
const buildGradleText = readFile(buildGradlePath, "build.gradle.kts is missing.");
const workflowText = readFile(workflowPath, "The jDeploy release workflow is missing.");
const buildWorkflowText = readFile(buildWorkflowPath, "The build-and-test workflow is missing.");
const linuxSmokeWorkflowText = readFile(linuxSmokeWorkflowPath, "The Linux installed-package workflow is missing.");
const windowsSmokeWorkflowText = readFile(windowsSmokeWorkflowPath, "The Windows installed-package workflow is missing.");
const dependencyTodoText = readFile(dependencyTodoPath, "The dependency warning waiver is missing.");

const gradleVersions = extractGradleVersions(buildGradleText);
const expectedTag = `v${packageJson.version}`;

ensure(packageJson.name === "serialslinger", `Expected package.json name to be 'serialslinger', found '${packageJson.name}'.`);
ensure(/^[a-z0-9-]+$/.test(packageJson.name), "package.json name must stay lowercase and npm-safe.");
ensure(
  packageJson.version === gradleVersions.packageVersion,
  `package.json version '${packageJson.version}' does not match build.gradle.kts package version '${gradleVersions.packageVersion}'.`,
);
ensure(packageLock.version === packageJson.version, `package-lock.json version '${packageLock.version}' does not match package.json version '${packageJson.version}'.`);
ensure(packageLock.packages?.[""]?.version === packageJson.version, "package-lock.json root package version does not match package.json.");
ensure(packageJson.devDependencies?.jdeploy, "package.json is missing the local jdeploy devDependency.");
ensure(packageJson.scripts?.["jdeploy:local-smoke"], "package.json is missing the installed-package smoke command.");
const requiredLauncherDependencies = ["node-fetch", "shelljs", "tar", "yauzl"];
const bundledDependencies = packageJson.bundledDependencies || packageJson.bundleDependencies || [];
for (const dependencyName of requiredLauncherDependencies) {
  ensure(packageJson.dependencies?.[dependencyName], `package.json is missing launcher dependency ${dependencyName}.`);
  ensure(bundledDependencies.includes(dependencyName), `package.json must bundle launcher dependency ${dependencyName}.`);
}
ensure(packageJson.overrides?.["brace-expansion"] === "1.1.21", "The patched brace-expansion override must remain at 1.1.21.");
ensure(dependencyTodoText.includes("jDeploy 6.1.7"), "The jDeploy warning waiver must name the pinned tool version.");
ensure(dependencyTodoText.includes("glob"), "The jDeploy warning waiver must cover the transitive glob notice.");
ensure(dependencyTodoText.includes("inflight"), "The jDeploy warning waiver must cover the transitive inflight notice.");
ensure(workflowText.includes('tags:\n      - "v*"'), "The jDeploy release workflow is not configured for v* tags.");
ensure(workflowText.includes("runs-on: macos-26"), "The release workflow must use the pinned macos-26 runner.");
ensure(workflowText.includes("fetch-depth: 2"), "The release workflow must fetch the tagged checklist commit's parent.");
ensure(workflowText.includes('node-version: "24"'), "The release workflow must use Node 24.");
ensure(workflowText.includes("check-release-tag.mjs"), "The release workflow must validate the tag and checklist before publication.");
ensure(workflowText.includes(":androidApp:lintRelease"), "The release workflow must run Android release lint.");
ensure(workflowText.includes(":androidApp:bundleRelease"), "The release workflow must build the Android release bundle.");
ensure(workflowText.includes("jdeploy:release-preflight"), "The release workflow must run the jDeploy preflight.");
ensure(workflowText.includes("prepare-jdeploy-github-release.mjs"), "The release workflow must use the local jDeploy release preparer.");
ensure(workflowText.includes("publish-jdeploy-github-release.sh"), "The release workflow must use the guarded local publisher.");
ensure(!workflowText.includes("shannah/jdeploy@"), "The release workflow must not use the mutable jDeploy action.");
ensure(!workflowText.includes("@master"), "The release workflow must not use actions from a moving master branch.");
ensure(workflowText.includes("Repair macOS jDeploy branding"), "The jDeploy workflow must repair macOS installer branding.");
ensure(workflowText.includes("docs/release-notes/"), "The jDeploy workflow must use checked release notes when present.");
ensure(workflowText.includes("--notes-file"), "The jDeploy workflow must publish checked release notes with gh release edit --notes-file when present.");

ensure(buildWorkflowText.includes(":shared:desktopTest"), "The build workflow must run desktop tests.");
ensure(buildWorkflowText.includes(":shared:testAndroidHostTest"), "The build workflow must run Android host tests.");
ensure(buildWorkflowText.includes(":androidApp:testDebugUnitTest"), "The build workflow must run Android application tests.");
ensure(buildWorkflowText.includes(":androidApp:lintRelease"), "The build workflow must run Android release lint.");
ensure(buildWorkflowText.includes(":androidApp:bundleRelease"), "The build workflow must build the Android release bundle.");
ensure(buildWorkflowText.includes("node --test"), "The build workflow must run release-script tests.");

ensure(linuxSmokeWorkflowText.includes("runs-on: ubuntu-24.04"), "The Linux smoke must use the pinned Ubuntu 24.04 runner.");
ensure(linuxSmokeWorkflowText.includes("init.defaultBranch main"), "The Linux smoke must avoid checkout initialization warnings.");
ensure(linuxSmokeWorkflowText.includes("xvfb-run"), "The Linux smoke must provide an AWT display.");
ensure(linuxSmokeWorkflowText.includes("npm run jdeploy:local-smoke"), "The Linux workflow must run the installed-package smoke.");
ensure(windowsSmokeWorkflowText.includes("runs-on: windows-2025"), "The Windows smoke must use the pinned Windows 2025 runner.");
ensure(windowsSmokeWorkflowText.includes("npm run jdeploy:local-smoke"), "The Windows workflow must run the installed-package smoke.");
ensure(windowsSmokeWorkflowText.includes(".\\gradlew.bat --stop"), "The Windows smoke must stop Gradle before cache save.");
ensure(
  fs.existsSync(path.join(repoRoot, "scripts", "repair-jdeploy-github-release-macos.sh")),
  "The macOS jDeploy installer branding repair script is missing.",
);
ensure(
  fs.existsSync(path.join(repoRoot, "scripts", "check-release-notes.mjs")),
  "The release-notes validation script is missing.",
);
for (const scriptName of [
  "check-release-tag.mjs",
  "jdeploy-local-smoke.mjs",
  "prepare-jdeploy-github-release.mjs",
  "publish-jdeploy-github-release.sh",
]) {
  ensure(fs.existsSync(path.join(repoRoot, "scripts", scriptName)), `Required release script is missing: ${scriptName}`);
}
const macosRepairScript = readFile(
  path.join(repoRoot, "scripts", "repair-jdeploy-github-release-macos.sh"),
  "The macOS jDeploy installer branding repair script is missing.",
);
ensure(macosRepairScript.includes("shared/packaging/icons/SerialSlinger.icns"), "The macOS installer repair script must use SerialSlinger.icns.");
ensure(macosRepairScript.includes("app.xml"), "The macOS installer repair script must patch installer app.xml icon metadata.");

const existingTag = execFileSync("git", ["ls-remote", "--tags", "origin", `refs/tags/${expectedTag}`], {
  cwd: repoRoot,
  encoding: "utf8",
}).trim();

// A local pre-tag run must still reject a published version. The tag workflow is the one
// intentional exception because GitHub starts it only after that exact tag reaches origin.
ensure(
  remoteVersionTagIsAllowed(existingTag, process.env, expectedTag),
  `Git tag '${expectedTag}' already exists on origin.`,
);

for (const testScript of [
  "scripts/check-release-tag.test.mjs",
  "scripts/jdeploy-local-smoke.test.mjs",
  "scripts/prepare-jdeploy-github-release.test.mjs",
  "scripts/publish-jdeploy-github-release.test.mjs",
]) {
  execFileSync(process.execPath, ["--test", testScript], { cwd: repoRoot, stdio: "inherit" });
}

runGradle(["prepareDesktopJdeployBundle", "verifyDesktopJdeployBundle"]);

console.log(
  [
    "jDeploy release preflight passed.",
    `Package: ${packageJson.name}@${packageJson.version}`,
    `Gradle base version: ${gradleVersions.baseVersion}`,
    `Gradle display version: ${gradleVersions.displayVersion}`,
    `Gradle package version: ${gradleVersions.packageVersion}`,
    `Target tag: ${expectedTag}`,
    "Workflow target: GitHub releases",
    "Desktop jDeploy bundle: prepared and verified",
  ].join("\n"),
);
