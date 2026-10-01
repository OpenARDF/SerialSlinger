import fs from "node:fs";
import path from "node:path";
import process from "node:process";

const repoRoot = process.cwd();
const packageJsonPath = path.join(repoRoot, "package.json");
const iconPath = path.join(repoRoot, "icon.png");
const jarPath = path.join(repoRoot, "shared", "build", "jdeploy", "SerialSlinger-jdeploy.jar");
const allowPublish = process.env.SERIALSLINGER_ALLOW_JDEPLOY_PUBLISH === "1";

function fail(message) {
  console.error(message);
  process.exit(1);
}

if (!fs.existsSync(packageJsonPath)) {
  fail("package.json is missing. Recreate the jDeploy scaffold before publishing.");
}

const packageJson = JSON.parse(fs.readFileSync(packageJsonPath, "utf8"));
const requiredLauncherDependencies = ["node-fetch", "shelljs", "tar", "yauzl"];
const bundledDependencies = packageJson.bundledDependencies || packageJson.bundleDependencies || [];

for (const dependencyName of requiredLauncherDependencies) {
  if (!packageJson.dependencies?.[dependencyName] || !bundledDependencies.includes(dependencyName)) {
    fail(`Launcher dependency '${dependencyName}' must be declared and bundled before publishing.`);
  }
}

if (!fs.existsSync(iconPath)) {
  fail("icon.png is missing. Restore the baseline jDeploy icon before publishing.");
}

if (!fs.existsSync(jarPath)) {
  fail(
    "shared/build/jdeploy/SerialSlinger-jdeploy.jar is missing. Run ./gradlew prepareDesktopJdeployBundle verifyDesktopJdeployBundle before publishing.",
  );
}

if (!allowPublish) {
  fail(
    [
      "Stop before the first real jDeploy publish.",
      `Confirm that the public package name should be '${packageJson.name}'.`,
      "Confirm that the generated jDeploy jar and icon are the artifacts you want to ship.",
      "",
      "When you are ready to intentionally publish, rerun with SERIALSLINGER_ALLOW_JDEPLOY_PUBLISH=1.",
    ].join("\n"),
  );
}

console.log(`Proceeding with jDeploy publish for package '${packageJson.name}'.`);
