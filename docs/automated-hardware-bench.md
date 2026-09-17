# Automated SerialSlinger and SignalSlinger test bench

Status: design and procedure specification, 2026-09-16. Instrument inventory,
wiring, operating limits, and host compatibility remain to be established.
No bench controller or instrument drivers have been implemented by this document.

Charles can usually provide at least two SignalSlingers and Android devices,
and owns a Siglent SDS2304X. The equipment shortlist and staged acquisition
budget are in [bench equipment recommendations](bench-equipment-recommendations.md).

## Objective

Charles connects a designated fixture once. A local controller repeatedly
builds, installs, exercises, measures, and records candidate behavior. Codex
inspects the evidence, reproduces and fixes defects, and requests another run.
The result is a reviewable candidate and evidence report. Publication remains
a separate release decision.

## Existing foundation and gaps verified in source

- `scripts/android-debug-command.sh` and `AndroidDebugCommandReceiver.kt`
  expose state, settings, trace, log, load, and configuration operations.
  The receiver refuses non-debuggable builds. These hooks do not prove that
  users can complete the same workflow through the release UI.
- `scripts/android-regression.sh` already exercises a connected transmitter.
  It currently uses a fixed-coordinate dialog tap, clears the log during the
  run, changes event configuration, and scans output for known failure text.
  Some settings are restored inline, but it has no general failure cleanup.
  It is a source of reusable operations, not yet an unattended supervisor.
- `DesktopControlServer` in `SerialSlingerDesktopApp.kt` exposes local status,
  device selection/discovery, window control, and raw commands. Extend it
  deliberately; the existing surface does not cover all visible workflows.
- SignalSlinger has host tests for session runtime/storage, thermal behavior,
  and battery/ADC behavior, plus repeatable firmware/package build tasks.
  Serial firmware updates should reuse the existing validated update path.

## Architecture

Codex submits a scenario to a local bench controller. That controller owns the
device connections, operates instruments, collects synchronized evidence, and
returns measurements and a result. Codex can then investigate and modify an
isolated candidate checkout. Instruments and the controller handle precise
timing; model response latency must never control a waveform or cutoff.

Start with a documented command interface and structured result files. An MCP
wrapper can be added later without creating a second implementation. Proposed
operations are discover, verify-fixture, install-candidate, run-scenario,
collect-evidence, stop, and restore-safe-state. These names are interface
requirements, not available commands today.

Use one exclusive bench lease, full device UIDs, explicit instrument identities,
and recorded wiring/channel assignments. Only one transport owner may drive a
given serial link. If a separate Linux controller is needed for instrument
drivers, keep the macOS app under test on the Mac; validate the exact host and
driver combination before choosing equipment.

## Fixture capabilities, in order

| Capability | Purpose | Selection requirements |
| --- | --- | --- |
| Dedicated transmitters and Android device | Repeatable desktop/Android configuration, update, clone tests | Two transmitters for cloning; both supported board revisions for release coverage; charging and USB-host behavior established |
| Programmable USB switching and power/reset control | Reconnect, device swap, reset, and recovery | Data and power routing must be specified; a power-only USB hub does not necessarily simulate unplugging; prevent back-powering |
| Remote-controlled supply and current measurement | Voltage variation, sleep consumption, wake peaks, supply transitions | Appropriate voltage/current range, sampling rate, burden voltage, and current limiting; sink capability or isolation if replacing a battery on a charging circuit |
| RF load and measurement path | Independent evidence of transmission, frequency, power, and timing | Rated dummy load plus suitable attenuation/coupling into a detector, receiver, or scope; calibrated detector needed for absolute power claims |
| Logic analyzer or oscilloscope | UART, keying, LED, reset, and power timing | Documented capture/export API; bandwidth, trigger, voltage, and input protection appropriate to signals |
| Fixed camera and/or optical LED sensors | Visible indicators and physical context | Stable view/lighting; use timestamped optical signals for precise blink timing; screenshots for app UI |
| Temperature stimulus and external sensor, later | Actual thermal cutoff and recovery | Controlled stimulus, independent measurement, bounded temperature, and automatic cutoff |

