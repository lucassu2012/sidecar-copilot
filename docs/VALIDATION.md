> 当前候选 **F134E5D4** 已完成 API35 合成 Android 回归：Full Native 2031、UI64及存储/控制/撤权/FGS/进程终止套件通过；首次锁屏前置拒绝保留。自有AVD已关闭，17:38:18 UTC复查无模拟器进程。实机与G2/G3仍未验收。见 [本轮运行证据](F134_RUNTIME.md)。

# Validation record

**Runtime update:** ED140E29 app plus the one-line corrected native-test APK AD74CF9E passed full Native 1,936 (pending subset 164); the unchanged fixture pair passed cold/warm controls, mixed controls, UI, interruption, OS revocation and FGS, and the unchanged crash pair passed ten scenarios. Original native failures and both Mixed orchestration failures remain preserved. No complete stage/hardware exit is approved. The owned emulator is closed. See [ED140E29_RUNTIME.md](ED140E29_RUNTIME.md) for exact scope, version binding and remaining availability defects. The records below describe their original build-time boundaries.

## Current candidate: mixed recovery and writer policy — ED140E29

Development SHA256 `ED140E2986AD48D5EC6D4DEFCC2FCEF28F9B0DFCCFBE3DF0739334C4F2B3934A`. The candidate directory `android/build/mixed-recovery-20261110` fixes 72 inputs, including the WAL test source and both current diagnostic fixture sources. `snapshot.json` lists 11 changed/added inputs against 873AA. Four builds and five signatures passed; all 61 production classes match their fixture copies, and the development JAR excludes fixtures. Both app/fixture backup resources resolve to 18 exclusions. Previous 873AA inputs/APKs and root 8B APKs were rehashed unchanged.

Source-bound host results: 257 fixed policy/lifecycle checks, 16,639 bounded-queue assertions with 200 stop races, 434 real-file assertions (one unavailable Windows symlink capability group skipped), 234 mixed-layout checks over 12 JVM phases, 49 original marker regression checks plus 29 pre-cache-failure checks, 92 unchanged helper-model checks, 161 unchanged pending-policy checks, 22 unchanged host-log checks, and 76 diagnostic host checks with 13 static checks. The identical mixed harness produces four expected failures on frozen 873AA, with no unexpected errors. These different scopes are not summed as acceptance. Host SQL verifies feasibility only; its initial count metadata typo was preserved and corrected to seven actual assertions.

The new atomic-note 100, pending-copy 164, deletion-marker 98, WAL-configuration 74 and mixed-control 48 assertions are **planned and compiled, not Android runtime passes**. No device was installed, no recorder enabled, and no window taken from the retention line. All native/FGS/UI/retention/crash regressions require new candidate binding after the resource window is released. FULL and transaction-bound secure_delete still require Android API 30/35/OEM validation. No checkpoint, physical-erasure or full-snapshot rollback success is implied. See [MIXED_RECOVERY.md](MIXED_RECOVERY.md) and the candidate's `DIAGNOSTIC_RETEST.md`.

Historical 873AA subsequently produced 48 mixed-control, 16 OS-revocation, 15 interruption and 44 combined-rerun passes. Its first All run failed at the unchanged eight-second BLOCK_APPEND prerequisite before END; the cause is unknown, and its failed receipt remains. Mixed/interruption logs lack complete individual command/time and immediately adjacent package receipts; neighboring-run binding does not fill that gap. No historical result is inherited by ED140E29.

## Historical 873AA candidate: initial host verification record — 873AA7C0

The complete development hash is `873AA7C00308A553A7C6250DCF1F6D3D93C642D5AAC373D24F19D6D24C8CE4B7`. Candidate `android/build/deletion-boundaries-20261110` contains 71 frozen inputs including XML resources and all registered new test sources, four successful build logs, five signature results and full APK hashes. All 61 production classes are byte-identical in the fixture; development excludes fixture classes. Previous 50216 source/APKs and root 8B APKs were rehashed unchanged.

Current host results: 257 fixed policy/lifecycle checks; 18,413 queue assertions with 200 stop races; 364 real-file assertions (one unavailable symlink capability group skipped); 92 deletion-helper model checks; 49 unchanged red/green note checks plus 29 distinct pre-cache-failure checks; 161 pending-copy policy checks; 22 host log-sharing checks. These suites exercise different scopes and are not summed into acceptance. Both final app/fixture backup resources and the runner's default offline manifest/hash verification passed. The old independent 104-check repair is not this implementation's evidence.

At the original host freeze, all Android suites were **compiled, not run on 873AA7C0**. Later UI evidence is recorded above; native storage and other suites remain unverified. In particular, 98 atomic-note, 98 deletion-marker, 89 pending-copy and 48 mixed-control assertions are planned only. `mixed_controls` requires its own fixture phase; All does not invoke it. No current-candidate device installation, actual microphone, OEM transfer, power-loss, whole-snapshot rollback or physical-erasure result exists. See [DELETION_BOUNDARIES.md](DELETION_BOUNDARIES.md) for source bindings, old failure preservation and the exact next-window plan. Real capture and complete G0/G1/G2/G3 exits remain closed.

Historical 50216 evidence was subsequently extended by the independent UI line: 44 combined checks and 16 same-package OS-revocation recovery checks passed with installed APK binding (`task-5/CANDIDATE_UI_FINAL.md`). The original All script failed/incompleted before revocation due to a host log-sharing conflict, and that receipt remains failed. Independent revocation is not a rewrite of that receipt. The initial work-slice record below predates those runs and is retained as history.

## Historical 50216 candidate: initial host verification record

Development APK `50216D6B505960C5841F0F82270B0C688A3ED0B8870739C1F837DFB01A0642FE` is built from 66 frozen source/test/manifest/script inputs in `android/build/atomic-notes-20261110/inputs.json`. All five APKs are under that directory's `source/build`; `artifacts.json` contains their complete hashes and signature results. All four build steps passed. The fixture contains 54 byte-identical production classes, and the production JAR excludes fixture classes. The prior root-build APKs and `privacy-*` evidence remain unchanged.

The change checks note-file commit/read/delete postconditions, discovers backup and pending-only layouts, and adds a scoped maintenance entry so an old cleanup I/O failure cannot skip independent due notes. Known deletion barriers are processed before clock-dependent body reads. A lone `.new` still retains ciphertext and its alias and reports an unresolved I/O error; it is not promoted, erased or claimed unrecoverable. See [NOTE_FILE_RECOVERY.md](NOTE_FILE_RECOVERY.md) for implementation and WAL boundaries. Audio storage and WAL configuration are unchanged.

| Validation on this source | Actual result and limit |
| --- | --- |
| Existing fixed JVM policy/lifecycle checks | 257 passed |
| Real temporary-file helper checks | 144 passed; host files only |
| Bounded queue | 19,571 assertions / 200 stop races passed; synthetic JVM |
| Current NoteStore host comparison | 291 checks / ten scenarios / 32 independent JVM phases passed, against `84F46647` + `46D79605`; Windows AtomicFile adaptation and simulated Android interfaces, not Android execution |
| Control orchestration | 21 offline checks passed; the actual frozen source and five APK hashes also passed default offline validation with an intentionally absent SDK path |
| Android native, FGS, UI, control combination, OS revocation, diagnostic and crash suites | **Compiled, not run** on this candidate; no device installation or runtime pass is recorded |

