> Historical local development README, retained as source documentation. See [SOURCE_SNAPSHOT.md](SOURCE_SNAPSHOT.md) for public packaging, latest APK status, license status, and omitted local evidence. References to unpublished state and older candidates below are historical.

> 当前候选 **F134E5D4** 已完成 API35 合成 Android 回归：Full Native 2031、UI64及存储/控制/撤权/FGS/进程终止套件通过；首次锁屏前置拒绝保留。自有AVD已关闭，17:38:18 UTC复查无模拟器进程。实机与G2/G3仍未验收。见 [本轮运行证据](docs/F134_RUNTIME.md)。

# Sidecar Copilot — local reference and Android development app

The latest source adds a [durable loopback transfer slice](docs/DURABLE_TRANSFER.md): real HTTP parts, persistent retry/deletion queues and verified ciphertext receipts, while capture/authorization inputs remain synthetic. Android range deletion now persists its barrier before discard and finalization waits. These new Android sources have compiled and passed host checks but have no newly built or device-tested candidate; the frozen F134 packages below remain the last recorded device baseline.

The following [Android source-interface checkpoint](docs/ANDROID_UPLOAD_REFERENCE.md) adds an authenticated local audio reference without guessing capture times or changing sample-range scope. Its value contract passed 214 host checks and the combined 59-source compile passed; the new Android storage checks are registered but unrun. Android transfer admission, persistent upload/outbox recovery and network/product callers remain unfinished.

The frozen v1.5 PRD is the sole requirements baseline. This repository contains a synthetic Python reference and an Android development implementation. **Real microphone capture remains disabled, and no complete G0, G1, G2 or G3 exit has been declared.** Current use is limited to synthetic test data; this is not a production recorder or a place for real private work material.

The Android app now includes meeting preparation and consent, permission handling, a microphone-type foreground service and visible controls, encrypted note/audio storage, pause/stop/end handling, privacy deletion, guarded replay and audio TTL housekeeping. Its supported-device gate remains closed. A separate fixture app exercises the positive foreground-service chain with generated PCM; it never opens AudioRecord or a speaker and is excluded from the development APK.

Use [current stage gates](docs/GATES.md) and [current candidate validation](docs/MEETING_ISOLATION.md) for status. The following [runtime results](docs/ED140E29_RUNTIME.md) are historical and do not validate F134E5D4. **ED140E29 with corrected native-test APK AD74CF9E passed the recorded API35 synthetic regression.** Original native Pending/full failures are preserved: one test fixture omitted its recorded-consent precondition. Only that test line changed; the four other APKs (including production) and 61 production classes are unchanged. That baseline’s native full 1,936 and pending subset 164 passed; UI/control/revocation/FGS and ten process-termination scenarios have separate bound receipts. Two host-orchestration failures are also preserved. The owned emulator is closed; the new scoped-homepage API35 results are recorded separately in F134_RUNTIME.md, and all physical-device/stage gates remain open.

## Run the Python reference

From this directory in PowerShell:

```powershell
python -m sidecar.demo
python -m pytest -q
```

For a fresh environment, create and activate a virtual environment, then install the test dependencies:

```powershell
python -m venv .venv
.\.venv\Scripts\Activate.ps1
python -m pip install -e ".[test]"
```

The recorded Python environment uses Python 3.14, cryptography 48.0.0 and pytest 9.1.1. The package declares Python 3.11 or later; other Python versions have not been qualified.

The demo generates synthetic text and uses a temporary directory to exercise encrypted frames, append-only segment storage, the post-capture buffer, simulated upload, evidence retrieval, synthetic extracts and deletion barriers. It closes the database and removes its demonstration directory on exit. Its upload operation is an in-process simulation, and its `audio` source label carries synthetic text rather than recorded audio. The generated extracts are not ASR output or model-generated meeting summaries.

The authenticated API is also synthetic-only and binds to loopback. Install its dependencies, set `SIDECAR_DEV_TOKEN` to a random token of at least 32 characters, then run:

```powershell
python -m pip install -e ".[api]"
python -m sidecar.server --synthetic-only
```

See [the processing/API slice](docs/PROCESSING_SLICE.md) for retrieval, layered reports, correction, deletion and cancellation behavior. This local API does not contact an external model or provide the Android app with a production backend.

