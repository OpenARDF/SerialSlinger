#!/usr/bin/env node

import { createSign } from "node:crypto";
import { existsSync, readFileSync } from "node:fs";
import { resolve } from "node:path";
import { fileURLToPath } from "node:url";

import { sectionBody } from "./release-notes-utils.mjs";

const publisherScope = "https://www.googleapis.com/auth/androidpublisher";
const oauthAudience = "https://oauth2.googleapis.com/token";

function base64Url(value) {
  return Buffer.from(value).toString("base64url");
}

export async function serviceAccountAccessToken(serviceAccount, fetchImpl = fetch, nowSeconds = Math.floor(Date.now() / 1000)) {
  if (!serviceAccount.client_email || !serviceAccount.private_key) {
    throw new Error("The Google Play service-account JSON lacks client_email or private_key.");
  }
  const header = base64Url(JSON.stringify({ alg: "RS256", typ: "JWT" }));
  const claims = base64Url(
    JSON.stringify({
      iss: serviceAccount.client_email,
      scope: publisherScope,
      aud: oauthAudience,
      iat: nowSeconds,
      exp: nowSeconds + 3600,
    }),
  );
  const unsignedToken = `${header}.${claims}`;
  const signer = createSign("RSA-SHA256");
  signer.update(unsignedToken);
  signer.end();
  const assertion = `${unsignedToken}.${signer.sign(serviceAccount.private_key, "base64url")}`;
  const response = await fetchImpl(oauthAudience, {
    method: "POST",
    headers: { "content-type": "application/x-www-form-urlencoded" },
    body: new URLSearchParams({
      grant_type: "urn:ietf:params:oauth:grant-type:jwt-bearer",
      assertion,
    }),
  });
  const body = await response.json();
  if (!response.ok || !body.access_token) {
    throw new Error(`Google OAuth token request failed (${response.status}): ${body.error_description || body.error || "unknown error"}`);
  }
  return body.access_token;
}

async function apiRequest(fetchImpl, url, accessToken, options = {}) {
  const response = await fetchImpl(url, {
    ...options,
    headers: {
      authorization: `Bearer ${accessToken}`,
      ...(options.headers || {}),
    },
  });
  const text = await response.text();
  const body = text ? JSON.parse(text) : {};
  if (!response.ok) {
    throw new Error(`Google Play API request failed (${response.status} ${options.method || "GET"} ${url}): ${body.error?.message || text}`);
  }
  return body;
}

export async function runPlayEdit({
  fetchImpl = fetch,
  accessToken,
  packageName,
  bundle,
  releaseName,
  releaseNotes,
  commit,
  apiBase = "https://androidpublisher.googleapis.com/androidpublisher/v3/applications",
  uploadBase = "https://androidpublisher.googleapis.com/upload/androidpublisher/v3/applications",
}) {
  const application = encodeURIComponent(packageName);
  const edit = await apiRequest(fetchImpl, `${apiBase}/${application}/edits`, accessToken, {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: "{}",
  });
  if (!edit.id) {
    throw new Error("Google Play did not return an edit ID.");
  }

  let committed = false;
  let operationError;
  try {
    const uploadedBundle = await apiRequest(
      fetchImpl,
      `${uploadBase}/${application}/edits/${encodeURIComponent(edit.id)}/bundles?uploadType=media`,
      accessToken,
      {
        method: "POST",
        headers: { "content-type": "application/octet-stream" },
        body: bundle,
      },
    );
    if (!uploadedBundle.versionCode) {
      throw new Error("Google Play did not return the uploaded bundle versionCode.");
    }
    await apiRequest(
      fetchImpl,
      `${apiBase}/${application}/edits/${encodeURIComponent(edit.id)}/tracks/internal`,
      accessToken,
      {
        method: "PUT",
        headers: { "content-type": "application/json" },
        body: JSON.stringify({
          track: "internal",
          releases: [
            {
              name: releaseName,
              versionCodes: [String(uploadedBundle.versionCode)],
              status: "completed",
              releaseNotes: [{ language: "en-US", text: releaseNotes }],
            },
          ],
        }),
      },
    );
    await apiRequest(fetchImpl, `${apiBase}/${application}/edits/${encodeURIComponent(edit.id)}:validate`, accessToken, {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: "{}",
    });
    if (commit) {
      await apiRequest(fetchImpl, `${apiBase}/${application}/edits/${encodeURIComponent(edit.id)}:commit`, accessToken, {
        method: "POST",
        headers: { "content-type": "application/json" },
        body: "{}",
      });
      committed = true;
    }
    return { editId: edit.id, versionCode: uploadedBundle.versionCode, committed };
  } catch (error) {
    operationError = error;
    throw error;
  } finally {
    if (!committed) {
      try {
        await apiRequest(fetchImpl, `${apiBase}/${application}/edits/${encodeURIComponent(edit.id)}`, accessToken, {
          method: "DELETE",
        });
      } catch (cleanupError) {
        if (!operationError) {
          throw cleanupError;
        }
        operationError.message += ` Cleanup also failed: ${cleanupError.message}`;
      }
    }
  }
}

