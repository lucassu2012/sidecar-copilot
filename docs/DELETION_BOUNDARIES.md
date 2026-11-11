# Deletion persistence and pending-copy boundaries

**Runtime update:** ED140E29 app plus the one-line corrected native-test APK AD74CF9E passed full Native 1,936 (pending subset 164); the unchanged fixture pair passed cold/warm controls, mixed controls, UI, interruption, OS revocation and FGS, and the unchanged crash pair passed ten scenarios. Original native failures and both Mixed orchestration failures remain preserved. No complete stage/hardware exit is approved. The owned emulator is closed. See [ED140E29_RUNTIME.md](ED140E29_RUNTIME.md) for exact scope, version binding and remaining availability defects. The records below describe their original build-time boundaries.

**Initial host freeze: `ED140E29`, 72 inputs and five signed APKs under `android/build/mixed-recovery-20261110`.** Four builds, signatures and host regressions passed; 61 production classes match their fixture copies. Android execution was unrun at that initial freeze; subsequent results are linked above. Mixed-copy admission, exact-copy cleanup, SQLite connection/writer policy and bounded control diagnostics are detailed in [MIXED_RECOVERY.md](MIXED_RECOVERY.md). No real capture or complete stage exit is approved.

At the initial freeze, the integration round was host-only and reserved the retention window. The new runtime report records the subsequent main-owned window and its release. Historical `873AA7C0` has 48 mixed-control, 16 OS-revocation, 15 interruption and 44 combined-rerun passes. Its first All run failed at the eight-second persistence prerequisite; the cause remains unknown. Mixed/interruption binding uses adjacent-run receipts, not complete independent pre/post package receipts. These results do not validate `ED140E29`; all earlier artifacts and failed attempts remain preserved.

## Historical 873AA implementation and initial build record

The frozen v1.5 PRD remains the only requirements baseline. This slice adds internal persistence checks and regression fixtures, not product features, product states or official test IDs. That development APK was `873AA7C00308A553A7C6250DCF1F6D3D93C642D5AAC373D24F19D6D24C8CE4B7`; 71 source/test/manifest/resource/script inputs and five signed APKs are frozen in `android/build/deletion-boundaries-20261110`. The earlier `50216D6B` and root `8B3E32FF` artifacts are preserved.

## Implemented ordering

`SharedPreferences.commit()` can update the process cache before reporting a failed disk write; this follows the fixed [Android 15 implementation](https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/android-15.0.0_r1/core/java/android/app/SharedPreferencesImpl.java). A cached marker therefore cannot, by itself, authorize destructive cleanup after a failed attempt.

`DurableDeletionMarkers` registers an uncertain operation before editing preferences. Failures before or after cache mutation preserve that registration and its original committer. Retrying changes the existing marker value to a fresh token, confirms successful synchronous commit, and only then removes the pending registration. Existing durable Boolean markers remain compatible. Registry locks are not held across preference or callback code; concurrent/reentrant confirmation in the same scope fails without waiting. This is an in-process durability guard, not a new persistent journal or whole-snapshot rollback detector.

Managed note/audio reads, discovery, writes and recovery include both cached and pending markers. UI final callbacks and service polling also consult pending markers through already-loaded preferences, without filesystem resolution or persistence on the UI thread. A marker hides data even when the failed edit never reached the cache. Whole-meeting cleanup, note-body filtering and audio purge still require confirmed durability before their destructive side effects. Constructor failures, including recoverable Errors, release audio database/file-lock resources and retain cleanup failures as suppressed exceptions. The focused native storage checks remain unrun in this record; later 873AA UI subsets and their limits are stated above. They do not close storage or current-candidate acceptance.

## Exact pending-copy handling

An isolated `.enc.new` is never promoted to a saved meeting. Maintenance may authenticate it only after the existing unlock and trusted-clock gates. `PendingNotePolicy` accepts only the complete known manual-note schema with valid original retention envelopes; missing, unknown, future-dated or unsupported content is retained and reported. It does not invent missing metadata or extend deadlines.

For an authenticated copy whose title, attachments and container are all expired, maintenance writes a durable internal marker keyed by meeting ID, byte length and SHA-256 of the entire encrypted copy. It rechecks layout and bytes, cancellation and unlock before unlinking that exact pending file. Interrupted retries with an already-confirmed exact marker need no plaintext, clock or key access. Both note and audio aliases are retained because they may protect a newer valid version or associated audio; this operation does not count as a completed meeting expiration or claim cryptographic erasure.

Normal reads check that exact-copy ledger before AtomicFile can restore/discard companions, and check the actual returned bytes again. Returning the marked ciphertext under `.enc`, `.bak` or `.new` cannot silently readmit it while the ledger survives. Different ciphertext is not covered by that marker. The shared privacy lock protects managed writers; these checks do not promise atomic protection against a privileged process replacing files outside the application.