The independent host evidence is `android/build/atomic-host-current/README.md` and `CURRENT_RESULT.json`; it does not directly execute the new static-maintenance entry. The 98 planned `AtomicNoteFileChecks` assertions, including maintenance continuation/cancellation and clock ordering, remain unrun on Android. Host failures/returns are not physical erasure, hardware authentication, power loss or trusted-reboot acceptance.

`ControlUiInstrumentation` is now included with the three existing fixture runners. It explicitly removes and verifies absence of the stopped-source warning, tests leaving/returning without restarting, and records a durable PID/boot/start-time/random-token identity across OS revocation. `run-control-tests.ps1` binds source, five APKs, installation and post-run APKs; the real revoke command and prepare/verify receipts are implemented but **have not been executed**. Default invocation is file-only. Keep the original 33 UI checks and 12 diagnostics; neither replaces the control combination or actual OS permission revocation. Supplemental triggers map to existing T01/T07/T08/T29/T32/T36/T37, with no new official identifiers.

The retention workline has reported its 5594 window released at 15:24:54 UTC; the next device run must record its own serialized window and candidate binding. This slice did not start, inspect or operate any device, alter host virtual memory, publish or deploy. All G0/G1/G2/G3 full exits and the physical-capture gate remain unapproved. The following device results belong only to their stated historical APKs.

## Historical 8B candidate: owned-buffer cleanup and retained range barriers

Later independent retention evidence is in `task-3/anchor-frozen-8b3e32ff-20261110T150805Z/RESULT.md`. It reports two expected process terminations, zero unauthorized saves, and 26 recovery / 7 durable-write / 55 reboot-isolation-and-deletion / 25 deleted-reopen checks passed. Its independently signed app is `CB330D93C03D3D59F2F574E0EB550A3B2BF7FD261A929F1BBE158E3F313D18AA`, paired with test APK `3DD24D3ADB410FE9AD93180CB506D4F3451FCA4E12E9927B4CD1D3D633F52F7D`. Manifest, DEX and resources match the historical 8B APK byte-for-byte; signatures differ. This is separately bound 8B execution-payload evidence, not a run of the original signed APK or the new note-file candidate. The receipt confirms its owned 5594 shutdown; normal emulator reboot and modeled preference failures are not physical power loss or actual media-write failures. Counts are not added to the mainline suites.

Development APK `8B3E32FFE2629F1A2A3B474B19DF1354F4FA09E5AF4095D133D0E2B565988419` includes the stopping-notification fix below and two storage changes. Audio append now clears its owned plaintext and nullable DEK across key-generation and encryption failures; note write covers length rejection and all key/cipher preparation. Direct audio read owns DEK, clear bytes and decoded PCM until its cursor closes and final admission succeeds. Failure, including a recoverable `Error`, clears unreturned PCM; successfully returned PCM remains the caller's responsibility. Test-only probes retain exact owned array references for element-by-element assertions. This does not erase immutable JSON/String objects, provider/key-object copies, driver buffers or arrays already delivered to callers.

Existing range tombstones now replay before recovery, explicit compaction and cleanup. Read, final replay admission, frame listing and coverage independently exclude intersecting half-open ranges. The internal `deletions_by_stream_end` index supports those lookups, and replay skips already fully revoked rows to avoid unnecessary WAL writes. There is no new table, product state or PRD test identifier. This defends the artificial case of an old live frame row returning while its current tombstone survives; normal SQLite commits already update both atomically. No normal-crash resurrection or protection against rollback of the tombstones themselves has been demonstrated.

| Current-candidate validation | Actual status |
| --- | --- |
| Five APK builds, signature verification and production fixture exclusion | Passed |
| Fixed JVM policies/lifecycle | 257 passed |
| Bounded PCM queue | 21,159 assertions / 200 stop races passed |
| Actual candidate SQL on host SQLite 3.50.4 | 14 checks passed; synthetic rows only |
| API 35 native regression | 1,500 passed, including 320 owned-buffer and 120 range-barrier checks |
| Synthetic foreground service | 230 passed, including stopping-notification action removal |
| Confirmed-write UI lifecycle | Clean-fixture run: 33 passed, zero failures; earlier contaminated run retained as failed |
| Named interruption diagnostic | 12 passed in one attempt; input/service/page/warning stages all completed |
| Abrupt process recovery | All 10 scenarios / 137 checks passed; process termination, not device power loss |

`android/build/privacy-production-inputs.json` fixes 24 production/build inputs, and `privacy-inputs.json` freezes all 61 current source, manifest and build/run inputs. Both were verified unchanged after compilation. All five complete APK hashes and successful signature checks are in `privacy-artifacts.json`; `privacy-class-binding.json` confirms the fixture contains byte-identical copies of all 53 production classes, while the production JAR excludes fixture classes. `privacy-build.log`, `privacy-test-build.log`, `privacy-fixture-build.log` and `privacy-crash-build.log` retain the host results. `privacy-range-sql-probe.py/.json` executes the source's actual SQL against in-memory synthetic rows, including a failing transaction, touching boundaries, other session/stream, repeat replay with zero changed rows and an index query plan. That host probe alone does not execute Android SQLite or Keystore. All five previous 8015 artifacts were hash-verified and preserved under `android/build/pre-buffer-range-8015` before replacement. Actual new-candidate installation receipts, pre/post-run pulled device APK hashes and raw results are retained in `android/build/privacy-device-runs`; all five successful suites match the frozen artifacts before and after execution.

The independent storage review in `task-2/revocation-risk-review/RISK_REVIEW.md` confirms the two buffer omissions and limits WAL evidence to host synthetic markers. Current-row `wrapped=NULL` and read denial do not prove historical WAL/main-database pages or old authorized encrypted copies cannot be recovered. This candidate does not add a checkpoint/truncation protocol or claim physical erasure. Android WAL/connection-pool cleanup, AtomicFile auxiliary-copy/rename failures, backup and complete-snapshot rollback remain separate unverified boundaries. Never manually delete a live WAL to claim cleanup.

The fixed local handoff is `android/build/privacy-frozen-8b3e32ff-20261110.zip`, SHA256 `A39ECC4F76239376F2AE1D800E83E1BFD76D844C7AB677A65B47E13280DA261E`. All 82 archive members were compared byte-for-byte: 61 source/build inputs, five signed APKs and 16 manifests/logs/probes. The archive contains no signing private key or reconstructed PRD. `privacy-frozen-archive.json` records its identity. Its `privacy-candidate.json` is the earlier host-only freeze receipt, so its installation list correctly remains empty; later actual device results are separately recorded in `privacy-device-summary.json` and per-run receipts rather than rewriting that archive.

The new `InterruptionDiagnosticInstrumentation` separates input-stop (2 seconds), service-stop, paused-page and warning-arrival stages (8 seconds each), saves evidence before cleanup, and does not open an audio store during observation. Its single current-candidate attempt passed all 12 checks, reaching the warning stage at about 5.1 seconds from runner start. This does not establish the root cause of historical timeouts or prove that every scheduling interleaving succeeds.