function parseArguments(argv) {
  const args = {};
  for (let index = 0; index < argv.length; index += 2) {
    const name = argv[index];
    const value = argv[index + 1];
    if (!name?.startsWith("--") || value === undefined) {
      throw new Error("Arguments must use --name value pairs.");
    }
    args[name.slice(2)] = value;
  }
  return args;
}

async function main() {
  const args = parseArguments(process.argv.slice(2));
  for (const name of ["bundle", "service-account", "package-name", "version", "release-notes", "commit"]) {
    if (!args[name]) {
      throw new Error(`--${name} is required.`);
    }
  }
  if (!/^[0-9]+\.[0-9]+\.[0-9]+$/u.test(args.version)) {
    throw new Error("--version must use MAJOR.MINOR.PATCH.");
  }
  if (!["true", "false"].includes(args.commit)) {
    throw new Error("--commit must be true or false.");
  }
  const bundlePath = resolve(args.bundle);
  const serviceAccountPath = resolve(args["service-account"]);
  const releaseNotesPath = resolve(args["release-notes"]);
  for (const filePath of [bundlePath, serviceAccountPath, releaseNotesPath]) {
    if (!existsSync(filePath)) {
      throw new Error(`Required file does not exist: ${filePath}`);
    }
  }
  const packageJson = JSON.parse(readFileSync(resolve("package.json"), "utf8"));
  if (packageJson.version !== args.version) {
    throw new Error(`Requested Play version ${args.version} does not match package version ${packageJson.version}.`);
  }
  const noteFailures = [];
  const notes = readFileSync(releaseNotesPath, "utf8").replace(/\r\n/gu, "\n");
  const releaseNotes = sectionBody(notes.split("\n"), "Android release notes", noteFailures);
  if (noteFailures.length > 0 || releaseNotes.length < 20 || releaseNotes.length > 500) {
    throw new Error(`Android release notes are invalid: ${noteFailures.join("; ") || "expected 20-500 characters"}`);
  }

  const serviceAccount = JSON.parse(readFileSync(serviceAccountPath, "utf8"));
  const accessToken = await serviceAccountAccessToken(serviceAccount);
  const result = await runPlayEdit({
    accessToken,
    packageName: args["package-name"],
    bundle: readFileSync(bundlePath),
    releaseName: args.version,
    releaseNotes,
    commit: args.commit === "true",
  });
  console.log(
    result.committed
      ? `Committed Android ${args.version} (versionCode ${result.versionCode}) to Play internal testing.`
      : `Validated Android ${args.version} (versionCode ${result.versionCode}) and discarded edit ${result.editId}.`,
  );
}

if (process.argv[1] && fileURLToPath(import.meta.url) === resolve(process.argv[1])) {
  main().catch((error) => {
    console.error(`ERROR: ${error.message}`);
    process.exit(1);
  });
}
