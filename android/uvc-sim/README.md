# UVC synthetic frame pipeline

This isolated development slice implements a synthetic source, lifecycle coordinator, bounded RGBA processing, privacy masking, an in-memory preview and metadata-only commit audit. It does not acquire real video, persist image files or upload content. It is not wired into the Android development app and is not included by the application's existing build scripts.

The concurrent-frame ordering defect found during premerge review is corrected in `bc6599c`. See [the fix handoff and reproducible regression results](FRAME_ORDER_FIX.md); historical review evidence is preserved separately. Native adoption and real-device gates remain closed.

Baseline: frozen Sidecar PRD v1.5, Library version 0, canonical ID `PRIVATE_ARTIFACT_REFERENCE_OMITTED`. The prior session read all 1,021 lines through Library; no PRD copy was reconstructed here. This slice adds no PR/T IDs and uses only existing recording-state meanings.

## Run and evidence

From this worktree:

```powershell
.\android\uvc-sim\run-checks.ps1
```

The script compiles Java 8-compatible classes using JDK 17, runs the three JVM check programs, then compiles the read-only Android discovery adapter against API 35. Each run uses a fresh ignored output directory to avoid stale classes. Defaults are `E:\tools\jdk-17.0.19+10` and `E:\tools\android-sdk`; override with `-JdkRoot` and `-SdkRoot`. No Gradle dependency download, APK build, adb command, emulator, native library loading or USB access occurs.

Latest observed result: **324 assertions passed**: coordinator 204; frame pipeline 77 across 10 scenarios; integrated flow 43 across 3 scenarios. These are implementation checks, not additional acceptance IDs or 324 independent scenarios. See [VALIDATION.md](VALIDATION.md) for the actual output and its limits.

## Isolation and review

The worktree is `C:\Users\USER\Documents\Codex\2026-11-10\task-4\sidecar-uvc`, branch `codex/uvc-simulation`, based on `496afd3936c7a601a5fcb34af4a4820aa76d51f3`. Only `android/uvc-sim/` is added. The primary worktree's audio/retention edits, application services, schemas, database, manifest and main build scripts are untouched.

This replaces the earlier metadata-only proposal as the next reviewable development slice; it does not automatically merge it or convert the microphone service into a desktop service. The integration owner owns shared APIs and the final merge. Do not copy the test fake into the production app. The prior downloaded AAR is not copied into this worktree or linked into any build.

## Lifecycle and publication contracts

`UvcDriver` defines synchronous open/negotiate/start/stop/close operations. One serial device worker owns every operation and returned handle. `SyntheticUvcDriver` supplies explicit blocking/failure injection and late callbacks without any real device. A failed open must not leak an unreturned handle; a successful close guarantees acquisition has stopped by the driver contract. These are synthetic assumptions, not verified native-driver properties.

`UvcCoordinator` requires explicit selection, a matching permission revision, explicit open, actual-format/display confirmation and explicit start with the caller's shared gates satisfied. Opening is not recording. It issues a `CaptureGate.Lease` containing session, attachment, permission revision and capture generation. The production consent/identity/resource/notification gates still need a real integration; a fixture boolean is not their implementation.

Stopping first cancels admission under the coordinator/gate locks, independently of device or media work. Queued opens/starts recheck cancellation; an executing operation may finish, but its handle is then owned by cleanup. Only drained tasks and a successful close confirm a paused/stopped receipt. A blocked or failed close leaves recovery pending and prevents another selection. Stop is attempted once and close once per handle; if stop throws but close succeeds, the driver contract permits final closure. A failed close is not blindly retried because its outcome may be uncertain.

External requested/observed times do not impose ordering on control acceptance. Accepted time comes from the injected clock inside the coordinator; exceptions, negative values or backward time produce `-1` and a clock-uncertainty indication after admission is closed. They cannot cancel the stop. Receipt confirmation can be true with unknown timestamps: it means the driver acknowledged closure, not that its effective time was measured.

A duplicate control ID returns its existing receipt. Completed detach does not create another pending close. A later STOP can dominate an in-progress PAUSE. Old connection callbacks, tokens and controls cannot authorize or interrupt a newly selected connection. Format change automatically triggers source interruption; resource exhaustion finishes as the existing protective-pause state. Neither permission recovery, reattachment nor process recreation automatically starts a source. Display confirmation is manual and cannot detect same-format HDMI topology changes.

## Frames, ownership and bounded memory