The first UI run after FGS (`20261110T145355Z-UI-5787682e`) crashed before foreground promotion. FGS intentionally ends by lowering `sidecar_capture` to `IMPORTANCE_NONE`; reinstalling with `-r` retained that state, while the UI runner issued a ticket with hard-coded notification readiness. The saved notification dump confirms importance 0, making the production visibility gate false. This run remains failed, with crash log, post-failure device hashes and reset receipts. Only the two owned synthetic fixture packages were uninstalled, then the same frozen APKs were installed cleanly; the subsequent UI run passed 33 checks and the notification dump confirms importance 2. No production code, permission gate or timing threshold was relaxed. Independent task-5 permission-revocation/40-check control results still belong to its own older build and are not counted as new-candidate coverage.

Successful run directories are `20261110T145119Z-Native-81003422`, `20261110T145316Z-FGS-5715ad51`, `20261110T145546Z-UI-b985d3ad`, `20261110T145615Z-Diagnostic-a9a8d512` and `20261110T145648Z-Crash-f99010e5`. The bound-run helper verifies frozen source/artifacts, saves install Success receipts, pulls both target/test APKs before and after each suite, and preserves full output and command exit status. Its adaptation from the acceptance workline is recorded in `privacy-bound-run-provenance.json`.

The device evidence archive is `android/build/privacy-device-evidence-8b3e32ff-20261110.zip`, SHA256 `FCDA18D0389462ED126CC18BB4800DE5209188CD3D3330ABB4E0E5760B54D624`. All 562 members were verified byte-for-byte; it retains the five passing run receipts and the failed initial UI run, raw logs, actual pulled APKs, source copies, supplemental channel/reset evidence and window-release records. `privacy-device-evidence-archive.json` and `privacy-device-summary.json` provide machine-readable indexes. Counts across overlapping suites or historical/independent builds must not be added into a new acceptance total.

After UI released 5596, the integration owned the only active emulator: 5580 / aneb-api35, launcher 65908 and qemu 75152. The initial launch failed because the shared AVD referenced a missing default image; logs were retained, and a read-only retry explicitly selected the existing API 35 Google APIs image without editing the AVD. After all tests, no Sidecar service/process remained and the owned instance was normally shut down. `privacy-window-released.json` records 2026-11-10 14:58:33 UTC, no remaining emulator process and release to the retention workline. UI's 5596 and the physical phone were never operated by the integration.

## Previous 8015 follow-up: stopping-notification actions — host checks only

The previous development APK is `8015B31702493BB007DC3F55D4213B07B38EE65E2857363707FE92011E5A2A53`. In that snapshot, only `CaptureService.shutdown` changed in production after the completed `5E5572ED` snapshot below. A newly constructed stopping notification now has its adopted `actions` cleared before rebuilding; input-stop ordering, storage completion, exception handling and the capture gate are unchanged.

The independent API 35 control UI run observed the stopping title with two stale Pause/End actions. This matches the [Android 15 builder implementation](https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/android-15.0.0_r1/core/java/android/app/Notification.java): adopting a notification retains its action array, while an empty builder list does not overwrite that array during build. This is a confirmed interaction inconsistency; the service already rejects repeated controls while stopping, and it is not evidence of restarted capture. The follow-up fixture adds local construction checks and zero-action assertions during blocked pause/end sealing, without adding PRD IDs.

| Follow-up validation | Actual status |
| --- | --- |
| Development, native-test, fixture and crash-test builds | Passed |
| Fixed JVM policies/lifecycle | 257 passed |
| Bounded PCM queue | 14,689 assertions / 200 stop races passed |
| APK v3 verification and production-JAR fixture exclusion | Passed |
| New APK device regression, including action-removal checks | **Not run yet** — serialized device window pending |

Sources/build inputs and all five artifact hashes are fixed in `android/build/notification-inputs.json` and `notification-artifacts.json`. Logs are `notification-build.log`, `notification-test-build.log`, `notification-fixture-build.log` and `notification-crash-build.log`. The later three builds used process-local JVM heap/metaspace limits, not system virtual-memory changes. The completed old APKs remain in `android/build/pre-notification`; their results below must not be relabeled as new-APK passes.

Resource coordination: the earlier owned 5580 instance was shut down. For this follow-up, the integration started a new **owned** `emulator-5580 / aneb-api35`, launcher PID 61316 and qemu PID 33864, limited to two cores and 2,048 MiB. `notification-emulator-process.json` records ownership. It is not an unrelated project instance. No follow-up instrumentation was started before the other workline requested an exclusive window. On 2026-11-10 at 14:32:38 UTC, ownership was rechecked, the package query confirmed no Sidecar package was installed, and all five host APK hashes were verified. Thus no Sidecar fixture could be running; the attempted instrumentation-specific dumpsys selector itself was unsupported and is not evidence. The owned instance was then shut down normally; `notification-safe-shutdown.json` and `.log` retain the receipt. Only the UI workline's 5596 remained, and it was not operated. Follow-up device regression awaits its window. The physical phone remains untouched.

The separate `task-5/CONTROL_UI_HANDOFF.md` is incomplete: 30 PASS observations, one explicit action-removal failure, then an unlocalized input-interruption timeout. Its three candidate waits are service destruction, paused-page rendering and warning-notification arrival. Runtime permission-revocation preparation was recorded but revocation/verification had not completed at that checkpoint. These cases are not covered by the successful older suites below and remain open. The next device run must verify this notification fix, separate those waits diagnostically, and complete the existing revocation scenario before claiming that coverage.

Supplementary storage evidence: `task-2/expiry-fix/HANDOFF.md` reports 455 independent checks, with two actual process-termination/recovery pairs. Its fixed production manifest matched all 23 production files of the completed `5E5572ED` snapshot; the four audited storage/time components are also unchanged by this notification-only follow-up. Six log hashes and both expected crash checkpoints were verified. Its APK `A267D3A7...F53CE7` is a different artifact, so these 455 checks are not added to mainline totals and do not establish physical erasure, power-loss or real-device acceptance.

## Completed 5E candidate: expiry revocation, recovery and confirmed writes

2026-11-10. Source and build inputs are fixed in `android/build/integration-final-native-inputs.json` and `integration-final-fixture-inputs.json`; artifact hashes are in `integration-final-artifacts.json`. Frozen v1.5 requirements, priorities and official test identifiers are unchanged. No complete G0/G1/G2/G3 exit is claimed.

| Current suite | Result | Evidence boundary |
| --- | --- | --- |
| JVM policies/lifecycle | 257 passed | 176 policy/clock + 13 framing + 22 start gate + 30 source lifetime + 16 retention envelope |
| Bounded PCM queue | 28,925 assertions / 200 stop races passed | Generated PCM; assertion total depends on scheduling |
| API 35 development-package regression | 1,060 checks passed | 499 buffered + 43 controls + 107 cleanup + 89 clock + 32 anchor + 41 recovery projection + 21 read completion + 27 notes + 38 PCM + 53 meeting + 23 service + 12 audio retention + 11 note concurrency + 18 replay + 31 note retention + 15 UI response |
| Confirmed-write UI lifecycle | 33 passed, zero failures | Old target had six semantic failures; both fixed fixture builds passed the same runner |
| Synthetic foreground service | 224 checks passed | Original 198 plus 26 deterministic held-store checks; historical timeout retained below |
| Abrupt process recovery | All 10 scenarios / 137 checks passed | Checkpoint, actual process termination and fresh-process verification; not device power loss |
| Isolated UVC simulation | 555 assertions and two original concurrency reproductions passed | Main-worktree module, outside APK; Android discovery only compiled against API 35 |
| Python regression | 46 passed in 35.63 seconds | Existing run in this integration; Python source unchanged by this Android slice |

