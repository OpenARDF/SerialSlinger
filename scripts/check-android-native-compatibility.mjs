#!/usr/bin/env node

import { existsSync } from "node:fs";
import { pathToFileURL } from "node:url";
import yauzl from "yauzl";

const PAGE_SIZE_16_KB = 16 * 1024;
const PAGE_ALIGNMENT_16_KB = 2;
const BUNDLE_CONFIG_ENTRY = "BundleConfig.pb";
const NATIVE_LIBRARY_PATTERN = /^[^/]+\/lib\/([^/]+)\/([^/]+\.so)$/;
const REQUIRED_64_BIT_ABIS = new Set(["arm64-v8a", "x86_64"]);

function fail(message) {
  throw new Error(message);
}

function readVarint(buffer, startOffset) {
  let value = 0n;
  let shift = 0n;
  let offset = startOffset;
  while (offset < buffer.length && shift <= 63n) {
    const byte = buffer[offset++];
    value |= BigInt(byte & 0x7f) << shift;
    if ((byte & 0x80) === 0) {
      return { value: Number(value), offset };
    }
    shift += 7n;
  }
  fail(`Invalid protobuf varint at byte ${startOffset}`);
}

export function parseProtobufFields(buffer) {
  const fields = new Map();
  let offset = 0;
  while (offset < buffer.length) {
    const key = readVarint(buffer, offset);
    offset = key.offset;
    const fieldNumber = key.value >>> 3;
    const wireType = key.value & 0x07;
    if (fieldNumber === 0) {
      fail(`Invalid protobuf field number at byte ${offset}`);
    }

    let value;
    if (wireType === 0) {
      const decoded = readVarint(buffer, offset);
      value = decoded.value;
      offset = decoded.offset;
    } else if (wireType === 1) {
      if (offset + 8 > buffer.length) fail("Truncated protobuf fixed64 field");
      value = buffer.subarray(offset, offset + 8);
      offset += 8;
    } else if (wireType === 2) {
      const length = readVarint(buffer, offset);
      offset = length.offset;
      if (offset + length.value > buffer.length) fail("Truncated protobuf bytes field");
      value = buffer.subarray(offset, offset + length.value);
      offset += length.value;
    } else if (wireType === 5) {
      if (offset + 4 > buffer.length) fail("Truncated protobuf fixed32 field");
      value = buffer.subarray(offset, offset + 4);
      offset += 4;
    } else {
      fail(`Unsupported protobuf wire type ${wireType}`);
    }

    const values = fields.get(fieldNumber) || [];
    values.push(value);
    fields.set(fieldNumber, values);
  }
  return fields;
}

function requireSingleField(fields, fieldNumber, label) {
  const values = fields.get(fieldNumber) || [];
  if (values.length !== 1) {
    fail(`${label} expected one field ${fieldNumber}, found ${values.length}`);
  }
  return values[0];
}

export function readBundlePageAlignment(bundleConfig) {
  // BundleConfig.proto assigns field 2 to optimizations, field 2 within that
  // message to uncompress_native_libraries, and enum value 2 to 16 KB alignment.
  const root = parseProtobufFields(bundleConfig);
  const optimizations = parseProtobufFields(
    requireSingleField(root, 2, "BundleConfig optimizations")
  );
  const nativeLibraries = parseProtobufFields(
    requireSingleField(optimizations, 2, "BundleConfig native-library optimization")
  );
  const enabled = requireSingleField(nativeLibraries, 1, "uncompressed native libraries");
  const alignment = requireSingleField(nativeLibraries, 2, "native-library page alignment");
  if (enabled !== 1) {
    fail("The bundle does not enable uncompressed native libraries");
  }
  return alignment;
}

function readUnsigned(buffer, offset, byteLength, littleEndian) {
  if (offset + byteLength > buffer.length) fail("Truncated ELF header");
  if (byteLength === 2) {
    return littleEndian ? buffer.readUInt16LE(offset) : buffer.readUInt16BE(offset);
  }
  if (byteLength === 4) {
    return littleEndian ? buffer.readUInt32LE(offset) : buffer.readUInt32BE(offset);
  }
  if (byteLength === 8) {
    const value = littleEndian ? buffer.readBigUInt64LE(offset) : buffer.readBigUInt64BE(offset);
    if (value > BigInt(Number.MAX_SAFE_INTEGER)) fail("ELF integer exceeds JavaScript safe range");
    return Number(value);
  }
  fail(`Unsupported integer width ${byteLength}`);
}

