# Abrupt-process recovery checks

**Runtime update:** ED140E29 app plus the one-line corrected native-test APK AD74CF9E passed full Native 1,936 (pending subset 164); the unchanged fixture pair passed cold/warm controls, mixed controls, UI, interruption, OS revocation and FGS, and the unchanged crash pair passed ten scenarios. Original native failures and both Mixed orchestration failures remain preserved. No complete stage/hardware exit is approved. The owned emulator is closed. See [ED140E29_RUNTIME.md](ED140E29_RUNTIME.md) for exact scope, version binding and remaining availability defects. The records below describe their original build-time boundaries.

**Initial host freeze: `ED140E29`, 72 inputs and five signed APKs under `android/build/mixed-recovery-20261110`.** Four builds, signatures and host regressions passed; 61 production classes match their fixture copies. Android execution was unrun at that initial freeze; subsequent results are linked above. Mixed-copy admission, exact-copy cleanup, SQLite connection/writer policy and bounded control diagnostics are detailed in [MIXED_RECOVERY.md](MIXED_RECOVERY.md). No real capture or complete stage exit is approved.

At the initial freeze, the integration round was host-only and reserved the retention window. The new runtime report records the subsequent main-owned window and its release. Historical `873AA7C0` has 48 mixed-control, 16 OS-revocation, 15 interruption and 44 combined-rerun passes. Its first All run failed at the eight-second persistence prerequisite; the cause remains unknown. Mixed/interruption binding uses adjacent-run receipts, not complete independent pre/post package receipts. These results do not validate `ED140E29`; all earlier artifacts and failed attempts remain preserved.


This is implementation evidence for the existing frozen v1.5 recovery, stop and deletion requirements. Scenario names below are test harness selectors, not new PRD test numbers or product states. The harness accepts only an explicitly named disposable `ranchu` emulator, targets the development application and uses generated PCM. It does not open a microphone, speaker or USB device.

## Historical 50216 initial build snapshot

The prior note-file recovery candidate was frozen separately at `android/build/atomic-notes-20261110`: development APK `50216D6B505960C5841F0F82270B0C688A3ED0B8870739C1F837DFB01A0642FE`, crash-test APK `2A60DBD4FA54C5D273C2030A49906FE96A4234D0A2F0677C12295DD9A6553896`. Its 66 inputs and all five signed APK hashes are recorded in `inputs.json` and `artifacts.json`. This slice compiled the existing crash harness but did not run it or install any package. The retention workline's device window was left untouched. None of the historical results below is a pass for this candidate.

For the next ED140E29 bound run, use `android/build/mixed-recovery-20261110/source/run-crash-tests.ps1` and that snapshot's `source/build` APKs, after a fresh window handoff and pre/post installed-package hashes. The new crash-test APK is `2D5D03784ACAB6336E0D21B64F4FAB8DB405B09D4B6387EF977F29D2397091CB`; no crash phase has run against it. Do not rebuild or substitute the root build between binding and execution. New note companion-layout tests are part of native instrumentation; they do not turn the existing audio process-kill selectors into note-write crash or power-loss tests. See [NOTE_FILE_RECOVERY.md](NOTE_FILE_RECOVERY.md).

## Historical 8B frozen candidate

Development APK `8B3E32FFE2629F1A2A3B474B19DF1354F4FA09E5AF4095D133D0E2B565988419` with crash-test APK `52502D063C494E71D96C510FB8193D6B337808EB17AACB56A3363810FDD223A8` passed all 10 existing selectors / 137 verification checks on 2026-11-10. Evidence is in `android/build/privacy-device-runs/20261110T145648Z-Crash-f99010e5`: complete/passed receipt, individual checkpoint and verification logs, installation receipts and pulled device APKs whose hashes match both before and after the run. The 61 source/build inputs and five artifacts remain fixed in `privacy-inputs.json` and `privacy-artifacts.json`.

This rerun includes the new owned-buffer and retained-range-barrier implementation. It establishes these synthetic process-recovery scenarios, not physical power loss, arbitrary snapshot rollback, WAL/flash erasure or real-device acceptance. The owned emulator was normally shut down afterward; `privacy-window-released.json` records release at 14:58:33 UTC. Historical evidence below retains its earlier artifact identity.

## Historical runner invocation

The following records the older integration invocation, not ownership or current credentials. For new verification use the bound candidate and current window handoff above; do not rebuild a frozen candidate in place.

```powershell
.\build-crash-tests.ps1
.\run-crash-tests.ps1 -Serial emulator-5580 -AvdName aneb-api35 -TestPin 2468
```

Those values identified that historical disposable emulator and are not a current assignment, phone configuration or production credential. The runner requires explicit serial, AVD name and test PIN; it verifies the emulator identity and unlocked state before installing. Each scenario records a durable checkpoint and abruptly kills its own instrumentation process without unwinding cleanup. A subsequent process checks recovery. The runner requires both the intended checkpoint and `Process crashed` before accepting a successful verification phase.

| Harness selector | Interrupted boundary |
| --- | --- |
| `before_first_frame` | Samples exist only in volatile assembly |
| `memory_tail` | One committed frame followed by an unsealed RAM tail |
| `after_fsync` | Segment bytes synced before index commit |
| `before_index_commit` | Index transaction has not committed |
| `after_pause_barrier` | Known stop limit committed before sealing finishes |
| `after_pause_finish` | Overflow-driven pause has finished sealing |
| `overflow_pause_barrier` | Accepted limit and rejected-callback gap committed together |
| `deleted` | Durable deletion barrier committed before store cleanup |
| `recovery_transaction` | Recovery transaction itself is interrupted before commit |
| `legacy_active` | Active pre-control-table database is reopened and migrated |

Unknown tails retain their original stream/generation/boot and committed watermark, with no invented endpoint or duration. Durable known limits instead preserve the exact admitted loss and rejected-callback ranges. Reopening is idempotent, does not start capture, and cannot revive deleted keys or control metadata. The legacy scenario covers an active database; it does not prove that already-paused history from every prior version can be reconstructed.

`RecoveryProjectionChecks` additionally tests the guarded metadata getter and the recording page's unknown-tail warning. The page first requires a readable meeting and its remaining NoteStore lifetime; stale page epochs, expiry and deletion still suppress display. Control metadata has no independent production TTL implementation yet. A frame-retention constant must not be treated as a new policy for that metadata.

The source delivery came from `task-2/crash-delivery`, ZIP SHA-256 `8B55886721B0A34F4E5DB70C319AACD99FCE4D15402256D6A068C001287FA2CB`. New harness files were checked against its manifest. Storage changes were applied to the current methods, and existing trusted-clock test fixes were retained. The current mainline adds return-time authorization checks and deterministic deletion races beyond that delivery. Its independent results are not counted as mainline acceptance.

Earlier on 2026-11-10, development APK `5E5572ED3E689742AA84B7FA80287901C504661BD8F836B5C6BCA4F4735AF7F0` passed all ten selectors, totaling 137 fresh-process verification checks. All required crash checkpoints and process-death results were observed. This historical summary is `android/build/integration-final-crash.log`; individual logs are in `android/build/crash-results`.

Complete mainline execution results, test-package identities and failures belong in [VALIDATION.md](VALIDATION.md). Process death is not physical power loss, filesystem rollback, trusted device-reboot recovery or proof of flash erasure. The real-device gate and G2/G3 acceptance remain closed.
