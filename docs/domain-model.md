# Domain Model Draft

## Purpose

This document describes the first-pass domain objects that should sit between the UI and the SignalSlinger protocol.

The key rule is simple:

**the UI edits a structured settings model, not serial commands.**

## Core Model Shape

The app should work with three related concepts:

1. a device snapshot loaded from the attached SignalSlinger
2. an editable settings model derived from that snapshot
3. a write plan that describes the exact changes to send back

## Draft Objects

### `DeviceSnapshot`

Represents the last known state read from the device.

Suggested contents:

- device info
- live status
- current editable settings
- capability flags
- timestamp of last successful read

### `DeviceInfo`

Read-only identity details.

Suggested fields:

- `productName`
- `softwareVersion`
- `hardwareBuild`
- `serialPortName`

### `DeviceStatus`

Read-only live state values.

Suggested fields:

- `connectionState`
- `temperatureC`
- `internalBatteryVolts`
- `externalBatteryVolts`
- `lastCommunicationError`

### `DeviceSettings`

Persistent configuration values that the table edits.

Suggested fields:

- `stationId`
- `eventType`
- `foxRole`
- `patternText`
- `idCodeSpeedWpm`
- `patternCodeSpeedWpm`
- `startTime`
- `finishTime`
- `daysToRun`
- `defaultFrequencyHz`
- `lowFrequencyHz`
- `mediumFrequencyHz`
- `highFrequencyHz`
- `beaconFrequencyHz`
- `lowBatteryThresholdVolts`
- `externalBatteryControlMode`
- `transmissionsEnabled`

Frequency note:

- treat `FRE 1`, `FRE 2`, `FRE 3`, and `FRE B` as editable frequency memory banks
- treat `FRE` as the device's current active frequency, selected by `EVT` and `FOX`
- the first shared model may keep the existing `defaultFrequencyHz` field name for compatibility, but the UI should present it as current active frequency rather than as another independent bank

### `DeviceCapabilities`

Describes which features the connected device actually supports.

Suggested fields:

- `supportsTemperatureReadback`
- `supportsExternalBatteryControl`
- `supportsPatternEditing`
- `supportsScheduling`
- `supportsFrequencyProfiles`

This lets the UI disable or hide fields safely instead of guessing from firmware behavior.

## Per-Field Editing Model

A plain `DeviceSettings` object is not enough for a good UI.
The UI also needs to know which values changed and whether they are valid.

Suggested wrapper:

### `SettingsField<T>`

Suggested fields:

- `key`
- `label`
- `originalValue`
- `editedValue`
- `isWritable`
- `isVisible`
- `validationState`
- `units`
- `helpText`

Derived properties:

- `isDirty`
- `hasError`
- `displayValue`

### `EditableDeviceSettings`

A container holding all visible `SettingsField` instances for the table.

Responsibilities:

- expose rows to the UI
- answer whether any field is dirty
- produce a validated `DeviceSettings` object
- support reset-to-device behavior

## Write Planning Model

The submit path should not send the whole table blindly.
It should compute an explicit write plan.

### `SettingChange`

Represents one logical setting change.

Suggested fields:

- `fieldKey`
- `oldValue`
- `newValue`
- `requiresVerification`
- `writeOrder`

### `WritePlan`

Represents the whole submit operation.

Suggested fields:

- `changes`
- `warnings`
- `blockingErrors`

Responsibilities:

- preserve a deterministic write order
- allow event-dependent ordering rules
- keep frequency editing event-aware because each event uses only 2 to 4 frequency slots
- accept human-entered frequencies in either `kHz` or `MHz`, and treat bare values like `3521` as `3521 kHz`
- support partial failure reporting

## Actions Are Separate From Settings

The following should not live inside `DeviceSettings`:

- refresh now
- submit now
- reboot device
- key transmitter
- start event now
- sleep device

Those belong in a separate action model, for example `DeviceAction` or `ToolbarAction`.

## TDD Targets From This Model

The first unit tests should prove:

1. `SettingsField.isDirty` changes only when the edited value differs from the original value
2. invalid values block conversion from `EditableDeviceSettings` to `DeviceSettings`
3. a diff between two `DeviceSettings` objects yields the expected `SettingChange` list
4. the write plan uses a stable field order
5. unsupported fields are excluded or marked read-only based on `DeviceCapabilities`

## Open Questions

- Do we want a strongly typed field per property, or a generic schema-driven row model for the table?
- Should schedule values be stored as local date/time values or normalized instants plus timezone information?
- Which fields require readback verification after a write?
- Should submit stop on first failure or continue and report a partial result?