Choose equipment by remote API and electrical fit, not by a USB connector alone.
Many instruments can be driven through VISA; PyVISA documents opening instrument
connections and issuing queries. Each model still needs its command manual and
supported backend: [PyVISA communication](https://pyvisa.readthedocs.io/en/latest/introduction/communication.html).

Saleae documents automated capture, analysis, export, and save operations:
[Logic 2 automation](https://www.saleae.com/support/extensions-api/automation-api/automation).
Programmable USB routing also exists commercially; Linux Automation describes
Python control and USB OTG testing, listing Linux and Windows support. Treat
macOS support as unverified: [USB-Mux](https://linux-automation.com/en/products/usb-mux.html).
These are capability examples, not a shopping list.

## First automated procedures

Every procedure starts with fixture identification, a known configuration,
instrument self-check, and an evidence capture. Limits and timing tolerances
must come from requirements and bench characterization before acceptance runs.

| Procedure | Automated sequence | Independent acceptance evidence |
| --- | --- | --- |
| Running-event dialog | Start a timed transmission; connect the app; exercise each button, Back, and outside dismissal separately | Only the explicit stop action sends GO 0; RF timing follows the selected action; UI and history agree |
| Firmware update then clone | Load source A; attach B; update B through the app; clone to B | Source identity/settings remain A; permitted settings match on B; unique identities remain distinct |
| Manual finish and restart | Run, stop, restart, and allow normal completion beyond the old saved schedule | Measured RF start/stop times agree with the current run and reported history; no false configuration failure |
| Reconnect and device swap | Disconnect data, reset power, reconnect, and alternate A/B at selected workflow boundaries | B never inherits stale identity/state from A; recovery deadlines hold; no unintended writes |
| Log and history presentation | Use normal/advanced modes; repeatedly view, select, close, and export a long log | Visible fields match mode; one log preserves history; viewing does not recursively increase log size |
| Low-power and external supply | Apply characterized supply states; observe sleep/wake and LEDs | Measured current, optical/keying traces, and external voltage meet requirements, including intentionally switched-off power |
| Recovery and soak | Repeat seeded action sequences and scheduled cycles; inject supported disconnects/failures | No accumulating memory/log growth, stuck state, corrupted settings, or unexplained RF/current events |

Record deterministic seeds and minimize failing action sequences. Explore
different valid operation orders and boundary conditions, in addition to replaying
known failures. Invariants such as "dismissing a read dialog must not stop RF"
help expose failures whose exact appearance was not anticipated by the author.

## Required software additions

1. Replace coordinate taps and fixed sleeps with semantic UI selection,
   observable dialog/action state, completion identifiers, and bounded waits.
   Exercise actual UI actions as well as controller hooks.
2. Add a shared scenario runner with adapters for existing desktop/Android
   controls, serial updates, USB routing, supplies, RF capture, and optical input.
   Reuse existing protocol parsers, settings models, and release validators.
3. Add a simulated transport for repeatable missing, delayed, split, or malformed
   replies using existing transport abstractions. Inject physical faults only
   where the fixture and recovery path support them.
4. Save full pre-test configuration and logs before changes. Always attempt the
   defined final state on success/failure, record cleanup results separately,
   and never erase the evidence that explains a failure.
5. Add instrument self-checks and known reference signals. Detect clipped data,
   disconnected probes, capture gaps, or stale samples as inconclusive results.
6. Expand firmware diagnostics only where existing reports cannot answer the
   question: compact reset causes, transition counters, or fault markers.
   Prefer passive capture; diagnostic polling can keep a device awake.

Injected temperature values or shortened simulated clocks test logic only.
Physical sensor tests and real-time soak remain distinct. Final acceptance must
include the intended release binaries through their ordinary interfaces. Debug
hooks and extra logging must not silently substitute for shipped behavior,
especially when measuring sleep current and timing.

## Unattended correction loop

1. Identify the baseline and candidate artifacts by hashes; record source state,
   hardware revisions, device UIDs, harness version, instrument settings, and
   measurement uncertainty. Save raw logs, screenshots, traces, and measurements
   under one run ID with host/device clock offsets and monotonic timing.
2. Verify the fixture, install the candidate, and execute the selected scenarios.
3. Return pass, product failure, fixture failure, or inconclusive. A successful
   command exit or an absence of known error strings is insufficient for pass.
4. On failure, Codex examines raw evidence, reproduces/minimizes the problem,
   and implements a scoped fix. Where practical, replay on the baseline to
   distinguish a product regression from a failing instrument or harness.
5. Demonstrate the original failure and its correction; repeat affected
   scenarios, ordinary regression checks, and the final candidate acceptance set.
6. Produce a report of changes, observations, unresolved issues, and gaps.

Acceptance limits and safety constraints live outside the repairable candidate
checkout. Codex can propose changes to a faulty test but cannot silently loosen
limits or delete an assertion to obtain a pass. Preserve failed runs and report
flakiness; retries must not erase it. Bound repair attempts, elapsed time, and
usage, and stop for unavailable equipment or unexplained measurement failures.

Codex supports scripted execution and machine-readable output, which can supply
the diagnosis/repair step after its permissions and authentication are configured:
[official non-interactive documentation](https://learn.chatgpt.com/docs/non-interactive-mode).
The bench controller must continue safe monitoring and cleanup if Codex loses
network access, reaches a usage limit, or terminates. This document starts no
background process or scheduled task.

## Physical limits and commissioning

The fixture needs current limiting, bounded RF/thermal tests, a known RF load,
and an independent watchdog/cutoff. Cutting only USB power may leave a battery
powered transmitter running; the fixture must control the relevant power or
inhibit path. A software cleanup handler alone is insufficient if the host hangs.

Commission once with known good and deliberately failing cases. Verify instrument
readings, clock alignment, recovery, probe effects, watchdog behavior, and safe
shutdown. Test brownout/update interruptions only on designated recoverable
devices. Retain an explicit recovery path for firmware that cannot boot; ordinary
updates use the serial bootloader, while programmer-based recovery can change
EEPROM or reset the device and needs its own procedure.

## Implementation stages

First, inventory equipment and define wiring, ranges, and permitted operations.
Build the runner, semantic app controls, evidence bundle, and simulator-backed
scenarios while the fixture is assembled. Next, commission one physical
transmitter with independent RF/current observation; add a second transmitter
and Android USB routing for update/clone/reconnect tests. Finally add thermal
stimulus, broad board/firmware coverage, overnight soak, and candidate-specific
release evidence. Human involvement then concentrates on fixture changes,
calibration, and physical faults that the controller cannot recover.
