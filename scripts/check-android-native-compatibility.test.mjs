import assert from "node:assert/strict";
import test from "node:test";

import {
  parseProtobufFields,
  readBundlePageAlignment,
  readElfLoadAlignments
} from "./check-android-native-compatibility.mjs";

function minimalBundleConfig(alignment) {
  // BundleConfig { optimizations { uncompress_native_libraries { enabled: true, alignment } } }
  return Buffer.from([0x12, 0x06, 0x12, 0x04, 0x08, 0x01, 0x10, alignment]);
}

function minimalElf64(alignment) {
  const buffer = Buffer.alloc(64 + 56);
  Buffer.from([0x7f, 0x45, 0x4c, 0x46]).copy(buffer);
  buffer[4] = 2;
  buffer[5] = 1;
  buffer[6] = 1;
  buffer.writeBigUInt64LE(64n, 32);
  buffer.writeUInt16LE(56, 54);
  buffer.writeUInt16LE(1, 56);
  buffer.writeUInt32LE(1, 64);
  buffer.writeBigUInt64LE(BigInt(alignment), 64 + 48);
  return buffer;
}

test("reads the 16 KB page-alignment enum from BundleConfig", () => {
  assert.equal(readBundlePageAlignment(minimalBundleConfig(2)), 2);
});

test("distinguishes a 4 KB BundleConfig page alignment", () => {
  assert.equal(readBundlePageAlignment(minimalBundleConfig(1)), 1);
});

test("parses repeated protobuf fields without losing values", () => {
  const fields = parseProtobufFields(Buffer.from([0x08, 0x01, 0x08, 0x02]));
  assert.deepEqual(fields.get(1), [1, 2]);
});

test("reads ELF64 load-segment alignment", () => {
  assert.deepEqual(readElfLoadAlignments(minimalElf64(16 * 1024)), {
    elfClass: 2,
    alignments: [16 * 1024]
  });
});

test("reports a 4 KB ELF64 load-segment alignment", () => {
  assert.deepEqual(readElfLoadAlignments(minimalElf64(4 * 1024)), {
    elfClass: 2,
    alignments: [4 * 1024]
  });
});
