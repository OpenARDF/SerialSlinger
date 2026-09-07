# Session status and recovery

With SignalSlinger firmware 2.0.4, the event status uses device-recorded outcomes. An overheating pause, a user interruption and a completed session are distinct. A schedule whose end has passed without a recorded finish is expired with completion unconfirmed. Completion describes the last session, never an assumed successful run on every configured day.

SignalSlinger defaults to thermal protection enabled at 65 C, including devices without a saved choice. A scheduled session may resume after cooling within its original window. Cooling does not extend the finish time; later scheduled days remain available. Disabling thermal protection is an explicit operator choice.

Android shows recent history in Device Status and includes it in exported device summaries. On desktop, hover over Event Status for recent history. Both show the latest applicable stop reason and device timestamp in the event status. Normal serial logs retain the complete received records.

The device retains seven recent records across resets. Earlier history may have rotated out. An unexpected power loss or reset has an unknown stop time; the app does not substitute the time the phone or computer read the log. Times are displayed in the computer or phone's local timezone.

Older firmware remains supported. A past finish time alone is no longer presented as proof of completion, and an explicitly interrupted event cannot appear running merely because its calendar window is open. Runtime outcomes are cleared when the device identity or schedule changes; the history stays associated with its original device and schedule.

The versioned EVT records are parsed and merged in shared code for Android and desktop. Regression coverage includes malformed reports, repeated refreshes, empty history, identity and schedule changes, reset uncertainty, thermal pauses and confirmed versus inferred outcomes. The firmware protocol is documented in SignalSlinger's `Software/AVR128DA28/release-notes/session-recovery-protocol.md`.