export function readElfLoadAlignments(buffer) {
  if (buffer.length < 16 || !buffer.subarray(0, 4).equals(Buffer.from([0x7f, 0x45, 0x4c, 0x46]))) {
    fail("Native-library entry is not an ELF file");
  }
  const elfClass = buffer[4];
  const littleEndian = buffer[5] === 1;
  if (buffer[5] !== 1 && buffer[5] !== 2) fail(`Unsupported ELF byte order ${buffer[5]}`);

  let programHeaderOffset;
  let programHeaderEntrySize;
  let programHeaderCount;
  let typeOffset;
  let alignmentOffset;
  let alignmentSize;
  if (elfClass === 2) {
    programHeaderOffset = readUnsigned(buffer, 32, 8, littleEndian);
    programHeaderEntrySize = readUnsigned(buffer, 54, 2, littleEndian);
    programHeaderCount = readUnsigned(buffer, 56, 2, littleEndian);
    typeOffset = 0;
    alignmentOffset = 48;
    alignmentSize = 8;
  } else if (elfClass === 1) {
    programHeaderOffset = readUnsigned(buffer, 28, 4, littleEndian);
    programHeaderEntrySize = readUnsigned(buffer, 42, 2, littleEndian);
    programHeaderCount = readUnsigned(buffer, 44, 2, littleEndian);
    typeOffset = 0;
    alignmentOffset = 28;
    alignmentSize = 4;
  } else {
    fail(`Unsupported ELF class ${elfClass}`);
  }

  const alignments = [];
  for (let index = 0; index < programHeaderCount; index += 1) {
    const headerOffset = programHeaderOffset + index * programHeaderEntrySize;
    const type = readUnsigned(buffer, headerOffset + typeOffset, 4, littleEndian);
    if (type === 1) {
      alignments.push(
        readUnsigned(buffer, headerOffset + alignmentOffset, alignmentSize, littleEndian)
      );
    }
  }
  if (alignments.length === 0) fail("ELF file contains no loadable segments");
  return { elfClass, alignments };
}

function openZip(path) {
  return new Promise((resolve, reject) => {
    yauzl.open(path, { lazyEntries: true }, (error, zipFile) => {
      if (error) reject(error);
      else resolve(zipFile);
    });
  });
}

function readZipEntry(zipFile, entry) {
  return new Promise((resolve, reject) => {
    zipFile.openReadStream(entry, (error, stream) => {
      if (error) {
        reject(error);
        return;
      }
      const chunks = [];
      stream.on("data", chunk => chunks.push(chunk));
      stream.on("error", reject);
      stream.on("end", () => resolve(Buffer.concat(chunks)));
    });
  });
}

async function readRelevantBundleEntries(bundlePath) {
  const zipFile = await openZip(bundlePath);
  const result = { bundleConfig: null, nativeLibraries: [] };
  return new Promise((resolve, reject) => {
    zipFile.on("error", reject);
    zipFile.on("end", () => resolve(result));
    zipFile.on("entry", async entry => {
      try {
        const nativeMatch = NATIVE_LIBRARY_PATTERN.exec(entry.fileName);
        if (entry.fileName === BUNDLE_CONFIG_ENTRY) {
          result.bundleConfig = await readZipEntry(zipFile, entry);
        } else if (nativeMatch) {
          result.nativeLibraries.push({
            path: entry.fileName,
            abi: nativeMatch[1],
            data: await readZipEntry(zipFile, entry)
          });
        }
        zipFile.readEntry();
      } catch (error) {
        zipFile.close();
        reject(error);
      }
    });
    zipFile.readEntry();
  });
}

export async function checkBundle(bundlePath) {
  if (!existsSync(bundlePath)) fail(`Android App Bundle does not exist: ${bundlePath}`);
  const entries = await readRelevantBundleEntries(bundlePath);
  if (!entries.bundleConfig) fail(`${bundlePath} does not contain ${BUNDLE_CONFIG_ENTRY}`);

  const pageAlignment = readBundlePageAlignment(entries.bundleConfig);
  if (pageAlignment !== PAGE_ALIGNMENT_16_KB) {
    fail(`Bundle requests page-alignment enum ${pageAlignment}; expected 16 KB`);
  }

  const allNativeAbis = new Set(entries.nativeLibraries.map(library => library.abi));
  const libraries64 = entries.nativeLibraries.filter(library => REQUIRED_64_BIT_ABIS.has(library.abi));
  if (entries.nativeLibraries.length > 0 && !allNativeAbis.has("arm64-v8a")) {
    fail("Bundle contains native libraries but no arm64-v8a variant");
  }

  const verified = [];
  for (const library of libraries64.sort((left, right) => left.path.localeCompare(right.path))) {
    const elf = readElfLoadAlignments(library.data);
    if (elf.elfClass !== 2) fail(`${library.path} is not a 64-bit ELF file`);
    const minimumAlignment = Math.min(...elf.alignments);
    if (minimumAlignment < PAGE_SIZE_16_KB) {
      fail(`${library.path} has a ${minimumAlignment}-byte ELF load alignment; expected at least 16384`);
    }
    verified.push({ path: library.path, minimumAlignment });
  }

  console.log(`PASS: ${bundlePath} requests 16 KB native-library packaging alignment.`);
  if (verified.length === 0) {
    console.log("PASS: The bundle contains no 64-bit native libraries requiring ELF inspection.");
  } else {
    for (const library of verified) {
      console.log(`PASS: ${library.path} load segments are aligned to at least ${library.minimumAlignment} bytes.`);
    }
  }
  return { pageAlignment, verified };
}

async function main() {
  const bundlePath = process.argv[2];
  if (!bundlePath || process.argv.length !== 3) {
    console.error("Usage: node scripts/check-android-native-compatibility.mjs <app-bundle.aab>");
    process.exit(2);
  }
  try {
    await checkBundle(bundlePath);
  } catch (error) {
    console.error(`ERROR: ${error.message}`);
    process.exit(1);
  }
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  await main();
}
