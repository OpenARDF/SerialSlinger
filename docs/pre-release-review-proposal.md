# Proposed pre-release behavior review

This is a proposal for augmenting the existing release workflow, not a change to
its enforced gates. Regression tests, packaging checks, and source review remain
necessary. They do not establish that the whole product behaves sensibly.

The preferred way to collect repeated physical evidence is an automated fixture.
See the [automated hardware bench design](automated-hardware-bench.md) for the
existing hooks, missing harness support, first test procedures, and unattended
diagnose/fix/retest loop. Manual interaction should concentrate on commissioning
and exceptions rather than routine execution.

## Review the candidate as a user

Before release, give a reviewer the candidate builds, representative devices, and
ordinary tasks without the implementation's expected click sequence. The review
should answer whether the displayed information is understandable and whether
every consequential action is explicit. Start in Normal Mode and repeat the
relevant tasks in Advanced Mode on both Android and desktop.

For this change set, use these scenarios:

| Task | Evidence to inspect | Failure to catch |
| --- | --- | --- |
| Configure Fox 4, attach Fox 2, update its firmware, then clone | Template identity/settings before and after; raw writes and readback | Update replacing the clone source |
| Connect during a running event; dismiss each dialog through buttons, Back, and outside taps | User actions, serial commands, transmitter state | Dismissal stopping the event |
| Complete a manual timed run after restarting it | Firmware records, visible outcome, actual stop time | Completion presented as a configuration failure |
| View, select, close, and share a long log repeatedly | File sizes and contents before/after | Log copying itself or losing evidence |
| Move the same cable between transmitters | Full device identities and UI labels | Indistinguishable devices or stale settings |
| Read history in Normal and Advanced Modes | Screenshots; saved single log | Clutter, hidden useful information, or lost diagnostics |

## Compare three independent views of behavior

For each scenario, compare what the user sees, what SerialSlinger sends and
receives, and what the transmitter actually does. A UI success message alone is
not proof. Readback alone does not prove RF output, temperature response, or
power consumption.

Exercise supported software/firmware combinations, both hardware revisions,
legacy firmware where supported, same-cable device swaps, missed replies,
disconnect/reconnect, scheduled boundaries, manual starts/stops, and reset
recovery. Use designated bench devices with known initial settings; restore and
read back the agreed final state.

## Measure performance and recovery

Compare against a recorded baseline for connection/update time, command latency,
UI responsiveness, memory use, log growth, and long-duration operation. Observe
sleep/wake cycles, supply voltage, temperature, and external-power behavior.
Where a change touches low-power or RF behavior, include physical current or RF
measurements rather than inferring them from firmware flags. Select numerical
acceptance limits from product requirements and measurements, not arbitrary
thresholds invented during the release.

## Review the evidence independently

Have a separate review pass inspect the candidate's source diff, screenshots,
chronological logs, and bench measurements. Ask it to find contradictions,
surprising side effects, misleading labels, missing observations, and untested
transitions rather than merely confirm that the scripts passed. The author
should not provide the only assessment of their own change.

Keep anonymized field failures and representative traces as a replay corpus.
Turn each confirmed defect into an appropriate executable regression plus a
user-workflow scenario. Replays can test interpretation and state transitions;
they cannot substitute for fresh physical measurements.

## Candidate evidence and release decision

Attach an evidence report to the exact candidate commit and artifact hashes. It
should list scenarios performed, software/firmware/hardware versions, observed
outcomes, timings, screenshots/log paths, findings resolved, and checks not run.
After a fix, rerun the affected scenarios on the new candidate and the normal
regression gate. Avoid reusing evidence from an older binary as current proof.

The release checklist should eventually gain a distinct behavior-review item
with that report as evidence. A waived physical check remains an explicit
evidence gap even if every software test passes. Where practical, use an
internal bench soak followed by a small opt-in prerelease group before broad
publication; ordinary users should not be the first people to try the complete
workflow.

## What was actually checked for the Session history presentation

This implementation includes formatter tests for manual restarts, separate
runs, missing starts/timestamps, active runs without a recorded end, and thermal
interruptions; both log writers are tested for retaining raw records alongside
the readable summary. A desktop component test checks Advanced Mode visibility
and renders a representative history for visual inspection. Android is compiled
and its log-writing tests run on the host. These checks are not a claim of an
Android on-device UI pass or a physical RF/power test.