## Build the Android development app

```powershell
cd android
.\build.ps1
.\build-tests.ps1
```

The usual local debug output is `android/build/sidecar-dev.apk`. The current five-package candidate is frozen separately under `android/build/meeting-isolation-candidate-20261110/source/build`; its development APK is F134E5D4 and its native-test APK is D203A868. Both were installed and verified on the owned API35 synthetic AVD; the complete runtime receipts are in F134_RUNTIME.md. The root-build APK and the preserved ED140/AD74 directories are older candidates. Build scripts accept `-SdkRoot` and `-JdkRoot`; the recorded toolchain uses JDK 17, Android platform 35 and Build Tools 35.0.0. The app declares minimum API 30 and target API 35; earlier device-side evidence comes from a disposable API 35 emulator and has not been rerun for the current APK. See [Android build notes](docs/ANDROID.md) for the original setup and [VALIDATION.md](docs/VALIDATION.md) for artifact identities and results.

The development app provides these partial implementations under the existing requirements:

- Encrypted manual notes, revision conflict checks and meeting-level deletion; complete note read-modify-write operations are serialized across store instances.
- Note reads reject unresolved `.new` or different base/backup ciphertext before recovery; exact durably marked copies can be removed without deleting their neighbors or shared keys. Note discovery includes recoverable `.enc.bak` companions; maintenance also scans `.enc.new` and retries durable deletion-barrier IDs. Reads, commits and deletions verify their file postconditions. An ambiguous lone `.new` is retained and reported as an I/O failure while other independently due notes can still be processed. MainActivity runs scoped maintenance before ordinary store construction; ordinary recovery remains fail-closed.
- Explicit meeting consent and start tickets, permission checks, foreground notification controls and session-scoped pause/stop actions. These do not bypass the closed real-device gate.
- Encrypted PCM frames in segment files, SQLite offsets and a two-second bounded source queue. At 16 kHz mono, the queue owns at most 32,000 samples / 64,000 PCM bytes including its in-flight item; assembler, source and encryption buffers are additional memory.
- Independent input-stop control, latched cancellation during source preparation, normal admitted-prefix drain and separate privacy discard, including the tested stop/start interleaving. Accepted volatile samples are not reported as committed frames.
- Meeting/audio association and transactional stop boundaries with known backpressure gaps. Recovery preserves committed frames and records an unknown tail when no durable close survived, without automatically restarting capture.
- Conservative whole-frame deletion and retryable ciphertext reclamation. With trusted time, newly expired wrapped keys are revoked transactionally before old physical cleanup; clock uncertainty permits only cleanup of prior revocations. The scoped maintenance entry skips constructor recovery so older cleanup faults cannot prevent the expiry transaction; ordinary store opens still fully recover.
- Guarded note/audio reads and replay admission, including time/revocation rechecks before returning audio. Complete same-boot anchors support monotonic retention without normal checkpoint rewrites; a failed initial preference commit remains untrusted across store instances until an original-anchor retry succeeds. Inconsistent clocks block content access. Idle-time audio cleanup remains best-effort JobScheduler work.
- Owned audio/plaintext byte arrays and undelivered PCM are cleared in `finally` paths. Persisted range-deletion records are reapplied before recovery/cleanup/compaction, with independent exclusion in listing, read, replay-admission and committed-count queries. Earlier `8B3E32FF` emulator checks cover these defenses; current-candidate regression, physical storage and runtime-copy guarantees remain unverified.
- Confirmed page mutations remain queued after navigation within the running process, while stale read/results are suppressed. Storage still enforces authentication, deletion, expiry and revision checks. The recording page retains its saving state while background writes finish; no completion is shown before confirmation.

The separate synthetic fixture APK is a local test artifact, not a release deliverable or a real-device recording mode. No physical phone has been installed or operated as part of the recorded validation, and no real microphone, speaker, screen-capture device or external model was used.

## Limits that remain

The Python reference uses simulated clocks, lock/network flags, test owners and an in-memory RSA key. Its SQLite text and generated extracts contain plaintext synthetic data. Those mechanisms do not establish Android Keystore security, real authentication, persistent key recovery or production authorization. The Python source writer is single-process and now acquires an exclusive OS lease before recovery; concurrent multi-process writing is rejected, not supported.

