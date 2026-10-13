#!/usr/bin/env node

import assert from "node:assert/strict";
import { execFileSync } from "node:child_process";
import { chmodSync, mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join, resolve } from "node:path";
import test from "node:test";

const updateScript = resolve("scripts/update-jdeploy-metadata.sh");

test("preserves the prior primary before publishing the new primary", () => {
  const root = mkdtempSync(join(tmpdir(), "serialslinger-metadata-test-"));
  try {
    const releaseDirectory = join(root, "release-files");
    const fakeBin = join(root, "bin");
    const stateDirectory = join(root, "state");
    const callLog = join(root, "calls.log");
    mkdirSync(releaseDirectory);
    mkdirSync(fakeBin);
    mkdirSync(stateDirectory);
    writeFileSync(join(releaseDirectory, "package-info.json"), '{"versions":["2.2.0"]}\n');
    writeFileSync(join(releaseDirectory, "icon.png"), "icon\n");
    writeFileSync(join(stateDirectory, "package-info.json"), '{"versions":["2.1.0"]}\n');
    writeFileSync(join(stateDirectory, "package-info-2.json"), '{"versions":["2.0.25"]}\n');

    const fakeGh = join(fakeBin, "gh");
    writeFileSync(
      fakeGh,
      `#!/usr/bin/env bash
set -euo pipefail
printf '%s\n' "$*" >> "$FAKE_CALL_LOG"
if [[ "$1 $2" == "release view" ]]; then
  printf 'false\tfalse\n'
elif [[ "$1 $2" == "api repos/OpenARDF/SerialSlinger/releases/tags/jdeploy" ]]; then
  printf '%s\n' '{"id":123,"assets":[{"id":11,"name":"package-info.json","digest":"sha256:old"},{"id":22,"name":"package-info-2.json","digest":"sha256:older"}]}'
elif [[ "$1 $2" == "api repos/OpenARDF/SerialSlinger/releases/123" ]]; then
  printf '%s\n' 'sha256:old'
elif [[ "$1 $2" == "release upload" ]]; then
  source_path="$4"
  case "$(basename "$source_path")" in
    package-info.json|package-info-2.json) cp "$source_path" "$FAKE_STATE/$(basename "$source_path")" ;;
  esac
elif [[ "$1 $2" == "release download" ]]; then
  destination=""
  patterns=()
  while (($#)); do
    if [[ "$1" == "-D" ]]; then destination="$2"; shift 2; continue; fi
    if [[ "$1" == "-p" ]]; then patterns+=("$2"); shift 2; continue; fi
    shift
  done
  mkdir -p "$destination"
  for pattern in "\${patterns[@]}"; do cp "$FAKE_STATE/$pattern" "$destination/$pattern"; done
fi
`,
    );
    chmodSync(fakeGh, 0o755);

    const executionOptions = {
      cwd: resolve("."),
      env: {
        ...process.env,
        PATH: `${fakeBin}:${process.env.PATH}`,
        FAKE_CALL_LOG: callLog,
        FAKE_STATE: stateDirectory,
        GH_TOKEN: "test-token",
        GITHUB_REPOSITORY: "OpenARDF/SerialSlinger",
        SERIALSLINGER_ALLOW_GITHUB_RELEASE_PUBLISH: "1",
        SERIALSLINGER_RELEASE_TAG: "v2.2.0",
        SERIALSLINGER_RELEASE_WORKFLOW: "1",
      },
      stdio: "pipe",
    };
    execFileSync("bash", [updateScript, releaseDirectory], executionOptions);

    assert.equal(readFileSync(join(stateDirectory, "package-info-2.json"), "utf8"), '{"versions":["2.1.0"]}\n');
    assert.equal(readFileSync(join(stateDirectory, "package-info.json"), "utf8"), '{"versions":["2.2.0"]}\n');
    execFileSync("bash", [updateScript, releaseDirectory], executionOptions);
    assert.equal(
      readFileSync(join(stateDirectory, "package-info-2.json"), "utf8"),
      '{"versions":["2.1.0"]}\n',
      "an idempotent retry must not replace the prior-version fallback with the current primary",
    );
    const calls = readFileSync(callLog, "utf8");
    assert.ok(
      calls.indexOf("release upload jdeploy") < calls.lastIndexOf("release upload jdeploy"),
      "fallback upload must precede the new primary and icon uploads",
    );
  } finally {
    rmSync(root, { recursive: true, force: true });
  }
});
