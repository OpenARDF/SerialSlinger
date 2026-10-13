#!/usr/bin/env node

import assert from "node:assert/strict";
import { execFileSync } from "node:child_process";
import {
  chmodSync,
  cpSync,
  mkdirSync,
  mkdtempSync,
  readFileSync,
  rmSync,
  writeFileSync,
} from "node:fs";
import { tmpdir } from "node:os";
import { join, resolve } from "node:path";
import test from "node:test";

const repoRoot = resolve(".");
const prepareScript = resolve("scripts/prepare-jdeploy-macos-installers.sh");

function makeArchive(root) {
  const releaseDirectory = join(root, "release-files");
  const wrapperDirectory = join(root, "wrapper");
  const appDirectory = join(wrapperDirectory, "SerialSlinger Installer.app");
  const contentsDirectory = join(appDirectory, "Contents");
  const macosDirectory = join(contentsDirectory, "MacOS");
  const resourcesDirectory = join(contentsDirectory, "Resources");
  const archivePath = join(releaseDirectory, "SerialSlinger.Installer-mac-arm64.tgz");
  mkdirSync(releaseDirectory, { recursive: true });
  mkdirSync(macosDirectory, { recursive: true });
  mkdirSync(resourcesDirectory, { recursive: true });
  writeFileSync(join(macosDirectory, "Client4JLauncher"), "#!/bin/sh\nexit 0\n");
  chmodSync(join(macosDirectory, "Client4JLauncher"), 0o755);
  writeFileSync(join(contentsDirectory, "app.xml"), "<app icon='data:image/png;base64,b2xk'/>\n");
  writeFileSync(
    join(contentsDirectory, "Info.plist"),
    `<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0"><dict>
<key>CFBundleIdentifier</key><string>org.openardf.serialslinger.fixture</string>
<key>CFBundleExecutable</key><string>Client4JLauncher</string>
<key>CFBundlePackageType</key><string>APPL</string>
</dict></plist>
`,
  );
  execFileSync("tar", ["-czf", archivePath, "wrapper"], { cwd: root });
  return { archivePath, releaseDirectory };
}

test(
  "customizes, signs, archives, and reprocesses a macOS installer fixture",
  { skip: process.platform !== "darwin" },
  () => {
    const root = mkdtempSync(join(tmpdir(), "serialslinger-macos-installer-test-"));
    try {
      const { archivePath, releaseDirectory } = makeArchive(root);
      for (let attempt = 0; attempt < 2; attempt += 1) {
        execFileSync("bash", [prepareScript, releaseDirectory], { cwd: repoRoot, stdio: "pipe" });
      }

      const extracted = join(root, "extracted");
      mkdirSync(extracted);
      execFileSync("tar", ["-xzf", archivePath, "-C", extracted]);
      const appDirectory = join(extracted, "wrapper", "SerialSlinger Installer.app");
      assert.equal(
        readFileSync(join(appDirectory, "Contents/Info.plist"), "utf8").includes("SerialSlinger"),
        true,
      );
      assert.match(
        readFileSync(join(appDirectory, "Contents/app.xml"), "utf8"),
        /icon='data:image\/png;base64,[A-Za-z0-9+/=]+'/,
      );
      assert.doesNotThrow(() =>
        execFileSync("codesign", ["--verify", "--deep", "--strict", "--verbose=2", appDirectory], {
          stdio: "pipe",
        }),
      );
      assert.deepEqual(
        readFileSync(join(appDirectory, "Contents/Resources/icon.icns")),
        readFileSync(join(repoRoot, "shared/packaging/icons/SerialSlinger.icns")),
      );
      assert.deepEqual(readFileSync(join(releaseDirectory, "icon.png")), readFileSync(join(repoRoot, "icon.png")));
    } finally {
      rmSync(root, { recursive: true, force: true });
    }
  },
);
