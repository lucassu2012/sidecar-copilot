# Confirmed UI writes and privacy boundaries

This integration fixes existing frozen v1.5 behavior. It adds no product feature, state branch or PRD test identifier. Production and fixture sources are fixed; the same 33-check runner reproduced six failures on the old fixture target and passed all 33 on the corrected target. Exact artifacts and logs belong in [VALIDATION.md](VALIDATION.md). This is synthetic lifecycle evidence, not a stage exit.

## Source and changes

The independent handoff is `C:\Users\USER\Documents\Codex\2026-11-10\task-5\UI_PRIVACY_HANDOFF.md`, with `ui-privacy-review.zip` in the same directory. Archive SHA-256: `fbc275117e9b007699406fd16dc495e1c6c8ff126c3388843012d332cde18ed1`. Its RecordingActivity baseline SHA-256, `53ebfc231df11f227d42096f2b63ec1299d9db040cc7f982daa821155896db3f`, exactly matched the integration source before these edits.

Two narrow changes in [RecordingActivity](../android/app/src/main/java/local/sidecar/copilot/RecordingActivity.java) were accepted: live updates preserve the pending-operation text while `loading` is true, and an explicitly submitted mutation executes even after page departure invalidates the UI epoch. Late callbacks still require the original epoch and display permission. Existing recovery-tail warnings, retention deadlines, unlock/deletion checks and asynchronous storage ownership remain intact.

The analogous manual-note page is [MainActivity](../android/app/src/main/java/local/sidecar/copilot/MainActivity.java); there is no separate NotesActivity. Its create, append and end operations now use the existing background helper's confirmed-mutation branch. Reads remain cancellable on departure. Store authentication, revision and retention checks still govern each write; accepting a click does not promise a successful commit after permission expires.

## Verification boundary

[UiPrivacyInstrumentation](../android/fixtures/UiPrivacyInstrumentation.java) defines 33 synthetic checks in the separate fixture package. It uses actual page buttons for saving, creating, pausing and ending; blocks the storage executor with a latch; explicitly invokes `callActivityOnStop`; then inspects persisted NoteStore/audio state and checks that late callbacks cannot repaint the stopped page. The pending-save check directly runs the existing live-update Runnable, avoiding a timer-dependent comparison. Other checks cover deletion blocking queued reads, confirmed deletion after departure, notification content and absence of active AudioRecord configurations.

Teardown releases blockers, waits for Activity destruction and all page executors to terminate, then deletes fixture data. Live capture is stopped before cleanup. The initial home Activity is drained before manual-note cases launch a fresh MainActivity, avoiding reuse of an already-top Activity with `startActivitySync`.

Deletion is invoked at the already-confirmed mutation boundary by reflection; the confirmation dialog is not clicked. Explicit lifecycle calls do not establish OEM back-gesture, lock-screen or notification behavior. These checks do not establish real microphone/speaker operation, hardware stopping latency or physical power-loss behavior. A queued write remains in process memory until committed: process death can still lose it, and no durable background operation queue or completion notification was added. Final combined red/green evidence must be read from [VALIDATION.md](VALIDATION.md), not inferred from the independent package or compilation.
