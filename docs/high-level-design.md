# High-Level Design

## Guiding Principle

SerialSlinger should be built around a device settings model, not around raw protocol strings.

The user interacts with a settings table.
The software translates between that table and the SignalSlinger serial protocol behind the scenes.

## Proposed Layers

### 1. UI Layer

Responsibilities:

- show connection state
- show a settings table
- show edited vs device-loaded values
- allow the user to submit changes
- show validation and communication errors

### 2. Application Layer

Responsibilities:

- coordinate `connect`, `read settings`, `edit`, and `submit`
- track dirty state
- decide which settings changed
- prepare a write plan for only the changed values

### 3. Domain Model Layer

Responsibilities:

- define a structured `DeviceSettings` model
- define field types, ranges, and validation rules
- represent per-field read/write capability and change state

### 4. Device Service Layer

Responsibilities:

- query the device for current settings
- write changed settings back to the device
- perform readback or confirmation when needed
- convert transport/protocol failures into user-meaningful errors

### 5. Protocol Layer

Responsibilities:

- encode SignalSlinger commands
- parse device replies
- map protocol values to structured settings fields

### 6. Transport Layer

Responsibilities:

- open and close serial connections
- send and receive bytes or lines
- handle timeouts and disconnects

## Recommended Early TDD Slices

1. Define a first `DeviceSettings` shape.
2. Define a `SettingsField` model with validation and dirty-state tracking.
3. Test the diffing logic that decides what changed.
4. Test protocol encoding and parsing separately from the UI.
5. Test the application workflow with a fake device service.

## Early Architectural Constraint

The UI should never need to know that `FOX`, `CLK`, `FRE`, or other SignalSlinger command strings exist.
That knowledge belongs below the application boundary.
