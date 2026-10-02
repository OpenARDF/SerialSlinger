#!/usr/bin/env node

import { readdir, readFile } from "node:fs/promises";
import path from "node:path";
import { fileURLToPath } from "node:url";

const defaultResultsDirectory = path.resolve(
  path.dirname(fileURLToPath(import.meta.url)),
  "../androidApp/build/outputs/androidTest-results/connected/debug",
);

async function findReports(directory) {
  const entries = await readdir(directory, { withFileTypes: true });
  const reports = [];
  for (const entry of entries) {
    const entryPath = path.join(directory, entry.name);
    if (entry.isDirectory()) {
      reports.push(...(await findReports(entryPath)));
    } else if (/^TEST-.*\.xml$/.test(entry.name)) {
      reports.push(entryPath);
    }
  }
  return reports;
}

function count(attribute, element) {
  const match = element.match(new RegExp(`\\b${attribute}="(\\d+)"`));
  if (!match) throw new Error(`Instrumentation report is missing ${attribute}.`);
  return Number.parseInt(match[1], 10);
}

export async function verifyInstrumentationResults(resultsDirectory) {
  const reports = await findReports(resultsDirectory);
  if (reports.length === 0) {
    throw new Error(`No Android instrumentation XML reports found in ${resultsDirectory}.`);
  }

  const totals = { tests: 0, failures: 0, errors: 0, skipped: 0 };
  for (const report of reports) {
    const xml = await readFile(report, "utf8");
    const element = xml.match(/<testsuites\b[^>]*>/)?.[0] ?? xml.match(/<testsuite\b[^>]*>/)?.[0];
    if (!element) throw new Error(`No test-suite summary found in ${report}.`);
    const reportCounts = Object.fromEntries(
      Object.keys(totals).map((attribute) => [attribute, count(attribute, element)]),
    );
    if (reportCounts.tests === 0) {
      throw new Error(`Android instrumentation executed zero tests in ${report}.`);
    }
    if (reportCounts.failures > 0 || reportCounts.errors > 0 || reportCounts.skipped > 0) {
      throw new Error(
        `Android instrumentation was incomplete in ${report}: ${reportCounts.tests} tests, ` +
          `${reportCounts.failures} failures, ${reportCounts.errors} errors, ` +
          `${reportCounts.skipped} skipped.`,
      );
    }
    for (const attribute of Object.keys(totals)) totals[attribute] += reportCounts[attribute];
  }

  return totals;
}

if (process.argv[1] === fileURLToPath(import.meta.url)) {
  try {
    const totals = await verifyInstrumentationResults(
      path.resolve(process.argv[2] ?? defaultResultsDirectory),
    );
    console.log(
      `Android instrumentation passed: ${totals.tests} tests, 0 failures, 0 errors, 0 skipped.`,
    );
  } catch (error) {
    console.error(error.message);
    process.exitCode = 1;
  }
}
