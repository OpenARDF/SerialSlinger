# SignalSlinger Release Handling

This note records the release-package rules that should guide future SignalSlinger firmware update and bootloader-install work.

## Core Rule

Do not infer package capability from release version numbers.

SerialSlinger must decide whether a package can perform a requested operation from the package contents, release metadata, verified files, and required script or protocol capabilities. This matters because public release numbering may be restarted or refreshed during testing, and an older-looking version can still contain newer hardened assets.

## Separate Package Uses

Treat these as separate capability checks:

- Firmware update: requires a valid update manifest, matching hardware, verified update HEX, supported firmware-update metadata, and a compatible bootloader response.
- Bootloader installation: requires a complete setup package, verified setup/helper files, setup launcher metadata, required launcher features, and a successful programmer check.

A package can be valid for firmware update while still being unusable for bootloader installation. The UI should say that plainly and recommend a useful next action.

## Firmware Update Checks

Before writing firmware, keep checking the actual metadata and device responses:

- release product is `SignalSlinger`
- connected firmware supports updates, unless the user deliberately chooses recovery
- connected or selected hardware matches the release package
- update HEX exists and matches manifest size and SHA-256
- update HEX start address matches release metadata
- release app start matches bootloader `app`
- bootloader protocol, page size, baud, write range, and required commands are compatible
- post-update `INF` confirms the expected product, version, hardware, app start, and update baud

If any of these checks fail, stop before writing and show a user-facing explanation that avoids internal terms unless the details are in logs.

## Bootloader Installation Checks

Before launching programmer setup, validate the package by capability:

- complete release ZIP or resident bundle is present and extractable
- release manifest and listed files pass size and SHA-256 checks
- `workshopSetup` metadata is present
- setup helper HEX and application HEX files are present and verified
- setup launcher file is present and verified
- setup launcher supports the required guarded flow, including programmer probing such as `-CheckProgrammer`
- required local tools are available, or the missing-tool message tells the user what to install

Do not hard-code checks such as "version must be greater than 2.0.1" to decide whether bootloader installation is supported. If the package lacks a required capability, report the missing capability in friendly language, offer `Download Latest` when online, and otherwise abort cleanly.

## Resident And Online Packages

Firmware update behavior:

- Prefer a resident package when it is the same firmware version as the latest available online package.
- If online lookup or download fails, offer a resident package only when it is newer than the connected firmware and matches the hardware.

Bootloader installation behavior:

- `Download Latest` should replace a same-version resident setup package if the resident package lacks required setup capabilities.
- A local or resident package should be accepted only after the same metadata, file, hash, and launcher-capability checks pass.
- If no usable local, resident, or downloadable package is available, stop with a friendly message rather than continuing with stale setup scripts.

## User-Facing Failure Style

Normal users should not have to know terms such as manifest, workshop flow, fuse metadata, reset vector, or boot section.

Prefer messages like:

- "This SignalSlinger package does not include the setup tools needed to install a bootloader."
- "The saved setup package has out-of-date setup scripts."
- "SerialSlinger could not download a refreshed setup package. Check the internet connection and try Download Latest again."
- "This update file is for different SignalSlinger hardware."

Put technical detail, command lines, script output, and exact protocol responses in logs.

## Regression Expectations

When release assets are refreshed or release numbering is reset, include tests or manual checks for:

- successful update using the latest online package
- same-version resident package preference for normal firmware updates
- same-version resident package replacement for bootloader setup when setup scripts are stale
- friendly failure for missing `workshopSetup`
- friendly failure for missing setup launcher metadata
- offline failure when no valid resident setup package exists
- recovery update path when the app is absent or not responding

