#!/usr/bin/env node

import { execFileSync } from "node:child_process";
import { existsSync } from "node:fs";
import { join } from "node:path";

const packageName = "com.SerialSlinger.openardf";
const activityName = `${packageName}/${packageName}.MainActivity`;
const apkPath = process.argv[2] || "androidApp/build/outputs/apk/debug/androidApp-debug.apk";

function fail(message) {
  console.error(`ERROR: ${message}`);
  process.exit(1);
}

function adbPath() {
  const sdkRoot = process.env.ANDROID_SDK_ROOT || process.env.ANDROID_HOME;
  if (sdkRoot) {
    const candidate = join(sdkRoot, "platform-tools", process.platform === "win32" ? "adb.exe" : "adb");
    if (existsSync(candidate)) return candidate;
  }
  return "adb";
}

function adb(args, options = {}) {
  const output = execFileSync(adbPath(), args, { encoding: "utf8", ...options });
  return typeof output === "string" ? output.trim() : "";
}

function adbResult(args) {
  try {
    return { ok: true, output: adb(args) };
  } catch (error) {
    const stdout = typeof error.stdout === "string" ? error.stdout.trim() : "";
    const stderr = typeof error.stderr === "string" ? error.stderr.trim() : "";
    return { ok: false, output: [stdout, stderr].filter(Boolean).join("\n") };
  }
}

function failureDiagnostics() {
  // Preserve both Java crashes and low-level process-exit reasons; either can end the app before pidof observes it.
  const crashLog = adbResult(["logcat", "-b", "crash", "-d"]);
  const exitInfo = adbResult(["shell", "dumpsys", "activity", "exit-info", packageName]);
  const systemLog = adbResult(["logcat", "-d", "-v", "brief", "AndroidRuntime:E", "ActivityManager:I", "*:S"]);
  return [
    "Crash buffer:",
    crashLog.output || "(empty)",
    "Recent process exit information:",
    exitInfo.output || "(unavailable)",
    "Relevant system log:",
    systemLog.output || "(empty)",
  ].join("\n");
}

function sleep(milliseconds) {
  return new Promise(resolve => setTimeout(resolve, milliseconds));
}

if (!existsSync(apkPath)) fail(`Debug APK does not exist: ${apkPath}`);

const pageSize = adb(["shell", "getconf", "PAGE_SIZE"]);
if (pageSize !== "16384") fail(`Emulator page size is ${pageSize}; expected 16384`);

adb(["install", "-r", apkPath], { stdio: "inherit" });
adb(["shell", "am", "force-stop", packageName]);
adb(["logcat", "-b", "crash", "-c"]);
const launch = adb(["shell", "am", "start", "-W", "-n", activityName]);
if (!/^Status: ok$/m.test(launch)) fail(`Activity launch did not report success:\n${launch}`);

await sleep(5000);
const processResult = adbResult(["shell", "pidof", packageName]);
if (!processResult.ok || !/^\d+(?:\s+\d+)*$/.test(processResult.output)) {
  fail(`SerialSlinger process is not running after launch.\n${failureDiagnostics()}`);
}

const crashLog = adb(["logcat", "-b", "crash", "-d"]);
if (crashLog.includes(`Process: ${packageName}`) || crashLog.includes(packageName)) {
  fail(`SerialSlinger produced a crash-buffer entry:\n${crashLog}`);
}

console.log(`PASS: SerialSlinger launched and remained running on a ${pageSize}-byte page-size emulator.`);
adb(["shell", "am", "force-stop", packageName]);
