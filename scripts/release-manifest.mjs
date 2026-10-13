#!/usr/bin/env node

import { createHash } from "node:crypto";
import { existsSync, readFileSync, readdirSync, statSync, writeFileSync } from "node:fs";
import { basename, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";

export const manifestFileName = "release-manifest.json";

function sha256(filePath) {
  return createHash("sha256").update(readFileSync(filePath)).digest("hex");
}

function releaseFiles(directory) {
  return readdirSync(directory, { withFileTypes: true })
    .filter((entry) => entry.isFile() && entry.name !== manifestFileName)
    .map((entry) => entry.name)
    .sort();
}

export function createReleaseManifest({ directory, version, tag, sourceCommit, workflowUrl, generatedAt }) {
  const files = releaseFiles(directory);
  if (files.length === 0) {
    throw new Error(`No release assets were found in ${directory}.`);
  }
  const macosInstallers = files.filter((name) => /^SerialSlinger\.Installer-mac-.*\.tgz$/u.test(name));
  if (macosInstallers.length === 0) {
    throw new Error("The release assets do not include a macOS installer archive.");
  }
  const assets = files.map((name) => {
    const filePath = join(directory, name);
    return { name, size: statSync(filePath).size, sha256: sha256(filePath) };
  });
  return {
    schemaVersion: 1,
    product: "SerialSlinger",
    version,
    tag,
    sourceCommit,
    workflowUrl,
    generatedAt,
    verification: {
      finalAssetsPreparedLocally: true,
      macos: {
        installerCount: macosInstallers.length,
        signature: "ad-hoc",
        codesignRoundTripVerified: true,
      },
    },
    assets,
  };
}

export function verifyReleaseManifest(directory, manifest) {
  const failures = [];
  const expectedNames = manifest.assets.map((asset) => asset.name).sort();
  const actualNames = releaseFiles(directory);
  if (JSON.stringify(actualNames) !== JSON.stringify(expectedNames)) {
    failures.push(`asset set mismatch: expected ${expectedNames.join(", ")}; found ${actualNames.join(", ")}`);
  }
  for (const asset of manifest.assets) {
    const filePath = join(directory, asset.name);
    if (!existsSync(filePath)) {
      failures.push(`${asset.name}: missing`);
      continue;
    }
    const size = statSync(filePath).size;
    const digest = sha256(filePath);
    if (size !== asset.size) {
      failures.push(`${asset.name}: expected ${asset.size} bytes, found ${size}`);
    }
    if (digest !== asset.sha256) {
      failures.push(`${asset.name}: SHA-256 mismatch`);
    }
  }
  if (failures.length > 0) {
    throw new Error(`Release manifest verification failed:\n- ${failures.join("\n- ")}`);
  }
}

function parseArguments(argv) {
  const [command, ...rest] = argv;
  const args = { command };
  for (let index = 0; index < rest.length; index += 2) {
    const name = rest[index];
    const value = rest[index + 1];
    if (!name?.startsWith("--") || value === undefined) {
      throw new Error("Expected named arguments in --name value pairs.");
    }
    args[name.slice(2)] = value;
  }
  return args;
}

function main() {
  const args = parseArguments(process.argv.slice(2));
  const directory = resolve(args.directory || "");
  if (!existsSync(directory)) {
    throw new Error(`Release asset directory does not exist: ${directory}`);
  }
  if (args.command === "create") {
    for (const name of ["version", "tag", "source-commit", "workflow-url"]) {
      if (!args[name]) {
        throw new Error(`--${name} is required when creating a release manifest.`);
      }
    }
    const manifest = createReleaseManifest({
      directory,
      version: args.version,
      tag: args.tag,
      sourceCommit: args["source-commit"],
      workflowUrl: args["workflow-url"],
      generatedAt: new Date().toISOString(),
    });
    const outputPath = join(directory, manifestFileName);
    writeFileSync(outputPath, `${JSON.stringify(manifest, null, 2)}\n`);
    console.log(`Created ${basename(outputPath)} for ${manifest.assets.length} release assets.`);
    return;
  }
  if (args.command === "verify") {
    const manifestPath = join(directory, manifestFileName);
    const manifest = JSON.parse(readFileSync(manifestPath, "utf8"));
    verifyReleaseManifest(directory, manifest);
    console.log(`Verified ${manifest.assets.length} release assets against ${manifestFileName}.`);
    return;
  }
  throw new Error("Usage: release-manifest.mjs create|verify --directory <path> [...]");
}

if (process.argv[1] && fileURLToPath(import.meta.url) === resolve(process.argv[1])) {
  try {
    main();
  } catch (error) {
    console.error(`ERROR: ${error.message}`);
    process.exit(1);
  }
}
