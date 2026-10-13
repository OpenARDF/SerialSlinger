#!/usr/bin/env node

import assert from "node:assert/strict";
import { generateKeyPairSync } from "node:crypto";
import test from "node:test";

import { runPlayEdit, serviceAccountAccessToken } from "./publish-google-play-internal.mjs";

function response(status, body = {}) {
  return {
    ok: status >= 200 && status < 300,
    status,
    async text() {
      return Object.keys(body).length === 0 ? "" : JSON.stringify(body);
    },
  };
}

test("exchanges a scoped, one-hour service-account assertion without exposing credentials", async () => {
  const { privateKey } = generateKeyPairSync("rsa", { modulusLength: 2048 });
  let tokenRequest;
  const fetchImpl = async (url, options) => {
    tokenRequest = { url, options };
    return {
      ok: true,
      status: 200,
      async json() {
        return { access_token: "short-lived-token" };
      },
    };
  };
  const token = await serviceAccountAccessToken(
    {
      client_email: "play-publisher@example.invalid",
      private_key: privateKey.export({ type: "pkcs8", format: "pem" }),
    },
    fetchImpl,
    1_800_000_000,
  );
  assert.equal(token, "short-lived-token");
  assert.equal(tokenRequest.url, "https://oauth2.googleapis.com/token");
  const assertion = new URLSearchParams(tokenRequest.options.body).get("assertion");
  const claims = JSON.parse(Buffer.from(assertion.split(".")[1], "base64url").toString("utf8"));
  assert.equal(claims.scope, "https://www.googleapis.com/auth/androidpublisher");
  assert.equal(claims.exp - claims.iat, 3600);
});

test("validates and discards an internal-track edit by default", async () => {
  const calls = [];
  const fetchImpl = async (url, options) => {
    calls.push({ url, options });
    if (url.endsWith("/edits")) return response(200, { id: "edit-1" });
    if (url.includes("/bundles?")) return response(200, { versionCode: 35, sha256: "abc" });
    return response(200);
  };
  const result = await runPlayEdit({
    fetchImpl,
    accessToken: "token",
    packageName: "com.SerialSlinger.openardf",
    bundle: Buffer.from("bundle"),
    releaseName: "2.2.0",
    releaseNotes: "A sufficiently detailed internal release note.",
    commit: false,
    apiBase: "https://example.invalid/api",
    uploadBase: "https://example.invalid/upload",
  });
  assert.equal(result.committed, false);
  assert.equal(result.versionCode, 35);
  assert.equal(calls.at(-1).options.method, "DELETE");
  const trackCall = calls.find(({ url }) => url.endsWith("/tracks/internal"));
  const track = JSON.parse(trackCall.options.body);
  assert.equal(track.releases[0].status, "completed");
  assert.deepEqual(track.releases[0].versionCodes, ["35"]);
});

test("commits only after the edit validates", async () => {
  const calls = [];
  const fetchImpl = async (url, options) => {
    calls.push({ url, options });
    if (url.endsWith("/edits")) return response(200, { id: "edit-2" });
    if (url.includes("/bundles?")) return response(200, { versionCode: 36 });
    return response(200);
  };
  const result = await runPlayEdit({
    fetchImpl,
    accessToken: "token",
    packageName: "com.SerialSlinger.openardf",
    bundle: Buffer.from("bundle"),
    releaseName: "2.2.1",
    releaseNotes: "A sufficiently detailed internal release note.",
    commit: true,
    apiBase: "https://example.invalid/api",
    uploadBase: "https://example.invalid/upload",
  });
  assert.equal(result.committed, true);
  assert.ok(calls.findIndex(({ url }) => url.endsWith(":validate")) < calls.findIndex(({ url }) => url.endsWith(":commit")));
  assert.equal(calls.some(({ options }) => options.method === "DELETE"), false);
});

test("preserves the original Play failure when edit cleanup also fails", async () => {
  const fetchImpl = async (url, options) => {
    if (url.endsWith("/edits")) return response(200, { id: "edit-3" });
    if (options.method === "DELETE") return response(503, { error: { message: "cleanup unavailable" } });
    return response(400, { error: { message: "bundle rejected" } });
  };
  await assert.rejects(
    runPlayEdit({
      fetchImpl,
      accessToken: "token",
      packageName: "com.SerialSlinger.openardf",
      bundle: Buffer.from("bundle"),
      releaseName: "2.2.2",
      releaseNotes: "A sufficiently detailed internal release note.",
      commit: false,
      apiBase: "https://example.invalid/api",
      uploadBase: "https://example.invalid/upload",
    }),
    /bundle rejected.*Cleanup also failed:.*cleanup unavailable/u,
  );
});