### Corrections and direct evidence

- Expiry now commits new tombstones and removes wrapped DEKs before attempting historical physical cleanup. The old C49 APK failed `integration-expiry-red.log` at cleanup assertion 58: injected old compaction failure left an independent authentic expired frame's key wrapped. The final 107-check suite covers both compaction I/O failure and corrupt surviving ciphertext, repeated retries, unchanged surviving metadata/ciphertext, cancellation before and after commit, and no duplicate revocation. It checks SQL directly before any read can revoke an expired frame. Uncertain time still permits only previously authorized cleanup, never new expiry.
- The retention job uses a scoped maintenance operation: acquire the exclusive writer lock and database, adjudicate expiry, then reclaim ciphertext. The unrecovered store is never returned to a caller. Ordinary constructors still require full recovery. A persistent orphan-cleanup fault demonstrates that ordinary open refuses access, while maintenance first revokes the due key and then reports the same cleanup failure. Normal reopening preserves unknown crash-tail metadata and idempotent generation recovery. This does not promise revocation when the database itself cannot open or commit.
- The note anchor remains pending until its original provenance and a fresh persistence nonce commit successfully, including SharedPreferences cache-first failure. Older code accepted cached nondurable provenance; the targeted corrected run passed 32 checks and the same component passed in the final 1,060-check run. Independent process/reboot-model evidence is supplementary, not real power-loss acceptance.
- Queue completion survives repeated identical exceptions, terminal `Error` and consumer interruption. It preserves the original failure, completes stop signaling, zeros owned PCM and waits for actual worker ownership to end. Deterministic tests cover the former self-suppression hang and cancellation racing startup. No claim is made that a VM unable to execute cleanup can recover.
- Recovery and pause completion recheck meeting deletion before writing controls/gaps. Audio authorization is checked again before returning plaintext. The unknown-tail UI is gated by a readable meeting, its remaining note lifetime, page epoch and deletion state; it never invents an end sample or duration.
- Confirmed recording-page and manual-note mutations no longer disappear solely because navigation invalidates a page epoch. Late UI results remain suppressed and storage still enforces access, revision, expiry and deletion. Recording refresh preserves the pending-save text. A queued operation is not promised durable across process death before commit. See [UI_PRIVACY.md](UI_PRIVACY.md).
- The same final UI runner produced 33 checks / six failures on the old fixture APK `277B72F58610A9095F08532A1879533F24D0A5CC3AA5ADAA8EAA30FAA33FC349`, then 33 / zero on the fixed fixture. Failures were the queued meeting-note save, pending-status refresh, two manual-create assertions, manual append and manual end. Both targets retained the privacy-display and stop-control assertions. Logs: `integration-ui-red.log` and `integration-final-ui.log`. The separate workline's additional 40-check manual UI suite is not counted here; its candidate differs only in comments, but its extra negative UI combinations were not rerun on this fixed artifact.
- The final native UI fixture waits for Activity destruction and all owned executors to terminate before opening another writer. Its earlier teardown raced a background worker and failed on the exclusive file lock; the repaired targeted 15-check run and final native run passed without changing production ownership or stop behavior.
- The first final FGS run failed at check 16 while awaiting the second start broadcast. The preceding page used `finish()+waitForIdleSync()`, which does not guarantee that a storage worker has released the writer lock. Its generic interruption notice did not preserve the original exception, so the historical failure has no uniquely established cause. It remains in `integration-final-fgs-first-failed.log`. The revised fixture records original open failures and drains page workers before direct store reads or another start. A deterministic page-worker lock holder proves that legacy finish/idle leaves ownership alive: the service reports the actual lock refusal, starts no source and sends no success broadcast; explicit release/drain permits a fresh successful start. The revised 224-check FGS suite passed (198 original plus 26 mechanism checks). Its maximum blocked-write software stop-dispatch observation was 6 ms against the unchanged 2,000 ms fixture bound; this is not a hardware stopping result. Observer waits remain eight seconds; the deliberately held test lock has a separate 30-second fail-safe and is explicitly released in `finally`. Production code and gates were unchanged.
- The abrupt-process harness passed all ten selectors documented in [CRASH_RECOVERY.md](CRASH_RECOVERY.md), totaling 137 verification checks. Each expected kill reached its checkpoint and the runner required `Process crashed` before accepting the new process's verification. `integration-final-crash.log` and `crash-results/` contain the actual mainline evidence. The executor briefly disconnected before the first launch; no process was created. After environment recovery, the app hash and emulator were verified before a single successful launch.

### Candidate identity and remaining boundaries

| Built artifact | SHA-256 |
| --- | --- |
| `sidecar-dev.apk` | `5E5572ED3E689742AA84B7FA80287901C504661BD8F836B5C6BCA4F4735AF7F0` |
| `sidecar-tests.apk` | `C86F7A8B675B3322BD56CBB669DAAAF8EB73878FC16D2020639D60487C29FE7A` |
| `sidecar-fixture.apk` | `2505ABEFB9CE329C8C723CEEEE38EE86C6522307DDEB724DD360FE400E0D8D9C` |
| `sidecar-fixture-tests.apk` | `AA639F4C484AE4F96D44A50D8CAD3EFA0CF6CBF4C18EB8A6CF53D85E7CD54B5D` |
| `crash-tests.apk` | `CE6A0C2BD32F7BFD1960663EF8B8082CF0B08CBB236030FD5B3C9F374A6EB86E` |

Builds and APK v3 signature verification passed with existing Java source-8/deprecation warnings. Native evidence is `android/build/integration-final-native.log`; JVM evidence is `integration-final-build.log`. An initial red-test launch raced instrumentation compilation and therefore used the previous test artifact; that disposable run was stopped and rerun after build completion. Its interruption is not a product-test result.

The original fixed UI comparison used fixture APK `955E0CFA1FBB3912A2536BCB51542C74B07E7F85388B6552D14854ABBDE5549D` and test APK `5EC5C9A9B26168A3EDEFAEBF5453CBCFD9E31420AD4CFD54553A83856F621D1C`. Their identities and UI result remain in `integration-final-pre-fgs-diagnostics-artifacts.json` and `integration-final-ui-before-fgs-diagnostics.log`. Only the two fixture classes changed for the FGS ownership diagnosis; the production/native/crash input manifest remained unchanged. Current fixture rebuild evidence is `integration-final-fixture-rebuild.log`.

The final fixture APK `2505ABEF` and test APK `AA639F4C` passed both the 224-check FGS suite and a fresh 33-check UI run, in that order, with fixture data reset between them. Current evidence is `integration-final-fgs.log` and `integration-final-ui.log`; the full hashes are in the table above. Production/native/crash sources were unchanged and were not unnecessarily rebuilt or rerun.

During this work another thread reported memory/page-file pressure. A subsequent read-only host snapshot showed roughly 15 GiB free physical and 41 GiB free virtual memory; the page file's historical peak was 37,888 MiB against 39,477 MiB allocated. This establishes resource-pressure context, not the cause of the earlier FGS timeout. `integration-resource-snapshot.json` records the observation. Subsequent fixture builds and device suites run serially; no virtual-memory settings or other worklines' emulators were changed.