Unknown, partially unexpired, unverifiable or unsupported pending copies retain their ciphertext and keys and report an incomplete operation. This can still block ordinary page initialization; a reachable user recovery/disposal flow is unfinished. Do not equate API refusal or a logged error with cleanup. Earlier accumulated I/O failures are retained if a later authentication, cancellation or other terminal failure stops the sweep.

## Backup and WAL limits

The application and synthetic fixture retain `allowBackup=false` and `fullBackupContent=false` and explicitly exclude all nine supported domains from both cloud backup and device transfer using packaged `dataExtractionRules`. Android documents that `allowBackup=false` alone may not disable device transfer on some manufacturers' devices; see [Android backup rules](https://developer.android.com/identity/data/autobackup). Both final APKs were inspected with aapt2: manifest references resolve to the resource, and each destination contains nine root-path exclusions. This is build verification, not an OEM transfer test or deletion of prior backups.

WAL configuration and checkpoint behavior were unchanged in 873AA. ED140E29 changes the connection/writer configuration described in MIXED_RECOVERY.md; checkpoint behavior remains unchanged. Current managed range barriers remain in force, but no new normal-crash WAL bypass was established. Restoring the entire database/preferences/ledger snapshot, including its tombstones, is not prevented by this slice. Exact-copy unlink, a missing managed alias, or a successful SQLite transaction is not evidence of flash, WAL, backup or physical erasure. The explicit platform experiment described in [NOTE_FILE_RECOVERY.md](NOTE_FILE_RECOVERY.md) is still required.

## Historical 873AA initial evidence and remaining run requirements

| Evidence bound to the 873AA initial inputs | Observed result |
| --- | --- |
| Four build steps / five signatures | Passed; 61 production classes byte-identical in fixture; fixture classes excluded from development APK |
| Existing fixed JVM policy/lifecycle | 257 passed |
| Queue | 18,413 assertions and 200 stop races passed; synthetic JVM |
| File-family helper | 364 real host-file assertions passed; symbolic-link capability group skipped because Windows refused creation |
| Marker helper | 92 host-model assertions passed; source/stub/harness hashes and commands preserved |
| Note deletion red/green | Unchanged 49 safety checks plus separate 29 pre-cache-failure checks passed across seven JVM phases; old baseline failed at both expected assertions |
| Pending retention policy | 161 pure-policy host assertions passed |
| Host log-sharing fix | 22 checks passed with real background writers; old conflict reproduced; original All failure receipt retained |
| Final backup resource packaging | Both APKs have verified manifest/resource binding and all 18 exclusions; runtime not tested |
| Native, FGS, UI, retention and crash suites at initial freeze | Compiled, not run at that freeze; later UI subsets are recorded above |

The host red/green evidence binds its final eight production files in `android/build/barrier-cache-repro/final/final-snapshot.json`; the result JSON alone happens to match an intermediate run and is not a version identifier. It uses real JDK encryption/files with disclosed synthetic Android interfaces and a Windows AtomicFile adaptation; it does not test AudioSegmentStore, SQLite/WAL, Android Keystore or power loss. The independent slow-line 104-check candidate is not used as a pass for this implementation.

Native focused entries are `atomic_notes_only` (98 planned), `deletion_markers_only` (98 planned) and `pending_copies_only` (89 planned). The fixture additionally compiles `phase=mixed_controls` (48 planned) for four page/notification stop interleavings; the host runner's `All` mode does not run that phase. These were planned assertions at the 873AA initial freeze; the subsequent mixed-controls result is recorded above. ED140E29 uses different focused test counts documented in MIXED_RECOVERY.md. The host runner's sharing fix and default file-only validation passed, but its complete device orchestration still needs execution.

The historical `50216D6B` UI evidence now includes 44 combined checks and 16 independently orchestrated OS-revocation recovery checks, with matching installed APK hashes. Its original All script remains failed/incomplete due to the recorded host log-sharing conflict. Neither result validates `873AA7C0`.

The retention line currently owns the exclusive window; after a fresh handoff, use a coordinated, explicitly owned disposable-emulator window and bind source, all five APKs, installation, raw logs, terminal receipt and post-run package hashes. Existing T01/T07/T08/T22/T29/T32/T36/T37 still require the applicable subsets: mixed controls; OS revocation followed by renewed consent for the same meeting without automatic recording; actual offline/airplane-mode conditions; interruption and cause presentation. The renewed-consent supplement is not yet encoded; interruption causes currently remain generic in the page. Actual USB/Bluetooth disconnects, AudioRecord routing, microphone privacy switches, call/other-recorder arbitration and OEM behavior require authorized physical hardware. G0/G1/G2/G3 full exits and real capture remain closed.
