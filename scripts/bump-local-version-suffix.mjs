import fs from "node:fs";
import path from "node:path";
import process from "node:process";

const repoRoot = process.cwd();
const buildGradlePath = path.join(repoRoot, "build.gradle.kts");
const packageJsonPath = path.join(repoRoot, "package.json");
const packageLockPath = path.join(repoRoot, "package-lock.json");

function fail(message) {
  console.error(message);
  process.exit(1);
}

function readText(filePath) {
  if (!fs.existsSync(filePath)) {
    fail(`${path.relative(repoRoot, filePath)} is missing.`);
  }
  return fs.readFileSync(filePath, "utf8");
}

function nextSuffix(current) {
  if (current.length === 0) {
    return "a";
  }
  if (!/^[a-z]+$/.test(current)) {
    fail(`serialSlingerVersionSuffix must be blank or lowercase letters, found '${current}'.`);
  }

  const chars = current.split("");
  for (let index = chars.length - 1; index >= 0; index -= 1) {
    if (chars[index] !== "z") {
      chars[index] = String.fromCharCode(chars[index].charCodeAt(0) + 1);
      return chars.join("");
    }
    chars[index] = "a";
  }
  return `a${chars.join("")}`;
}

function packageVersion(baseVersion, suffix) {
  return suffix.length === 0 ? baseVersion : `${baseVersion}-${suffix}`;
}

const buildGradleText = readText(buildGradlePath);
const baseMatch = buildGradleText.match(/val\s+serialSlingerVersion\s*=\s*"([^"]+)"/);
const suffixMatch = buildGradleText.match(/val\s+serialSlingerVersionSuffix\s*=\s*"([^"]*)"/);

if (!baseMatch) {
  fail("Could not find serialSlingerVersion in build.gradle.kts.");
}
if (!suffixMatch) {
  fail("Could not find serialSlingerVersionSuffix in build.gradle.kts.");
}

const baseVersion = baseMatch[1];
const oldSuffix = suffixMatch[1];
const newSuffix = nextSuffix(oldSuffix);
const oldPackageVersion = packageVersion(baseVersion, oldSuffix);
const newPackageVersion = packageVersion(baseVersion, newSuffix);

fs.writeFileSync(
  buildGradlePath,
  buildGradleText.replace(
    /val\s+serialSlingerVersionSuffix\s*=\s*"([^"]*)"/,
    `val serialSlingerVersionSuffix = "${newSuffix}"`,
  ),
);

const packageJson = JSON.parse(readText(packageJsonPath));
if (packageJson.version !== oldPackageVersion) {
  fail(`package.json version '${packageJson.version}' does not match expected '${oldPackageVersion}'.`);
}
packageJson.version = newPackageVersion;
fs.writeFileSync(packageJsonPath, `${JSON.stringify(packageJson, null, 2)}\n`);

const packageLock = JSON.parse(readText(packageLockPath));
if (packageLock.version !== oldPackageVersion) {
  fail(`package-lock.json version '${packageLock.version}' does not match expected '${oldPackageVersion}'.`);
}
if (packageLock.packages?.[""]?.version !== oldPackageVersion) {
  fail(`package-lock.json root package version '${packageLock.packages?.[""]?.version}' does not match expected '${oldPackageVersion}'.`);
}
packageLock.version = newPackageVersion;
packageLock.packages[""].version = newPackageVersion;
fs.writeFileSync(packageLockPath, `${JSON.stringify(packageLock, null, 2)}\n`);

console.log(`Bumped local SerialSlinger version: ${baseVersion}${oldSuffix} -> ${baseVersion}${newSuffix}`);
console.log(`Updated npm package version: ${oldPackageVersion} -> ${newPackageVersion}`);
