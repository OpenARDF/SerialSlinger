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

## Future Device Controls

- evaluate an advanced thermal shutdown threshold control; keep the cutoff
  read-only until firmware command semantics, safety limits, validation, and
  tester guidance are defined

## Release Workflow

- before the next jDeploy deployment, address the GitHub Actions Node.js 20
  deprecation warning from the nested jDeploy release action path
- after the protected `Development1` release flow has completed at least one
  full deployment successfully, evaluate consolidating development and releases
  onto one protected `main` branch with short-lived topic branches
- in the simplified flow, tag the fully tested `main` commit and let GitHub
  Actions publish it; keep release notes, workflow links, artifact hashes, and
  verification summaries in the GitHub Release or immutable release assets
  instead of adding post-publication evidence commits
- evaluate GitHub artifact attestations for downloadable packages and immutable
  releases using a draft-first asset upload; adopt them only after the workflow
  verifies every required asset before publication
- retire the checklist-only tag commit only after its source-commit, version,
  checklist, and changed-file guarantees have equivalent automated enforcement
  in the tag workflow; preserve historical tags and release records unchanged
