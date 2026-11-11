# Android encrypted audio integration

Implementation slice under frozen v1.5. No PRD features, states or acceptance IDs were added. These checks cover portions of existing T06/T07/T08/T22/T23/T39/T40; they do not establish completion of those scenarios or a G gate.

## Delivered

- `AudioSegmentStore`: private append-only segment containers, SQLite offsets, independent AES-256-GCM frames and authenticated identity/timing metadata. Segments rotate at 15 minutes or 16 MiB. A frame is committed in SQLite only after the ciphertext file is synchronized. SQLite uses WAL and FULL synchronization.
- Per-session Android Keystore RSA private key requires recent device authentication and an unlocked device. Each frame has an independently random DEK wrapped with the public key. This implementation deliberately does **not** claim a hash-ratchet design or hardware forward-secrecy verification. The public-key path permits ongoing blind writes while locked; public APIs refuse plaintext reads while locked.
- `AudioCaptureCoordinator`: generation checks reject late callbacks. Pause closes admission, persists a stop barrier, seals only already accepted tail samples, then closes the sealing ticket. All failure paths clear the in-memory assembler. A failed final write records a missing tail when the database remains writable.
- Whole intersecting frames are tombstoned before cleanup. Compaction copies retained ciphertext, synchronizes and verifies it, atomically switches offsets, then removes the old container. No codec boundary re-encoding was added.
- Recovery truncates uncommitted physical tails, removes orphan segments, quarantines corrupt frames, closes pending sealing tickets and leaves interrupted capture paused. An exclusive writer lock prevents concurrent owners.
- `AndroidPcmSource` now sends PCM through this coordinator. A rejected callback terminates the read loop. The adapter remains inaccessible from UI and the manifest still has no microphone permission.

## Verification and reproduction

Build from `android` with `build.ps1` and `build-tests.ps1`. Use only a disposable API 35 `ranchu` emulator, configure its synthetic test PIN `2468`, authenticate, install both APKs, and run:

```powershell
adb -s emulator-5580 shell am instrument -w local.sidecar.copilot.test/local.sidecar.copilot.StoreInstrumentation
```

The audio fixture creates an isolated private directory each run. It uses generated PCM only, exercises emulator sleep/lock/unlock, and never opens a microphone. The test APK contains fault injection and must not be installed on a real user device. Inspect the instrumentation stream: shell exit status alone does not establish success.

Covered: explicit consent, five-second frame plus short tail, round-trip PCM, lock refusal/public-key blind write/unlock read, late callbacks, whole-frame deletion and retained-frame compaction, ended-session refusal, exclusive writer, failures before file sync/after file sync/before SQLite commit/before compaction switch, final-tail write failure, recovery of active sessions, orphan truncation and deliberate ciphertext corruption.

## Remaining limits and next work

- No FGS visibility/notification and stop action or runtime permission/consent UI has been wired. Therefore recording is still disabled. These are the next capture integration tasks before any authorized physical-device experiment.
- Emulator Keystore checks do not verify a physical TEE/StrongBox, OEM lock behavior, real audio routing/interruptions, dual tracks, AV clock calibration, one-second stopping latency, sustained power/I/O or 8/12-hour capture.
- RSA wrapping still occurs per frame; energy cost is unmeasured. Payload arrays are cleared where owned, but JVM/provider copies cannot be guaranteed erased. PCM is uncompressed.
- Tombstones and compaction enforce logical exclusion; they are not proof of flash physical erasure or resistance to restoring both an old database and old segment snapshot. Directory metadata power-loss durability and abrupt device power cuts remain unverified. Fault injection raises software exceptions, not real power failures.
- Retention uses the existing seven-day policy and conservative clock calculation. Reads reject expired frames. Background TTL cleanup, trustworthy clock recovery after reboot and full session-key disposal are not complete. No upload path is exposed; the persisted 15-minute eligibility metadata alone is not an upload gate.
- A fully unavailable database can prevent persisting a stop barrier/gap. The current coordinator still closes in-memory admission and discards PCM; next store open pauses persisted active sessions. This is not a durable-write guarantee during storage failure.
- No processing/upload integration, real ASR/OCR, model output or full PRD acceptance is implied by this slice. No external publishing or deployment occurred.