Only the disposable `emulator-5580` / `aneb-api35` is used. Physical phone, microphone, speaker and USB endpoints were not operated. `CaptureEnvironment.supported()` remains false. Actual device routes, OEM lifecycle/notification behavior, physical stop latency, power loss, flash erasure, battery/thermal endurance and complete stage acceptance remain unverified. Production retention/declaration deadlines and trusted cross-boot recovery remain open; current development constants are not adopted production policy.

Final verification matched all 58 source/build inputs and all five APK hashes, found no test/fixture classes in the production JAR, and passed UTF-8, local-link, traceability (36 requirements / 33 P0) and `git diff --check` checks. Both target packages had no active services; the service dump's historical ANR section belonged to Google network location, not these target services. The owned 5580 emulator was shut down after testing; other worklines' emulators and the physical phone were left untouched.

UVC source `fd9825e` (runtime fix `bc6599c`) is integrated only in `android/uvc-sim`, with provenance and main-worktree verification in its [integration record](../android/uvc-sim/INTEGRATION.md). Historical failure evidence is preserved. No native/AAR/Hutool dependency was imported; provenance/licensing, actual USB capture, durable control integration and encrypted image storage remain gated. The 44-T / 27-D readiness crosswalk is traceability, not passed acceptance. No publication, deployment or external processing occurred.

The snapshots below are historical. Their counts, hashes and pending statements apply only to those builds.

## Historical integration: bounded capture, deletion recovery and time gates

2026-11-10. The frozen v1.5 baseline remains unchanged. These are software checks under existing scenarios, not new PRD test identifiers or complete G0/G1/G2/G3 acceptance. Real capture remains disabled.

| Current suite | Result | Evidence boundary |
| --- | --- | --- |
| JVM policies/lifecycle | 257 passed | 176 note/clock + 13 framing + 22 start gate + 30 source lifetime + 16 retention envelope |
| JVM bounded queue | 21,217 assertions; 200 stop races passed | Scheduling-dependent assertion count; generated PCM |
| Development-package API 35 emulator | 923 checks passed | 499 buffered capture + 43 control recovery + 43 cleanup recovery + 89 clock quarantine + 21 read completion + 27 notes + 38 encrypted PCM + 53 meeting + 23 service + 12 audio retention + 11 note concurrency + 18 replay + 31 note retention + 15 UI response |
| Isolated synthetic foreground service | 198 checks passed | Generated PCM with actual Android FGS lifecycle; no AudioRecord or speaker |
| Abrupt application process recovery | 10 checks passed | Separate host force-stop and new-process verification; not power loss or a reboot |
| Python regression | 46 passed in 35.63 seconds | Local synthetic reference and API tests |

The final development APK, instrumentation and isolated fixture builds completed. Development APK v3 signature verification passed; existing Java source-8/deprecation warnings remain. Inspection of the production JAR found no fixture, instrumentation or test classes. `CaptureEnvironment.supported()` remains false.

### Behavior covered

- Source callbacks use a bounded two-second PCM queue including the consumer-owned item. Stop closes admission independently of storage; normal pause seals the accepted prefix, while privacy discard clears queued arrays and prevents pending assembler work from being sealed. Injected blocked writes and deterministic start/stop interleavings cover both behaviors.
- Service cancellation survives a blocked source factory. Coordinator cancellation retains its original event and privacy-discard intent across a simultaneous startup reset. The deterministic end/privacy test admits 800 samples into the assembler: normal end preserves them; privacy stop commits none, clears the owned array and records the known uncommitted interval.
- Stop identity, generation, sealing bound and known rejected range commit in one transaction. Before/after-commit injection covers rollback, idempotent retry, stale controls, ended coverage, deletion and unknown crash-tail metadata. Exception injection alone is not proof of abrupt-process durability.
- Cleanup retries already-durable deletion without classifying new expiry or decrypting media. Tests cover an old orphan left after a committed location switch, same-instance retry, cancellation, switch rollback, expiry-transaction rollback and unchanged surviving ciphertext/metadata. Time uncertainty still blocks new expiry and plaintext access.
- Notes validate complete clock provenance before reading/decrypting/parsing their body and again before return. Audio rechecks time and revocation after decryption. Controlled late expiry, deletion, boot-marker and wall-anchor faults reject stale plaintext. Routine clock validation does not rewrite checkpoints or adopt a forward wall jump.
- The final FGS fixture observed a maximum blocked-write software stop-dispatch interval of 10 ms against its 2000 ms fixture bound. This is one emulator observation, not an AudioRecord, driver, acoustic or PRD hardware stopping result. Source-factory cancellation was also exercised while the main handler was blocked; the synthetic source never started.
- The abrupt fixture committed 40,000 generated samples and kept another 1,000 only in the assembler. After `CONTROL_ABRUPT_READY`, the host force-stopped only the disposable development app. The prepare process consequently reported `Process crashed`, as intended. A fresh instrumentation process passed 10 checks: committed audio remained readable, recovery paused the session, the unknown tail began at sample 40,000 with unknown time/end fields left null, no exact lost duration was invented, and repeated recovery was idempotent.

### Failures and corrections in this integration

- The first combined native run reached the encrypted-PCM lock test but failed because the MENU key did not open the API 35 PIN screen. The harness now requests the credential screen with `wm dismiss-keyguard`, waits for its focused PIN entry and submits the disposable emulator PIN. Actual secure-keyguard, locked-read rejection, blind-write and unlocked-read checks remain; the complete final run passed.
- The next attempt failed the old `visibleLifetime <= 100 ms` fixture because its deadline used raw wall time while the implementation correctly returned a validated monotonic projection. Test deadlines and delayed-return waits now use the store's actual trusted clock. A prior replay assertion expecting a forward wall jump to advance the anchor was also replaced with rejection plus an unchanged anchor. No clock tolerance, privacy gate or deadline assertion was relaxed.
- Review found and fixed two stop/start races (lost stop publication and null request reread), then an associated privacy-discard reset that could otherwise seal pending PCM. The final 923-check run includes the strengthened interleavings.
- One test-install command initially used the wrong relative directory after a build script changed location. Installation had not occurred; rerunning from the repository directory succeeded. An overly broad case-insensitive JAR-name check matched `NoteStore`; the corrected class-name check passed. These were orchestration/checker errors, not product fixes.

| Current built artifact | SHA-256 |
| --- | --- |
| `android/build/sidecar-dev.apk` | `8E980A53C4F5E6DAB6A15C48D5BE223FB519508845779AE1E77A435227084C60` |
| `android/build/sidecar-tests.apk` | `C8638F5242941F51AFA0ACC70A23010A3993E5065966BB70DEC9F83064E916F6` |
| `android/build/sidecar-fixture.apk` | `AA501D847C682A7F36D4E7607F100251344D9E3B692F418124E6232C12157E74` |
| `android/build/sidecar-fixture-tests.apk` | `87B3DD38A2A105254D56D44BAF2787A833B39391FE199253CDFF10B42E557131` |

Local run logs are under `android/build/integration-*-final.log`; initial native failures are in `integration-native.log` and `integration-native-retry.log`. Builds and device data are disposable synthetic artifacts. Physical capture, AudioRecord/AudioTrack behavior, calibrated stopping/overflow, device reboot, power loss, flash erasure and sustained thermal/power tests remain unverified. Production TTL choices, declaration deadlines, trusted cross-boot recovery and complete gap presentation are still open. No upstream code/dependency, publication, deployment, external processing or PRD change was introduced. The independent UVC work has not been merged; it has no physical-device acceptance evidence.

