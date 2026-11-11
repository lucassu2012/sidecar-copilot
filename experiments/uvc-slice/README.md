# UVC isolated development handoff

2026-11-10. Status: a tested lifecycle proposal and read-only Android discovery adapter, **not completed UVC capture integration**. No phone, USB device, microphone, speaker, camera, emulator, external model, publication or deployment was operated. The application’s real-device gates remain unchanged.

## Isolation and baseline

Source repository inspected: `C:\Users\USER\Documents\Codex\2026-11-10\task\sidecar-copilot`. `git status --short` showed all application directories untracked; `git log -1` reported no commits on master. Creating a normal worktree from HEAD was therefore unavailable. This package is entirely under `task-4\uvc-slice`; the source repository was only read. No common interface, manifest, service, build script, database or acceptance table was changed. The integration owner should review these new files before choosing their destination; do not bulk-copy this directory into the application source tree.

PRD was read through Library read, version `0`, lines 1–1000 and 1001–1021, with the bounded text reviewed in sections. Canonical ID: `PRIVATE_ARTIFACT_REFERENCE_OMITTED`. No reconstructed PRD file was saved. Requirements remain v1.5, with no new PR/T identifiers or product state branches.

## Files and behavior

| File | Responsibility |
| --- | --- |
| `src/local/sidecar/copilot/uvc/UvcLifecycle.java` | Bounded, synchronized metadata protocol; explicit selection, permission results, open/start acknowledgments, generation invalidation, stop/close acknowledgment and explicit reconnection |
| `android/local/sidecar/copilot/uvc/AndroidUvcDiscovery.java` | Read-only `UsbManager` enumeration; recognizes video control/streaming interfaces in composite devices; returns current permission metadata |
| `test/UvcLifecycleChecks.java` | Pure JVM synthetic checks, including a blocked fake open worker and late callbacks |
| `run-checks.ps1` | Java 8-compatible compilation on JDK 17, checks, API 35 compilation of discovery; no install or adb commands |
| `LICENSE_REVIEW.md` | Fixed-source and fixed-artifact assessment, native/dependency findings and remaining adoption blockers |
| `audit/` | Public candidate AAR/POM/module downloaded only for inspection; not linked or executed; exclude from app packaging |

An attachment is a newly assigned enumeration identity, not VID/PID or USB path alone. The future Android bridge must give each detach/re-enumeration a fresh identity even when the physical path is reused. Selection is explicit; enumeration does not open, request permission, start preview or start recording. USB authorization is separate from recording consent and the shared start gate.

An asynchronous operation carries an identity capability from this lifecycle instance. A stale permission, open or start callback cannot reactivate a cancelled run. A stop invalidates frame admission before the external driver is asked to stop and close. A blocked driver cannot hold this protocol’s monitor. The model does not show PAUSED/STOPPED until the caller confirms the pending open/start operations have drained and every owned handle is closed. A close failure leaves recovery pending and blocks another selection. A later STOP dominates an already pending PAUSE/interruption. Close acknowledgment is a caller contract, not evidence this prototype has physically stopped a camera.

Negotiated format is an exact value checked against manual display confirmation. A frame with a changed format is rejected and invalidates admission; the caller must dispatch close using the resulting barrier. Reconnection requires selection, current permission, format/screen confirmation and an explicit start again. An unrelated device detach does not interrupt this selected source. Process recreation starts unconfigured; no start token is persisted. `hostReceiveMonoNs` is reception metadata with its own boot domain, not an exposure time or a calibrated video timestamp. Duplicate, pre-start, future and mismatched-boot frame observations are rejected.

The lifecycle is intentionally metadata-only: `observeFrame()` returning true is **not** an authorization token to save pixels later. No pixels are acquired, persisted or uploaded by this package. A real sink must atomically recheck the shared capture/deletion generation at preview, queue and final commit boundaries. A real driver must retain unique handle ownership and idempotent cleanup, including late successful opens; it must not close an already adopted active handle merely because a duplicate callback was rejected.

## Integration boundary for the main conversation

The existing `CaptureService`, `AudioCaptureCoordinator`, meeting repository, `CaptureStartGate`, shared schemas and recording UI remain owned by the integration conversation. These classes do not replace their consent, identity, single-active-session, resource, privacy-pause, lock-screen or retention checks.

