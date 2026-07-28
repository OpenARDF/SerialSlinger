#!/usr/bin/env node

import { readFileSync, writeFileSync } from "node:fs";
import { resolve } from "node:path";
import process from "node:process";

function usage() {
  console.error([
    "Usage:",
    "  node ./scripts/update-release-checklist.mjs --file <checklist.json> --id <item-id> --done-evidence <evidence>",
    "  node ./scripts/update-release-checklist.mjs --file <checklist.json> --id <item-id> --skip-requested-by <name> --skip-reason <reason>",
  ].join("\n"));
}

function parseArgs(argv) {
  const options = {};
  for (let index = 2; index < argv.length; index += 1) {
    const name = argv[index];
    if (
      [
        "--file",
        "--id",
        "--done-evidence",
        "--skip-reason",
        "--skip-requested-by",
      ].includes(name)
    ) {
      options[name.slice(2)] = argv[index + 1];
      index += 1;
    } else if (name === "--help" || name === "-h") {
      options.help = true;
    } else {
      throw new Error(`Unknown argument: ${name}`);
    }
  }
  return options;
}

function main() {
  const options = parseArgs(process.argv);
  if (options.help) {
    usage();
    return;
  }
  if (!options.file || !options.id) {
    throw new Error("--file and --id are required.");
  }

  const done = Boolean(options["done-evidence"]);
  const skipped = Boolean(options["skip-reason"] || options["skip-requested-by"]);
  if (done === skipped) {
    throw new Error("Provide either --done-evidence, or both --skip-reason and --skip-requested-by.");
  }
  if (skipped && (!options["skip-reason"] || !options["skip-requested-by"])) {
    throw new Error("Skipped items require both --skip-reason and --skip-requested-by.");
  }

  const filePath = resolve(options.file);
  const checklist = JSON.parse(readFileSync(filePath, "utf8"));
  if (!Array.isArray(checklist.items)) {
    throw new Error("Checklist does not contain an items array.");
  }

  const matches = checklist.items.filter((item) => item.id === options.id);
  if (matches.length !== 1) {
    throw new Error(`Expected exactly one checklist item '${options.id}', found ${matches.length}.`);
  }

  const item = matches[0];
  if (done) {
    item.status = "done";
    item.evidence = options["done-evidence"];
    item.skipReason = "";
    item.skipRequestedBy = "";
  } else {
    item.status = "skipped";
    item.evidence = "";
    item.skipReason = options["skip-reason"];
    item.skipRequestedBy = options["skip-requested-by"];
  }

  writeFileSync(filePath, `${JSON.stringify(checklist, null, 2)}\n`, "utf8");
  console.log(`Updated ${options.id}: ${item.status}`);
}

try {
  main();
} catch (error) {
  console.error(error instanceof Error ? error.message : String(error));
  process.exit(1);
}