Implementation: [bounded PCM](BOUNDED_PCM.md), [integration recovery](INTEGRATION_RECOVERY.md), [current gates](GATES.md). All snapshots below are historical; their hashes, counts and incomplete-work descriptions apply only to their respective builds.

## Previous completed snapshot: control isolation and note cleanup

Previous Android validation: 2026-11-10. The following artifacts predate the integrated queue/recovery changes above. Real capture was also disabled; no stage exit was declared.

| Completed suite | Passed checks | Evidence boundary |
| --- | ---: | --- |
| JVM policies and lifecycle | 107 | 26 note/clock + 13 PCM + 22 start gate + 30 source lifetime + 16 retention envelope |
| Development-package API 35 emulator | 226 | 27 notes + 38 encrypted PCM + 53 meeting lifecycle + 23 service + 12 audio retention + 11 note concurrency + 16 replay + 31 note retention + 15 UI response |
| Isolated positive foreground-service fixture | 173 | Generated PCM, actual Android FGS lifecycle; no AudioRecord or speaker |
| Python suite, earlier run | 46 | Unchanged and not rerun in this Android-only slice |

## Latest completed validation: control isolation and authenticated note cleanup

- Final development build, APK v3 signature verification, instrumentation build and isolated fixture build succeeded. Existing Java source-8/deprecation warnings remain.
- The service now separates atomic cancellation, device-stop dispatch and storage finalization. Fixture checks deliberately stalled preparation, encrypted append and failure recovery; notification pause/end and privacy deletion still stopped synthetic input, preserved pending status/foreground notification, rejected later samples and finalized correctly after release.
- The largest observed blocked-write software stop-dispatch interval in the final fixture run was **5 ms**, against its deliberately loose **2000 ms** test bound. This single emulator measurement is not AudioRecord, Binder, driver or acoustic stopping latency and is not a PRD hardware threshold pass.
- Blocked End checked that the stored control boundary fell between the request and disk-latch release, rather than using delayed persistence-completion time. Preparation cancellation never started the synthetic source. Injected notification-construction failure did not skip pause/cleanup. Concurrent deletion stopped input before waiting for the durable privacy lock, then revoked keys and access.
- Source-lifetime JVM checks covered cancellation before/during recorder start, stopping before blocked/failed sealing and safe release ownership. Native UI checks held the privacy/store lock while exercising all three pages, verified main-loop response and rejected late private content after leaving. The preparation page no longer decrypts an existing meeting title.
- Retention checks covered independent immutable title/note deadlines, verified legacy migration, inconsistent metadata rejection, cancellation, idempotent cleanup, restored-ciphertext barriers and concurrent append/delete. The policy evidence is explicitly a synthetic development snapshot, not recovered user policy selection or an approved production default.
- Both final service dumps returned `(nothing)`. Only the disposable `emulator-5580` was operated; it was shut down after testing. The attached physical phone was not operated. Development JAR inspection excludes fixture/instrumentation/synthetic-source classes.

## Failed/intermediate runs and resolution in this slice

- The first fixture run failed at check 34: preparation inspected a prior instance's notification before the current FGS notification was observable. The test now waits with a bound for the current fixture service-instance marker, FGS flag and expected controls; the final 173-check run passed.
- A later native run stalled in the added preparation-page navigation test: `startActivitySync` was asked to launch the already-top preparation activity again and waited for creation. That disposable development process was stopped, and the harness was changed to cover it with a different Activity. The corrected final 226-check suite passed; no product privacy or capture gate was relaxed.
- A sub-agent's optional isolated compile was rejected by automatic approval because its generated output targeted the build directory excluded from that subtask's editing scope. It did not execute or retry. The root task integrated its tests into the standard authorized build; the complete final JVM and native runs above passed. No approval blocker remains for these deliverables.

| Final tested artifact | SHA-256 |
| --- | --- |
| `android/build/sidecar-dev.apk` | `F224BDBA0D51303B3EB3F88A8633325D61F0B2C2A3582D1A6BB7E9DAA6049474` |
| `android/build/sidecar-tests.apk` | `DD9830531C6C25B8FADF1D1842205B0F8BB365E673D0B3D03C33395E6879E656` |
| `android/build/sidecar-fixture.apk` | `6F31703928837A62EE83027262131E479046D5EB5E451379D01B7CC60165BC14` |
| `android/build/sidecar-fixture-tests.apk` | `B36DEC8BD20B40B60271B2D4FB690AA8AF600B6C38212DA092DD9D1FBCA472EF` |

Fixture/instrumentation artifacts are for the disposable emulator only. No PRD feature, state branch or test identifier was added. No upstream source/dependency was imported; no publication, deployment, real recording or external processing occurred. See [control isolation and limits](CONTROL_ISOLATION.md), [pinned upstream review](OPEN_SOURCE_CONTROL_REVIEW.md) and [current gates](GATES.md).

Remaining limits include platform start/stop or driver stalls, synchronous persistence on the source reader (no PCM handoff queue), real overflow/gap and long-run behavior, physical capture/playback/timing, production retention selections and declaration deadlines, cross-boot trusted-time recovery and full processing/authorization scope. Cleanup completion/error reporting does not prove a timed-out native worker has released every platform resource. Note cleanup is authenticated and best-effort, not exact-deadline physical erasure.

## Historical record

The following snapshots apply to earlier builds; their hashes, counts and incomplete-work statements are historical.

## Previous validation snapshot

Previous completed Android validation: 2026-11-10. Hashes below identify the tested artifacts. These implementation checks do not add PRD test identifiers or establish full T/G acceptance. Real microphone capture remains disabled; no G2/G3 exit is declared.

| Completed suite | Passed checks | Evidence boundary |
| --- | ---: | --- |
| JVM policy, framing, start gate and source lifetime | 76 | 26 policy + 13 PCM + 22 start gate + 15 source lifecycle; no device capture |
| Development-package API 35 emulator instrumentation | 180 | 27 notes + 38 encrypted PCM + 53 meeting lifecycle + 23 service + 12 retention job + 11 note concurrency + 16 replay admission |
| Isolated positive foreground-service fixture | 33 | Real OS foreground service with generated PCM; no AudioRecord or speaker |
| Python suite, earlier completed run | 46 | Unchanged and not rerun in this Android-only work |

### Prior implementation validation: concurrency, stop ordering and retention clock

- Development APK compilation and v3 signature verification passed. Instrumentation and isolated fixture APKs built successfully. Existing Java source-8/deprecation warnings remain.
- Two NoteStore instances contending on the same revision produce one successful save and one conflict; explicit retry preserves both accepted notes, and stale writes cannot resurrect a deleted meeting.
- JVM recorder callbacks verified stop-before-seal under blocked/failed persistence and idempotent concurrent stop/release. This does not exercise AudioRecord or prove a physical stop-latency bound.
- Synthetic replay checked partial/zero writes, clearing owned PCM, ordered deletion versus an in-flight output submission and denial of subsequent submissions. Already queued sound is not claimed to be retractable.
- One hundred replay access checks against a controlled same-boot anchor caused no SQLite changes. Separate checks verified forward-clock checkpointing and rejected rollback/cross-boot access. This is controlled database evidence, not a wall-clock change or real reboot test.
- Initial development instrumentation stopped at encrypted-PCM check 8 because the emulator credential UI had not unlocked. The test now waits for the visible, focused PIN field before submitting the disposable emulator credential; actual locking, authentication and Keystore checks remain. The complete corrected suite then passed all 180 checks. No product safeguard was bypassed.
- An initial chained build command failed to locate the next scripts after build.ps1 changed the caller's working directory. Re-running the remaining scripts by absolute path succeeded; this was command orchestration, not a product compile failure.
- The isolated fixture again passed all 33 existing positive FGS checks. Its notification actions, resume consent, stale-control rejection, deletion and notification-denial stopping remain covered with generated PCM only.
- Development JAR build inspection excludes fixture/instrumentation/synthetic-source classes. Both final application service dumps returned `(nothing)`. Only `emulator-5580` was operated, and it was shut down after testing. The attached physical phone was not operated.
- No PRD feature, state branch or test ID was added. No upstream code/dependency was imported, no network processing was enabled and nothing was published or deployed.