`FrameSink.accept(lease, frame)` transfers the exclusive pixel array even if rejected. The driver must not reuse it afterward. Callback frames before start acknowledgment, with stale handles/leases, invalid boot/timing/sequence, or changed format are rejected and cleared by the coordinator. Last admitted sequence/reception time is diagnostic metadata, not a hardware exposure boundary. The current fixture does not reconstruct missing device sequences or prove source continuity from those values.

`FramePipeline` accepts the fixture's 640×360 RGBA format, queues at most two frames, and performs masking/hashing on its own worker. These are fixed laboratory settings, not PRD production defaults. The configured rectangular mask is applied before any preview or simulated commit. The commit audit contains only generation, sequence, host-reception time, reason and masked-frame SHA-256. It has no raw image or file output. The preview retains only the newest redacted frame and exposes a gated single-pixel/hash read for verification rather than a raw array.

Publication, preview reads and cancellation share `CaptureGate`'s short lock. Its `publish` callback is restricted to trusted pointer/metadata swaps: no driver call, hashing, pixel copy, disk operation or arbitrary external callback belongs there. Media processing never holds this lock. A pause can therefore invalidate a frame while masking/hashing or a test hook is blocked; final publication checks the lease again. Cancellation clears queued frames and the preview; the in-flight worker releases its frame when it resumes. A suspended worker may still hold an inaccessible in-memory array; zeroing managed arrays is not a forensic-erasure claim.

Queue exhaustion closes admission, records the rejected and discarded queued/in-flight sequences, and tells the coordinator to stop. Processing faults do likewise. Notifications run outside media/publication locks; failure to notify is counted and does not reopen admission. A cleared, previously committed preview is not falsely counted as a lost capture frame. `close()` atomically disables publication and removes the preview, then drains the queue; repeated close does not revoke a later unrelated lease.

The pipeline reserves at most **7 MiB of owned pixel arrays**. The controlled `emitGenerated` source serializes allocation and transfer and limits its one generated frame to 1 MiB, giving a conservative 8 MiB frame-data envelope for that source/pipeline combination. This excludes JVM/runtime objects, metadata/hashes and unrelated test allocations. Arbitrary external factories or concurrently preallocated fault-injection frames are not covered by that budget. The observed owned-buffer peak in the pipeline checks was 3,686,400 bytes. Ownership reservations are released after clearing arrays, and tests assert zero owned bytes after draining.

The in-memory audit is limited to 128 records. `auditEvicted` explicitly reports overwritten records; consumers should drain it during longer fixtures. It is not durable gap history. Pipeline drops include sequence/reason records; driver rejection reasons and complete cross-run gap projection still need integration with the application's common timeline/control layer.

## What the checks prove

- Permission denial and closed shared gates make no start call; connections remain ready until an explicit confirmed start.
- Older requested stop times, faulty clocks, open/start blocking, callback races and slow sinks cannot undo the admission barrier.
- Close failures do not produce a successful pause or reopen; repeated controls do not double-release handles.
- Masked pixels and full-frame hash match an independently constructed expected image; raw image hash differs.
- Stops during pre-mask or pre-publication work reject late publication and release buffers after processing resumes.
- Overflow stops the simulated source while the processing worker is still blocked, with gap evidence for all four affected frames.
- A new connection requires a new explicit start; old callbacks are cleared and leave the new lease intact.
- Shutdown during publication cannot resurrect preview ownership or leak the reserved buffer.

Partial evidence maps to existing T01, T07, T08, T27 and the resource-pressure aspects of T06. No complete T/G exit is declared. These deterministic interleavings replace the earlier strategy of repeatedly running the same metadata trace; they are not an exhaustive concurrency proof.

## Remaining integration and device gates

The next integration must bind the coordinator to shared persisted control events, single-active-session arbitration, identity/permission revisions, source health/gap projection, real USB permission receipts and runtime revocation. `AndroidUvcDiscovery` only enumerates descriptors and current permissions; it neither creates stable attachment epochs nor owns a receiver, requests permissions or opens devices. Its API 35 compile check is not an Android lifecycle test.

No change-selection algorithm, encrypted video storage, photo handling, UAC route, foreground USB service, real preview UI, upload, OCR or ASR is added. The user's chosen in-memory endpoint intentionally excludes these larger integration changes. In particular, successful synthetic publication is not durable media submission.

See [LICENSE_REVIEW.md](LICENSE_REVIEW.md) for the separately updated fixed-source/binary assessment. Prefer further study of a pinned UVCAndroid source build; the existing published AAR has provenance/dependency/permission differences that are not cleared. Native libraries, device support, actual stop latency, 16-KiB runtime loading, power/thermal behavior, timestamps and 2/8/12-hour operation remain unverified. The application's supported-device gate stays closed. No physical phone or capture card was operated, and nothing was published or deployed.