Before connecting a real or emulator USB driver, the owner needs to bind the protocol to the common `ControlCoordinator` semantics: durable control event/session/stream identity, requested/accepted/effective times, capture generation, stop receipt, upload pause and gap projection. Snapshot flags describe pending implementation operations; they are not new user-facing PRD states. Record previous-run last trusted observation and unknown gap before selecting a new run. Do not interpret a fresh frame as proof that a different screen or HDMI route is still authorized.

Required Android bridge work remains:

1. A lifecycle-owned attach/detach receiver plus initial enumeration and safe unregister. Scope permission PendingIntents to the application and unique attempt, use current platform requirements, and recheck `UsbManager.hasPermission()` and attached identity on callbacks. USB device names and descriptors are not authentic identity proofs.
2. Choose and qualify one native adapter. Open/start/stop/close must have single ownership, a dedicated device worker, cancellation outside blocking I/O, bounded buffer ownership and exactly-once release. No upstream auto-open sample flow may bypass consent. A hung driver leaves stop uncertain; do not promise a hardware deadline.
3. Negotiate actual format and propagate source health, timestamps, sequence discontinuities, queue loss and backpressure gaps. Mask before persistence, then feed the existing frame-selection/encrypted-store path with repeated gate checks. No native preview/snapshot/log file may become a separate ungoverned persistence route.
4. Bind only appropriate Android foreground-service types and actual permissions to the chosen OS/target/API. The current app has microphone-specific wiring; simply adding connectedDevice or camera declarations is not integration. Review visibility, lock-screen stop, lifecycle and background restrictions for the actual path.
5. Keep UAC independent: enumerate/qualify audio route separately. UVC availability never means four-way audio readiness. A detach never activates the phone microphone or implements the offline-meeting shortcut by itself; that shortcut belongs to the shared meeting coordinator.

The official [USB host guide](https://developer.android.com/develop/connectivity/usb/host) supports the enumeration/permission/lifecycle design. This is an implementation inference from API documentation, not a tested device-support claim. Recheck [foreground-service type requirements](https://developer.android.com/develop/background-work/services/fgs/service-types) when the actual adapter and target are fixed.

## Validation

Run from this package: `powershell -File .\run-checks.ps1` (or invoke the script in PowerShell). Default tools: `E:\tools\jdk-17.0.19+10`, `E:\tools\android-sdk\platforms\android-35\android.jar`.

Observed result: **12,060 assertions passed**, including 1,000 repetitions of the same deterministic detach/pending-start sequence. This count is not 12,060 distinct scenarios, not a random-fault campaign, and not additional PRD test IDs. The single blocked-open worker test confirms metadata stop does not await an external worker; it does not measure hardware stop latency. Android discovery compiled against API 35; it was not run on a device. No APK was built or installed and the existing application suite was not rerun because no application file changed.

Coverage is partial implementation evidence for existing T01 (no source/permission rejection), T07 (detach/recreation), T08 (late callbacks/stop admission) and T27 (observable format change). T02, T24, T29/T31 and T41 remain integration/device work. Same-format extension-to-mirror switching is not observable here and cannot be claimed detected.

No throughput, pixel quality, native ABI loading, buffer-copy cost, CPU/heap/power/thermal, USB bandwidth, 16-KiB ELF compatibility or 2/8/12-hour result is claimed. This protocol keeps constant-size metadata for one selected source/run and performs no media allocation; that is a code property, not a measured camera resource profile.

## Physical acceptance still open

Keep G1/G2/G3 gates closed. Select a phone/build, capture-card/firmware, powered hub, cable and power supply before any separately authorized physical test. Verify selected-screen identity, unchanged input/loop-through timing, scaling/aspect/font readability, negotiated compression/fps, host+charging, disconnect/reconnect/suspend, permission revocation, visible foreground/lock-screen stopping, sustained thermal/power behavior and true driver stop delay. Validate frame timestamp/queue uncertainty against synthetic visual/audio references. Validate four-way audio independently. README claims and the presence of an AAR do not establish any supported combination.
