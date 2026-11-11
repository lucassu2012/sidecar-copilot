# Positive foreground chain, end projection and audio housekeeping

Frozen v1.5 implementation update. These are engineering changes and checks under existing requirements, not new product modes or acceptance IDs.

## Isolated positive service verification

`PcmSource` separates source ownership from the existing service. The development app's factory still creates `AndroidPcmSource`, and its supported-device gate remains false. There is no intent-controlled simulation switch or runtime bypass of that gate.

`android/fixtures` builds a **separate application**, `local.sidecar.copilot.fixture`, whose service subclass supplies generated PCM and labels its notification synthetic. It accepts only an emulator environment and never constructs AudioRecord. Its storage and application identity are separate from the development app. Fixture source files are outside the production compilation tree; `build.ps1` now cleans generated class directories and rejects fixture/instrumentation classes in the development JAR. Debug signing and fixture packages are local test artifacts, not release deliverables.

The fixture starts a real Android microphone-type foreground service from a visible Activity with granted system permissions and an ephemeral start ticket. The remaining path is shared: encrypted declaration, consent-generation binding, PCM framing, segment commit, live status, notification actions and stop barrier. The native test verifies the FGS notification flag, live UI, paused ciphertext replay through the store, explicit resume with a new declaration, stale notification rejection, notification end, deletion-triggered stop, stop when the notification channel is disabled and no automatic restart. AudioManager reports no active microphone recording configurations. This is a **positive OS service lifecycle test with fake media**, not a microphone/device acceptance test.

Reproduce only on a disposable API 35 emulator with a synthetic credential:

```powershell
./build.ps1
./build-tests.ps1
./build-fixture.ps1
# Install the development and test APKs for StoreInstrumentation.
# Install sidecar-fixture.apk and sidecar-fixture-tests.apk for the isolated fixture.
adb -s emulator-5580 shell am instrument -w local.sidecar.copilot.test/local.sidecar.copilot.StoreInstrumentation
adb -s emulator-5580 shell am instrument -w local.sidecar.copilot.fixture.test/local.sidecar.copilot.FixtureInstrumentation
```

The fixture's final notification-denial test deliberately blocks its own channel. Reset only the disposable fixture application's data before repeating that suite. Never install these instrumentation/fixture APKs on a real user phone. Credential setup, exact hashes and measured results belong in `VALIDATION.md`.

## Frozen stop boundary

An explicit end transaction now stores the last stream's sample-end index, the control event's monotonic time/boot and a device-clock UTC estimate with the ended state. Repeated end calls leave this record unchanged. The encrypted meeting view projects that boundary when unlocked, without extending title/note retention. Post-meeting notes cannot alter it. Older ended sessions lacking an event stay unknown; they are not assigned today's time. A control-event timestamp is not a claim of calibrated physical microphone latency.

## Retention housekeeping

`AudioRetentionJob` uses Android [JobService](https://developer.android.com/reference/android/app/job/JobService), protected by `BIND_JOB_SERVICE`. It registers periodic, device-idle work through [JobInfo.Builder](https://developer.android.com/reference/android/app/job/JobInfo.Builder). The nominal interval is 15 minutes; OS deferral, force-stop and device activity mean it is **not a deadline guarantee**. It is registered on app entry or capture start, is not persisted across reboot, and never opens a microphone, decrypts audio, starts playback, uploads data or resumes a session.

The worker obtains the exclusive store owner, applies the conservative retention clock, records expired intervals and removes their wrapped DEKs transactionally, then compacts only affected segments. Empty replacement containers are removed. Cancellation leaves logical exclusion in place and cleanup resumable. A busy store or unavailable trustworthy clock is retried instead of bypassed. Ordinary read/replay paths reject expiry independently of this job.

The housekeeping open performs recovery of lengths, orphan files and pending barriers without rehashing every historical audio frame. Retained frames copied during compaction are verified; ordinary reads still verify metadata, ciphertext and AEAD authentication. This avoids a full-history byte scan every scheduled run. No battery/I/O improvement ratio is claimed.

Tests force the real system job against controlled synthetic expiry metadata, verify cancellation, scheduler registration and service permission, then verify removal, retained-frame readability and idempotency. This is not seven days of elapsed-device testing. Background cleanup of encrypted notes/declaration history, full cross-boot clock recovery, OEM power restrictions and forensic erasure remain unresolved.

## Gate status

Real microphone capture remains disabled. The next physical evidence requires a selected phone/build/input configuration and an explicitly permitted recording scenario. Still unverified: actual AudioRecord input, route and arbitration behavior, real speaker replay, acoustic coverage, measured stopping latency, battery/thermal/storage thresholds, long-duration operation and OEM lock-screen controls. G2/G3 do not pass on these synthetic tests. No third-party dependency, model, publication or deployment was added.