| Tested artifact | SHA-256 |
| --- | --- |
| `android/build/sidecar-dev.apk` | `DDEC277EF466E8FB928D2DEAB3EC34F96CCA468E8418EFD76D577277DC5A51B1` |
| `android/build/sidecar-tests.apk` | `B2793EB01048B3CCD25D6C5C0B735CAF26D424C7BAFE55A4EC1BD2F3AF514A02` |
| `android/build/sidecar-fixture.apk` | `3F5C0FBD611EF2BC9F0FA9CB7C4770EC11BA1340733F71524F1B16C06756454D` |
| `android/build/sidecar-fixture-tests.apk` | `D56DB22388E4CD4BCD73BFD1DD5E5189109B8C52CFF00169AE15409E9F0E528B` |

Fixture and instrumentation APKs are for the disposable emulator only. See [implementation and coverage limits](CONCURRENCY_HARDENING.md) and [current gates](GATES.md). Main-thread service I/O and coordinator recovery I/O can still delay real stopping. Cross-boot trusted-time recovery, complete non-audio/declaration retention and physical capture/playback/long-run acceptance remain unfinished. The new persistent-zero output timeout has not had a dedicated test; real AudioRecord worker timeout and release behavior also remain unverified.




The sections below describe earlier iterations. Their counts, hashes and implementation limits apply to those builds, not automatically to the latest artifacts.

## Prior validation: positive FGS chain and retention worker (2026-11-10)

- Final development build and APK v3 signature validation passed. JVM checks: **17 note-policy + 13 PCM boundary + 22 start-gate = 52**.
- Final development-package emulator suite: **27 native note + 38 encrypted PCM + 53 meeting lifecycle + 23 capture service + 12 retention job = 153** checks passed.
- Final isolated fixture-package suite: **33 positive foreground-service synthetic checks passed; no AudioRecord or speaker opened**. A real OS microphone-type FGS ran with generated PCM. Its notification actions, live UI, encrypted persistence, resume consent, stale-control rejection, privacy deletion and notification-denial stopping were exercised.
- End projection tests verified frozen sample/control boundaries, idempotent end, rejected projection rewrites and unchanged end time after post-meeting notes. Legacy missing timestamps remain unknown.
- The retention test forced the real system job, checked its binding permission, cancellation and registration, and verified removal/idempotency and decryptability of retained frames. Expiry was a controlled metadata fixture, not a seven-day elapsed-time or device-clock test.
- Development APK SHA-256: `A69D0AE1DB76FAF3301C0D69790744FC362C511EA45D1471C820753C337485C6`.
- Isolated fixture APK SHA-256: `6B6738D6A935C8BF59723116FD8781725E801D515E838CA818F2ACAA68989DF2`. This is test-only and must not be installed on a real user device.
- Development JAR inspection found no fixture/instrumentation/synthetic-source classes. Both application service dumps were empty after tests. The disposable emulator was shut down; the physical phone was not operated.
- Python was unchanged and its earlier 46-pass result was not rerun. No PRD feature/state/test ID changed. No upstream code, runtime dependency, publication, deployment, network processing or real recording was introduced.
- [FOREGROUND_CHAIN.md](FOREGROUND_CHAIN.md) defines the distinction between positive service verification and real media/device acceptance, reproduction steps and remaining gates. Earlier results are preserved in the historical record below.

## Early prototype and native-note history

日期：2026-11-10；环境：Windows 本地开发机、Python 3.14、cryptography 48.0.0、pytest 9.1.1。

最终代码验证命令：`python -m pytest -q`。

结果：**30 passed in 3.68s**，退出码 0。先前一轮同时执行 `python -m sidecar.demo` 成功，输出确认缓冲先阻止提交、同 ID 第二次提交为 duplicate、证据可检索、请求 [1,3) 整删实际 [0,5) 帧、删除后搜索为空。最后一处代码变更为写入失败时建立暂停屏障，回归仍为 30 项通过。

| 既有测试映射 | 已跑的局部模拟覆盖 |
| --- | --- |
| T01/T36 | 无同意不写帧；显式开始 |
| T08/T37/T41 | 暂停旧代次、单会话互斥、快捷准备幂等、全局暂停 |
| T09/T15/T25 | 最后样本缓冲、墙钟改变不提前成熟、新boot阻止、未知/计费/非授信/网络代次 |
| T10 | 1,000 次同对象模拟提交（丢响应重试模型），仅一个可见对象；没有真实网络故障 |
| T12/T30 | 跨owner/撤销拒读；认证元数据变化拒绝 |
| T16/T40 | 随机线程先后删除/提交不复活；中间帧整删；边界前一帧保留；保留帧重写后可读/模拟上传 |
| T17/T42 | 等待全部上传输入；重复任务；会后笔记不移动音频边界；旧附件快照拒绝 |
| T21/T34/T44 | 整产物隔离；更正基线冲突；旧输入任务拒绝；不延长期限 |
| T23/T44 | 软件锁标志下可写、拒绝读取及模拟上传，**不代表真实Keystore锁屏安全** |
| T33/T35 | 取消策略重开后仍生效；已判到期不因墙钟回拨恢复 |
| T39/T40 | 未提交尾部回收、哈希和偏移拒错、fsync模拟失败不提交、重写失败保留合法原副本 |

以上是同一 PRD 测试场景的子集，**没有任何整项 T 测试或 G 阶段据此宣称验收通过**。尤其单进程模拟不等于 Android/服务端并发，RSA 对象留内存不等于锁屏私钥不可用，SQLite 文件删除不等于物理擦除。

未运行：所有硬件测试、真实录音/拍照、实际网络与远端故障、Android 前台服务/通知/权限/Keystore、2/8/12 小时长测、72 小时容量、系统断电、SQLite整体回滚/备份恢复、照片可读性、实际ASR/OCR/模型质量、费用流量审计、跨进程竞争、完整33项P0验收。HID未启用，T14不适用于当前原型。

资源信息：段轮转和帧数行为有代码覆盖，未测手机耗电/温升/写放大/尾延迟；测试总运行时间不能作为产品性能数据。不存在发布支持矩阵，任何手机/采集设备均未在本任务中获支持结论。

阶段判断：可运行的 G0 局部核心闭环；公共契约仍为草案，完整G0交付与评审未完成。G1/G2待设备及授权测试条件，G3/G4未进入。

首轮原生进展：已增加 Android 手工笔记开发 APK，构建/签名校验通过；当时12项 JVM 策略检查及13项 API 35 模拟器原生存储检查通过，应用启动成功。该轮APK已被后续生命周期版本替代。

