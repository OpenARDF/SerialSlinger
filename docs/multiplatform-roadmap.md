# SerialSlinger Multiplatform Roadmap

## Current State

SerialSlinger supports Android, macOS, Windows, and Linux. The apps share
Kotlin protocol, settings, and session logic for reading and configuring
connected devices. See the [developer guide](developer-guide.md) for the
platform architecture and the [release workflow](release-workflow.md) for
validation and distribution.

## Near-Term Roadmap

### Google Play production readiness

Complete these items before applying for production access for
`com.SerialSlinger.openardf`. Review this checklist monthly and before applying.
Update the review date and open items here. When closing an item, link the
build, test result, or dated Play Console observation that supports it.

**Last reviewed November 27, 2026.** The 2.1.0 release manifest targets API 37
and requests no location, SMS, or call-log permissions. Production eligibility
and store screenshot inventory still need a Play Console check.

- [ ] Allow the folded outer screen to use portrait. The activity currently
  requests `sensorLandscape`, while its narrow layout already stacks the controls.
  Check that device settings, action buttons, and logs are reachable in that layout.
- [ ] Keep an active temperature log running when Android recreates the main
  activity. Its `onDestroy()` handler currently disables logging. Verify that
  a real USB session and temperature log continue through rotation or folding
  and that the operator can still stop logging explicitly.
- [ ] Run the Tier 3 tests on both 1:1 and 4:3 foldable profiles. Rotate, fold,
  unfold, and resize in split-screen while editing device settings. Verify that
  unsent values and scroll position survive, controls respond, and the app fills
  the window without clipping or letterboxing. Record the build, emulator
  results, and hardware-session evidence. [Android Tier 3 tests](https://developer.android.com/docs/quality-guidelines/adaptive-app-quality/tier-3)
- [ ] Complete the listing and verify its screenshots in Play Console. Capture
  two standard phone screenshots and three foldable views from a passing build
  (1:1 unfolded, 4:3 unfolded, and 10:16 folded portrait). Google accepts
  emulator captures. Tier 3 and foldable screenshots are required from April
  2027. [Google Play adaptive quality requirements](https://developer.android.com/blog/posts/google-play-adaptive-quality-standards)
- [ ] Check this package's production-access dashboard. Record the required
  tester count and duration if Play requires a closed test, complete the test,
  and verify eligibility. Recheck the candidate's target API, merged release
  permissions, outstanding policy declarations, and listing status before
  applying. Record the results for this app.

### Future Device Controls

- evaluate an advanced thermal shutdown threshold control; keep the cutoff
  read-only until firmware command semantics, safety limits, validation, and
  tester guidance are defined

### Release Workflow

- evaluate Developer ID signing and Apple notarization after the main-only,
  dry-run-first release workflow has completed a production deployment
- evaluate automated Play production staged rollouts only after the protected
  internal-testing upload has been exercised and its readback is reliable

## Initial Planning Notes

These were the foundation tasks and design questions from the initial backlog.
The [stack decision](stack-decision.md), [first settings table](first-settings-table.md),
and [domain model](domain-model.md) document the resulting design.

### Foundation

- choose the implementation stack for desktop and future mobile support
- document the first supported settings table
- define the initial device settings model
- define connection and error states

### TDD First Targets

- a `DeviceSettings` model test
- a field validation test suite
- a settings diff test suite
- a protocol parser/encoder test suite
- an application workflow test suite for `load -> edit -> submit`

### First UX Questions To Answer

- Which settings are always visible in the first table?
- Which fields are read-only vs editable?
- How should invalid values be shown before submit?
- Should submit send all values or only changed values?
- Should the UI refresh from device after submit automatically?
