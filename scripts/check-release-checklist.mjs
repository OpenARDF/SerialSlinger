import fs from "node:fs";
import process from "node:process";

const phases = {
  "pre-tag": [
    "version-aligned",
    "android-version-code",
    "release-preflight",
    "platform-parity-review",
    "android-regression",
    "desktop-regression",
    "windows-intel-x64-smoke",
    "windows-arm64-smoke",
    "linux-intel-x64-smoke",
    "linux-arm64-smoke",
    "android-release-notes",
    "merged-to-main",
  ],
  final: [
    "version-aligned",
    "android-version-code",
    "release-preflight",
    "platform-parity-review",
    "android-regression",
    "desktop-regression",
    "windows-intel-x64-smoke",
    "windows-arm64-smoke",
    "linux-intel-x64-smoke",
    "linux-arm64-smoke",
    "android-release-notes",
    "merged-to-main",
    "tag-pushed",
    "workflow-published",
    "release-verified",
    "final-checklist-audit",
  ],
};

function usage() {
  console.error([
    "Usage:",
    "  node ./scripts/check-release-checklist.mjs --file <checklist.json> [--phase pre-tag|final]",
    "",
    "Each required checklist item must have status \"done\" with evidence, or status",
    "\"skipped\" with skipReason and skipRequestedBy.",
  ].join("\n"));
}

function fail(message) {
  console.error(message);
  process.exit(1);
}

function parseArgs(argv) {
  const args = {
    file: process.env.RELEASE_CHECKLIST_FILE || "",
    phase: "final",
  };

  for (let index = 0; index < argv.length; index += 1) {
    const arg = argv[index];
    if (arg === "--file") {
      args.file = argv[++index] || "";
    } else if (arg === "--phase") {
      args.phase = argv[++index] || "";
    } else if (arg === "-h" || arg === "--help") {
      usage();
      process.exit(0);
    } else {
      fail(`Unknown argument: ${arg}`);
    }
  }

  return args;
}

function nonBlank(value) {
  return typeof value === "string" && value.trim().length > 0;
}

const args = parseArgs(process.argv.slice(2));
if (!args.file) {
  usage();
  fail("Missing --file or RELEASE_CHECKLIST_FILE.");
}
if (!Object.hasOwn(phases, args.phase)) {
  usage();
  fail(`Unsupported phase: ${args.phase}`);
}
if (!fs.existsSync(args.file)) {
  fail(`Release checklist file does not exist: ${args.file}`);
}

let checklist;
try {
  checklist = JSON.parse(fs.readFileSync(args.file, "utf8"));
} catch (error) {
  fail(`Could not parse release checklist JSON: ${error.message}`);
}

if (!Array.isArray(checklist.items)) {
  fail("Release checklist must contain an items array.");
}

const itemById = new Map(checklist.items.map((item) => [item.id, item]));
const failures = [];

for (const id of phases[args.phase]) {
  const item = itemById.get(id);
  if (!item) {
    failures.push(`Missing checklist item: ${id}`);
    continue;
  }

  if (item.status === "done") {
    if (!nonBlank(item.evidence)) {
      failures.push(`${id}: done items must include evidence.`);
    }
    continue;
  }

  if (item.status === "skipped") {
    if (!nonBlank(item.skipReason)) {
      failures.push(`${id}: skipped items must include skipReason.`);
    }
    if (!nonBlank(item.skipRequestedBy)) {
      failures.push(`${id}: skipped items must include skipRequestedBy.`);
    }
    continue;
  }

  failures.push(`${id}: status must be "done" or "skipped", found "${item.status || "<missing>"}".`);
}

if (failures.length > 0) {
  console.error(`Release checklist failed for phase '${args.phase}':`);
  failures.forEach((failure) => console.error(`- ${failure}`));
  process.exit(1);
}

console.log(`Release checklist passed for phase '${args.phase}': ${args.file}`);
