#!/usr/bin/env node

import { execFileSync, spawnSync } from "node:child_process";
import { existsSync, readFileSync, rmSync } from "node:fs";
import { homedir, platform, tmpdir } from "node:os";
import { join } from "node:path";
import { pathToFileURL } from "node:url";

function fail(message) {
  console.error(`ERROR: ${message}`);
  process.exit(1);
}

function npmCommand() {
  return platform() === "win32" ? "npm.cmd" : "npm";
}

function run(command, args) {
  if (platform() === "win32" && command.endsWith(".cmd")) {
    execFileSync("cmd.exe", ["/d", "/c", "call", command, ...args], { stdio: "inherit" });
    return;
  }
  execFileSync(command, args, { stdio: "inherit" });
}

export function localInstallPath(runtimePlatform = platform(), userHome = homedir()) {
  if (runtimePlatform === "darwin") {
    return join(
      userHome,
      "Applications",
      "SerialSlinger.app",
      "Contents",
      "MacOS",
      "Client4JLauncher"
    );
  }
  if (runtimePlatform === "win32") {
    return join(userHome, ".jdeploy", "apps", "serialslinger", "SerialSlinger.exe");
  }
  if (runtimePlatform === "linux") {
    return join(userHome, ".jdeploy", "apps", "serialslinger", "serialslinger");
  }
  return null;
}

export function validateEvidence(evidencePath, expectedPackageVersion) {
  if (!existsSync(evidencePath)) {
    throw new Error(`Installed-package evidence was not created: ${evidencePath}`);
  }
  const evidence = readFileSync(evidencePath, "utf8");
  if (!evidence.includes("SerialSlinger installed package smoke")) {
    throw new Error("Installed-package evidence has an unexpected header.");
  }
  if (!evidence.includes(`packageVersion=${expectedPackageVersion}`)) {
    throw new Error(`Installed package did not report package version ${expectedPackageVersion}.`);
  }
  for (const field of ["displayVersion=", "javaVersion=", "osName="]) {
    if (!evidence.includes(field)) {
      throw new Error(`Installed-package evidence is missing ${field}`);
    }
  }
}

function runInstalledProbe(installPath, evidencePath) {
  const result = spawnSync(installPath, ["--installed-package-smoke", evidencePath], {
    encoding: "utf8",
    timeout: 30_000
  });
  if (result.error) {
    throw result.error;
  }
  if (result.status !== 0) {
    throw new Error(`Installed launcher exited ${result.status}: ${(result.stderr || "").trim()}`);
  }
}

export function parseSmokeArguments(args) {
  if (args.length === 0) {
    return { probeOnlyVersion: null };
  }
  if (args.length === 2 && args[0] === "--probe-only" && /^\d+\.\d+\.\d+$/.test(args[1])) {
    return { probeOnlyVersion: args[1] };
  }
  throw new Error("Usage: jdeploy-local-smoke.mjs [--probe-only <major.minor.patch>]");
}

function main() {
  let options;
  try {
    options = parseSmokeArguments(process.argv.slice(2));
  } catch (error) {
    fail(error.message);
  }

  if (options.probeOnlyVersion == null) {
    run(npmCommand(), ["run", "jdeploy:install-local"]);
    run(npmCommand(), ["run", "jdeploy:verify-install"]);
  }

  const installPath = localInstallPath();
  if (installPath == null) {
    console.log("SerialSlinger local jDeploy install verified; executable probe is unsupported on this platform.");
    return;
  }
  if (!existsSync(installPath)) {
    fail(`Expected local jDeploy install at ${installPath}.`);
  }

  const evidencePath = join(tmpdir(), `serialslinger-installed-smoke-${process.pid}.txt`);
  rmSync(evidencePath, { force: true });
  try {
    runInstalledProbe(installPath, evidencePath);
    const expectedPackageVersion = options.probeOnlyVersion
      ?? JSON.parse(readFileSync("package.json", "utf8")).version;
    validateEvidence(evidencePath, expectedPackageVersion);
    console.log(`SerialSlinger installed-package smoke passed for ${expectedPackageVersion}.`);
  } catch (error) {
    fail(error.message);
  } finally {
    rmSync(evidencePath, { force: true });
  }
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  main();
}
