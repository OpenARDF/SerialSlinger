# Initial Backlog

## Foundation

- choose the implementation stack for desktop and future mobile support
- document the first supported settings table
- define the initial device settings model
- define connection and error states

## TDD First Targets

- a `DeviceSettings` model test
- a field validation test suite
- a settings diff test suite
- a protocol parser/encoder test suite
- an application workflow test suite for `load -> edit -> submit`

## First UX Questions To Answer

- Which settings are always visible in the first table?
- Which fields are read-only vs editable?
- How should invalid values be shown before submit?
- Should submit send all values or only changed values?
- Should the UI refresh from device after submit automatically?