The Android implementation still needs actual AudioRecord input and route validation, acoustic coverage, calibrated timestamps, playback behavior, stopping latency, OEM lock-screen controls and selected-device evidence. The earlier 33 UI and 12 interruption checks cannot substitute for the separate 40-control combination or OS permission-revocation recovery. The enhanced control runner and explicit host revocation script are now included, with notification-removal and process-identity checks, but have not run on the current candidate. The original T01/T07/T08/T29/T32/T36/T37 retest plan is in [GATES.md](docs/GATES.md). Earlier notification and diagnostic passes do not identify the historical intermittent timeout's cause. The source reader now hands PCM to a bounded consumer queue; slow storage can exhaust that capacity and stop admission with a known rejected-callback gap. Platform recorder calls can still block, and native losses or an abruptly lost RAM tail cannot be assigned a fabricated exact duration. Playback audio already queued to Android is not promised to be retractable. A different boot or untrusted clock remains blocked; trusted cross-boot recovery is not implemented.

UVC/UAC desktop capture, photo handling, Android/provider upload and authorization, processing budgets, real OCR/ASR/model integration, production retention selections, declaration deadlines and complete non-audio cleanup, correction workflows and the full applicable frozen acceptance scope remain incomplete. The new host loopback uploader is described separately above. The [isolated UVC simulation](android/uvc-sim/INTEGRATION.md) has been integrated and separately checked using generated pixels and in-memory preview/audit; it is outside the development APK and does not provide real capture or durable image storage. There is no published supported phone/capture-card combination. The repository does not install a desktop capture agent.

The separate [UVC experiment contract](android/uvc-contract/README.md) now defines capture identity, borrowed-FD ownership, owned RGBA frames and combined native/platform/application stop evidence. It provides a Java host checker and controlled C++ ownership experiments, not an Android USB/JNI driver. Its source ownership and next adapter work are specified without changing the frozen F134 APK inputs; exact host results and remaining gates are recorded in [its validation report](android/uvc-contract/VALIDATION.md).

Deletion provides access revocation and managed-file cleanup; it does not establish forensic erasure from flash, WAL, AtomicFile copies, backups or hardware caches. The WAL experiment uses host SQLite 3.50.4 and a synthetic marker, not Android wrapped keys or plaintext; it does not establish platform erasure. Physical power-loss recovery, hostile rollback/restore, hardware-backed key assurance, sustained 8/12-hour use, power/thermal performance and cleanup deadlines have not been accepted. Automated check counts are implementation evidence, not additional PRD test identifiers or complete stage acceptance.

## Implementation references

- [Frozen baseline provenance](docs/BASELINE.md), [architecture](docs/ARCHITECTURE.md), [contract draft](docs/CONTRACTS.md) and [requirement traceability](docs/TRACEABILITY.csv)
- [Current gates](docs/GATES.md), [validation and history](docs/VALIDATION.md), [bounded PCM ingestion](docs/BOUNDED_PCM.md) and [integration recovery](docs/INTEGRATION_RECOVERY.md)
- [Note companion-file recovery and maintenance](docs/NOTE_FILE_RECOVERY.md)
- [UI privacy and confirmed mutations](docs/UI_PRIVACY.md), [controlled process-termination recovery](docs/CRASH_RECOVERY.md)
- [Prior control isolation](docs/CONTROL_ISOLATION.md) and [prior concurrency hardening](docs/CONCURRENCY_HARDENING.md), with implementation history and limits
- [Foreground-service fixture, end projection and audio cleanup](docs/FOREGROUND_CHAIN.md)
- [Meeting consent, linkage, controls and preview](docs/MEETING_LIFECYCLE.md), [service preparation](docs/CAPTURE_SERVICE.md) and [encrypted audio persistence](docs/AUDIO_PERSISTENCE.md)
- [Pinned open-source and license assessment](docs/OPEN_SOURCE_REVIEW.md), [Oboe/WebRTC control review](docs/OPEN_SOURCE_CONTROL_REVIEW.md)

No Screenpipe code, UVC native dependency or ASR model has been imported. Candidate reuse requires review of the selected version and its dependencies. Development has not published, deployed or shared this project externally.
