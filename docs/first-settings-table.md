# First Settings Table

## Purpose

This document defines the first draft of the graphical settings table that SerialSlinger should show after it reads the current state from an attached SignalSlinger.

The first version should focus on **persistent configuration**.
It should not mix configuration fields with temporary action commands.

That means commands such as `GO`, `KEY`, `SLP`, and `RST` should not appear as ordinary editable table fields in the first table.
They belong in a separate actions area later.

## Table Philosophy

The first table should:

- show the device's current values clearly
- let the user edit supported settings safely
- mark which values were changed locally
- validate entries before submit
- submit only changed fields

The first table should not:

- expose raw protocol command names to the user
- require the user to know event-specific serial syntax
- mix live status values with editable configuration in confusing ways

## Proposed Table Sections

### 1. Device And Connection

These fields are read-only in the first version.

| Field Key | User Label | Editable | Type | Notes |
| --- | --- | --- | --- | --- |
| `serialPortName` | Serial Port | No | text | Selected or connected port |
| `connectionState` | Connection State | No | enum | Disconnected, Connecting, Connected, Error |
| `softwareVersion` | Software Version | No | text | Parsed from device version reply |
| `hardwareBuild` | Hardware Build | No | text | Parsed from device version reply |
| `temperatureC` | Temperature | No | decimal | Optional if supported by device |
| `internalBatteryVolts` | Internal Battery | No | decimal | Read-only status |
| `externalBatteryVolts` | External Battery | No | decimal | Read-only status |

### 2. Identity And Event Profile

| Field Key | User Label | Editable | Type | Initial Validation |
| --- | --- | --- | --- | --- |
| `stationId` | Station ID | Yes | text | non-empty or explicit blank |
| `eventType` | Event Type | Yes | enum | Classic, Foxoring, Sprint, None |
| `foxRole` | Fox Role | Yes | enum | choices depend on event type |
| `patternText` | Pattern Text | Yes | text | max length to be defined |
| `idCodeSpeedWpm` | ID Speed | Yes | integer | positive range |
| `patternCodeSpeedWpm` | Pattern Speed | Yes | integer | positive range |

### 3. Schedule

| Field Key | User Label | Editable | Type | Initial Validation |
| --- | --- | --- | --- | --- |
| `startTime` | Start Time | Yes | datetime | valid local date/time |
| `finishTime` | Finish Time | Yes | datetime | after start time |
| `daysToRun` | Days To Run | Yes | integer | minimum 1 |

### 4. Frequencies

| Field Key | User Label | Editable | Type | Initial Validation |
| --- | --- | --- | --- | --- |
| `currentFrequencyHz` | Current Frequency (`FRE`) | No | frequency | derived from the currently selected memory bank; accept display in `kHz`/`MHz` |
| `currentFrequencyBank` | Current Memory Bank | No | enum/text | derived from `EVT` + `FOX` settings when known |
| `lowFrequencyHz` | Frequency 1 (`FRE 1`) | Yes | frequency | valid device range; accept `kHz` or `MHz` entry; bare values like `3521` mean `3521 kHz` |
| `mediumFrequencyHz` | Frequency 2 (`FRE 2`) | Yes | frequency | valid device range; accept `kHz` or `MHz` entry; bare values like `3521` mean `3521 kHz` |
| `highFrequencyHz` | Frequency 3 (`FRE 3`) | Yes | frequency | valid device range; accept `kHz` or `MHz` entry; bare values like `3521` mean `3521 kHz` |
| `beaconFrequencyHz` | Frequency B (`FRE B`) | Yes | frequency | valid device range; accept `kHz` or `MHz` entry; bare values like `3521` mean `3521 kHz` |

`FRE 1`, `FRE 2`, `FRE 3`, and `FRE B` should be treated as editable frequency memory banks.
`FRE` should be treated as the current active frequency, which reflects the bank selected by the device's current `EVT` and `FOX` settings.
Writing `FRE 3521` is shorthand for "set the currently active bank to `3521 kHz`", so it should be treated as equivalent to writing the active bank directly, such as `FRE 1 3521` when bank 1 is active.

### 5. Power And Battery Behavior

| Field Key | User Label | Editable | Type | Initial Validation |
| --- | --- | --- | --- | --- |
| `lowBatteryThresholdVolts` | Low Battery Threshold | Yes | decimal | valid device range |
| `externalBatteryControlMode` | External Battery Control | Yes | enum | Off, Charge And Transmit, Charge Only |
| `transmissionsEnabled` | RF Transmissions | Yes | boolean | maps to disable/enable behavior |

## Important First-Cut Decisions

### Editable table vs actions

The following device interactions should be treated as actions, not persistent settings:

- refresh from device
- submit changes
- cancel local edits
- restart device
- key transmitter
- start event now
- put device to sleep

### Read-only vs editable

The first version should make it obvious which rows are:

- loaded from device and not editable
- editable but unchanged
- edited locally and not yet submitted
- failed validation
- failed to write

### Event-dependent behavior

Some fields are only meaningful for certain event modes.

Examples:

- `foxRole` choices depend on the selected event type
- `patternText` may matter more for foxoring than other modes
- each event exposes between 2 and 4 readable/writable frequency slots
- the UI should show frequency rows in an event-aware way instead of permanently showing every possible slot
- the UI should distinguish editable frequency memory banks from the current active `FRE` readback
- `EVT` and `FOX` determine which memory bank is currently active
- frequency entry should accept either `kHz` or `MHz`, and bare values like `3521` should be interpreted as `3521 kHz`

The table should support conditional enablement rather than forcing the user to understand protocol-specific edge cases.

## First Submit Behavior

When the user clicks `Submit`, the app should:

1. validate the edited settings model
2. compute a diff from the last device-loaded snapshot
3. build a write plan for changed fields only
4. write those fields in a deterministic order
5. report success or failure per field or per section
6. refresh the device snapshot after a successful write when practical

## Open Questions

- Should the first version support editing all fields above, or should some stay read-only until the protocol read/write path is proven?
- Should the internal `defaultFrequencyHz` model field be renamed to `currentFrequencyHz` to match how `FRE` behaves on the device?
- Should date/time fields be shown as one combined local timestamp or as separate date and time columns?
- How should unsupported fields appear when the connected device firmware does not report them?