## 当前 Android 生命周期版本

2026-11-10，`android/build.ps1` 和 `android/build-tests.ps1` 成功；APK v3签名校验通过。当前结果为 **17项 JVM 策略检查通过、27项 API 35 模拟器原生存储检查通过**。最终APK重新安装于一次性模拟器并冷启动：`Status: ok`、`Activity: local.sidecar.copilot/.MainActivity`。没有安装实体手机。

该生命周期版本 APK SHA-256：`6C92E0DDC119E72451DEBE33AD29D90E2AE9A6065586C261B512EB25D53CE63C`（已被后续包含未开放音频接口的开发构建替代）。

新增检查覆盖既有 T11/T34/T35/T40/T42 的局部场景：新增笔记不延长旧标题/笔记期限；单笔记删除不影响另一条；将删除前密文重新写回后仍不可见；到期笔记清理后恢复旧密文仍不可见；整场到期不会让其他会议不可读；可见正文最早截止计算。时钟回拨、跨boot和单调时钟倒退为纯策略测试，**不是实机改时/重启实验**。页面定时清空和时间变化广播目前只有编译/启动及底层截止计算覆盖，尚未完成端到端UI自动化。

详细范围见 `ANDROID.md`。没有任何完整T场景或G阶段据此验收通过；Python部分本轮未修改，未重复运行此前30项检查。一次性模拟器在测试完成后关闭。

## 当前本地API与音频接口版本

最终 `python -m pytest -q`：**46 passed in 9.19s**，退出码0，无警告。包含原30项和新增的现有T场景子集：跨来源关键词联合、会话/身份隔离、会议背景与删除隔离、当前更正版本、三段式报告、紧凑回收后缺口保留、夏令时23/25小时、取消例外不永久解禁、旧任务发布屏障、HTTP认证与409冲突。

其中一项实际启动127.0.0.1临时端口，通过HTTPX发HTTP请求验证401、双来源召回、删除和再次检索；没有出站外部请求，测试结束已关闭本地服务器。其他API用例用TestClient，是进程内ASGI测试。没有生产云或移动网络验收结论。

最终 `android/build.ps1`：编译与v3签名校验通过，**17项原生策略检查、13项PCM边界检查通过**。PCM合成样本覆盖5秒封帧、尾帧、连续样本索引、未同意拒绝、旧代次拒绝、健康标记切换及写入失败停止；未打开任何麦克风。AudioRecordSource接口仅编译，不具备完整加密Sink、FGS可见性或设备实测支持；当前Manifest仍无音频/网络权限。此前27项Android存储instrumentation本轮未重复执行，不能称为本构建新完成的设备测试。

当前 APK SHA-256：`D9D2D96D1A70C59971B4090728D1090EBE03D3C3119F9B5C7C794CF73E51579C`。

OpenAPI从实际FastAPI路由导出到`contracts/openapi.json`，不是手写伪接口；契约仍待评审。已完成的工程切片及剩余阻碍见`PROCESSING_SLICE.md`和`GATES.md`。完整G0/G1/G2/G3均未宣布通过。
## Historical verification: encrypted audio integration (2026-11-10)

- Android build and APK v3 signature verification passed; 17 native policy and 13 PCM boundary checks passed.
- API 35 disposable emulator instrumentation: `27 native note checks and 38 encrypted PCM checks passed; synthetic emulator only`.
- Tested file-sync/SQLite/compaction failures, stop-tail failure, interrupted capture recovery, corruption, deletion, and emulator locked blind-write / unlock read. Details: [AUDIO_PERSISTENCE.md](AUDIO_PERSISTENCE.md).
- Initial runs exposed SQLite PRAGMA API misuse (fixed), keyguard animation timing in the test (fixed), and reuse of deliberately corrupted test data (isolated fixtures now used). The final complete run passed.
- Current app APK SHA-256: `B893B5196F949156B3F5720DE336F3049887FE70B818B3AF02F87E436F68384F`.
- Python was unchanged in this slice and its earlier 46 passing tests were not rerun. No real microphone, physical phone installation, cloud call, deployment or PRD modification occurred.
- Instrumentation checks and exception injection do not establish actual hardware security, power-loss durability or complete T/G acceptance. Older results below are historical.

## Historical verification: capture preparation and service gates (2026-11-10)

- Final Android build, APK v3 signing check and JVM checks passed: 17 note-policy, 13 PCM boundary and 22 capture-start gate checks.
- Final API 35 emulator result: `27 native note checks, 38 encrypted PCM checks and 23 capture service checks passed; synthetic emulator only`.
- Service checks include non-exported components, microphone FGS manifest type, immutable/session-specific notification actions, unchecked/reset consent, runtime microphone/notification grants, no active recording after grants or rejected starts, unsupported-device denial despite a valid ticket, stale stop handling, and blocked notification-channel detection. The permission result callback was exercised programmatically, not by an end-to-end system-dialog UI test.
- Positive microphone FGS start is **not tested**: the release gate remains closed. No real or emulator microphone was opened. The post-test service dump was empty. The disposable emulator was shut down; the connected physical phone was not operated.
- Final APK SHA-256: `B7F2FEC3166F8787A91830853569240E1CA4E0F859F9D5FE3CDF732B0BD38674`.
- Initial test compilation used a non-public ServiceInfo field; fixed to the public getter. An initial 18-check emulator pass was followed by the stronger final 23-check pass. The temporary blocked channel was moved to an isolated fixture channel. Only synthetic app data on emulator-5580 was cleared between these runs.
- Python code was unchanged; the earlier 46-pass result was not rerun or presented as a new result. No PRD edits, external runtime dependencies, publication or deployment occurred.
- [Implementation scope](CAPTURE_SERVICE.md) and [upstream/license assessment](OPEN_SOURCE_REVIEW.md) detail remaining gates. Older results below are historical.

## Historical verification: meeting binding, control page and replay (2026-11-10)

- Final APK build and v3 signature validation passed. JVM checks: 17 note-policy + 13 PCM boundary + 22 start-gate checks passed.
- Final disposable API 35 emulator result: `27 native note checks, 38 encrypted PCM checks, 48 meeting lifecycle checks and 23 capture service checks passed; synthetic emulator only`.
- New meeting coverage includes encrypted consent/name, meeting/audio identity, consent revision to generation binding, consumed-consent rejection, paused/end native controls, post-end notes, deletion/key removal/old ciphertext refusal, active-buffer deletion and controlled preview. A latch-based test held a frame after fsync, attempted deletion concurrently, verified deletion waited for the in-flight commit, then verified the deleted meeting could not be read or restarted.
- Preview output was an in-memory test double. No actual microphone or speaker was opened. Actual AudioTrack behavior, live capture UI updates, positive microphone FGS, OEM notification controls, hardware source arbitration and stop latency remain untested.
- Earlier 43-check passes preceded the concurrency strengthening; the final 48-check run passed. This run did not require any permission override on the physical phone. No PRD requirement/state/test identifiers changed.
- App APK SHA-256: `19481A77ADFF5D323F156F013581F15BB30B5F4117D5F78A62A4FA0F3A5E6032`.
- Python was unchanged; its earlier 46-pass result was not rerun. No upstream code/dependency, external service, publication or deployment was introduced.
- See [MEETING_LIFECYCLE.md](MEETING_LIFECYCLE.md) for scope and remaining limitations. Older results below are historical.
