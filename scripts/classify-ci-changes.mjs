#!/usr/bin/env node

import { execFileSync } from "node:child_process";
import { appendFileSync } from "node:fs";
import { resolve } from "node:path";
import { fileURLToPath } from "node:url";

const evidenceOnlyPatterns = [
  /^docs\/release-checklist-\d+\.\d+\.\d+\.json$/u,
  /^docs\/release-notes\/v\d+\.\d+\.\d+\.md$/u,
  /^docs\/release-verification-\d+\.\d+\.\d+(?:-[A-Za-z0-9._-]+)?\.md$/u,
];

export function requiresHeavyValidation(paths) {
  return paths.length === 0 || paths.some((path) => !evidenceOnlyPatterns.some((pattern) => pattern.test(path)));
}

function parseArguments(argv) {
  const args = {};
  for (let index = 0; index < argv.length; index += 2) {
    const name = argv[index];
    const value = argv[index + 1];
    if (!name?.startsWith("--") || value === undefined) {
      throw new Error("Usage: classify-ci-changes.mjs --base <sha> --head <sha> --github-output <path>");
    }
    args[name.slice(2)] = value;
  }
  return args;
}

function main() {
  const args = parseArguments(process.argv.slice(2));
  if (!args.base || !args.head || !args["github-output"]) {
    throw new Error("--base, --head, and --github-output are required.");
  }
  const paths = execFileSync("git", ["diff", "--name-only", args.base, args.head], {
    encoding: "utf8",
  })
    .split("\n")
    .filter(Boolean);
  const heavy = requiresHeavyValidation(paths);
  appendFileSync(args["github-output"], `heavy=${heavy}\n`);
  console.log(`${heavy ? "Full" : "Evidence-only"} validation selected for ${paths.length} changed file(s).`);
}

if (process.argv[1] && fileURLToPath(import.meta.url) === resolve(process.argv[1])) {
  try {
    main();
  } catch (error) {
    console.error(`ERROR: ${error.message}`);
    process.exit(1);
  }
}
