#!/usr/bin/env node

import { execFileSync } from "node:child_process";
import { existsSync } from "node:fs";
import { platform } from "node:os";
import { resolve } from "node:path";

function fail(message) {
  console.error(`ERROR: ${message}`);
  process.exit(1);
}

function pathSeparator() {
  return platform() === "win32" ? ";" : ":";
}

function gradleCommand() {
  return platform() === "win32" ? resolve("gradlew.bat") : resolve("gradlew");
}

function runGradle(args, options) {
  const gradle = gradleCommand();
  if (platform() === "win32") {
    execFileSync("cmd.exe", ["/d", "/c", "call", gradle, ...args], options);
    return;
  }
  execFileSync(gradle, args, options);
}

let javaHome = process.env.JAVA_HOME;
if (!javaHome && platform() === "darwin" && existsSync("/usr/libexec/java_home")) {
  javaHome = execFileSync("/usr/libexec/java_home", ["-v", "17"], { encoding: "utf8" }).trim();
}
if (!javaHome) {
  fail("Set JAVA_HOME to a full JDK 17 installation.");
}

const gradle = gradleCommand();
if (!existsSync(gradle)) {
  fail(`Gradle wrapper was not found at ${gradle}.`);
}

runGradle(["prepareDesktopJdeployBundle", "verifyDesktopJdeployBundle"], {
  stdio: "inherit",
  env: {
    ...process.env,
    JAVA_HOME: javaHome,
    PATH: `${resolve(javaHome, "bin")}${pathSeparator()}${process.env.PATH || ""}`,
  },
});
