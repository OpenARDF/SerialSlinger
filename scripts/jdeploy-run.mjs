#!/usr/bin/env node

import { execFileSync } from "node:child_process";
import { existsSync } from "node:fs";
import { platform } from "node:os";
import { resolve } from "node:path";

function fail(message) {
  console.error(`ERROR: ${message}`);
  process.exit(1);
}

let javaHome = process.env.JAVA_HOME;
if (!javaHome && platform() === "darwin" && existsSync("/usr/libexec/java_home")) {
  javaHome = execFileSync("/usr/libexec/java_home", ["-v", "17"], { encoding: "utf8" }).trim();
}
if (!javaHome) {
  fail("Set JAVA_HOME to a full JDK 17 installation.");
}

const args = process.argv.slice(2);
if (args.length === 0) {
  fail("Pass a jDeploy command to run.");
}

const pathSeparator = platform() === "win32" ? ";" : ":";
const npx = platform() === "win32" ? "npx.cmd" : "npx";
const npxArgs = ["--no-install", "jdeploy", ...args];
const options = {
  stdio: "inherit",
  env: {
    ...process.env,
    JAVA_HOME: javaHome,
    PATH: `${resolve(javaHome, "bin")}${pathSeparator}${process.env.PATH || ""}`,
  },
};

if (platform() === "win32") {
  execFileSync("cmd.exe", ["/d", "/c", "call", npx, ...npxArgs], options);
} else {
  execFileSync(npx, npxArgs, options);
}
