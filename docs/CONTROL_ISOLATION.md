# Capture control isolation and authenticated note retention

Implementation record for the frozen v1.5 scope. These changes address existing stop, interruption, deletion, lifecycle and retention requirements; they add no PRD feature, state branch or acceptance identifier. The final integrated build and emulator regression completed successfully. [VALIDATION.md](VALIDATION.md) is the authority for completed results and tested artifacts; [GATES.md](GATES.md) records stage decisions.

## Separate cancellation, device control and persistence

`CaptureService` separates three responsibilities:

| Execution context | Responsibility | What it does not establish |
|---|---|---|
| Service main thread | Validate the explicit start/control request, maintain notifications and snapshots, latch cancellation and dispatch work. | A responsive main loop does not prove fast Android system calls or device stop latency. |
| Dedicated control executor | Call `PcmSource.stopInput()` independently of store preparation, frame sealing and store closure. Repeated requests reuse the same source stop task. | The platform recorder/driver can still block or fail. |
| Serial storage executor | Prepare the meeting/store/source, then finalize pause/end, deletion cleanup and close in order. | Source PCM ingestion is not moved onto this executor; the source reader still synchronously commits frames through the coordinator. |

The service main thread no longer opens the note/audio stores or waits for frame persistence and source closure. `AudioCaptureCoordinator.stopAdmission()` increments an atomic sequence without waiting for the coordinator monitor that may be held by a blocked append. Later sample callbacks compare that sequence before accepting more data. `PcmSource.signalStop()` separately latches source cancellation without a device call, persistence or monitor acquisition.

The source checks cancellation before and after preparation and before/after starting the platform recorder. `PcmSourceLifecycle.startIfAllowed()` serializes recorder lifecycle calls and stops a start that was cancelled during the platform call. A source instance is not reused for another start. These checks prevent delayed preparation from silently undoing a stop request; the existing consent, source, visibility and supported-device gates still apply.

Code: [CaptureService.java](../android/app/src/main/java/local/sidecar/copilot/CaptureService.java), [AudioCaptureCoordinator.java](../android/app/src/main/java/local/sidecar/copilot/AudioCaptureCoordinator.java), [PcmSource.java](../android/app/src/main/java/local/sidecar/copilot/PcmSource.java), [AndroidPcmSource.java](../android/app/src/main/java/local/sidecar/copilot/AndroidPcmSource.java), [PcmSourceLifecycle.java](../android/app/src/main/java/local/sidecar/copilot/PcmSourceLifecycle.java).

## Failure and completion ordering

When a sample append fails, the coordinator first closes admission and invokes the source stop callback, then discards its assembler and attempts persistent pause recovery. The source failure callback notifies the service and requests/stops input before recovery writes. Recovery therefore cannot be the prerequisite for issuing a hardware stop request. Explicit close still stops input before sealing and preserves the worker's release ownership if the worker does not exit within the join wait.

`NoteStore.delete()` validates the meeting identifier and signals input stop before waiting for the shared privacy lock. A blocked encrypted append holding that lock therefore does not prevent the initial stop signal. The durable deletion barrier still commits under the privacy lock before key/file cleanup; the earlier signal does not mean that deletion has already committed or cleanup has completed.

During shutdown, an already-published service snapshot becomes `stopping`. The recording UI disables capture controls, the notification removes its actions, and the service rejects subsequent start/control commands while finalization is pending. For active-service END, the monotonic request time is captured at the control entry and carried into `AudioSegmentStore.end`; a delayed storage commit does not replace it with the later commit time. The recorded sample boundary remains distinct from that request time and from any unmeasured physical stop latency.

Notification construction/update failure cannot skip final storage cleanup: shutdown dispatches it from a `finally` block. The existing active service identity and published stopping snapshot remain until the cleanup path has completed or reported failure and the service is destroyed. A pending store operation can keep finalization pending while the independent control executor has already received its input-stop work. The UI must not describe this interval as confirmed saved audio.

There are two precise boundaries to this status: preparation cancelled before a snapshot is first published can still have no snapshot; its cancellation is enforced by the active service and source latch. Also, failed source closure can include a still-live reader after a join timeout. The failure is reported, and that worker retains release ownership; service destruction and a cleared snapshot do not prove that every native resource or physical microphone has closed successfully. `stopping` disables capture actions, not all note editing or privacy deletion operations.

Code: [AudioSegmentStore.java](../android/app/src/main/java/local/sidecar/copilot/AudioSegmentStore.java), [RecordingActivity.java](../android/app/src/main/java/local/sidecar/copilot/RecordingActivity.java).

## UI work and late results

`MainActivity` and `RecordingActivity` perform store creation, reads, mutations, end projection and managed-file cleanup on their storage executors. A recording page can show the service's in-memory status and stop controls while its meeting data is still loading. Replay-stop work also has a separate executor.

The preparation page no longer decrypts an existing meeting just to display its title. It uses the fixed label "继续已有会议"; identity and consent are resolved by the service's storage worker. Space and battery checks run in a background executor, and their UI updates check visibility, destruction and render epoch before display. Those resource observations do not qualify a physical device or grant recording permission.

Each asynchronous view operation carries a view epoch. Screen lock, time-change handling, navigation, hiding or destruction invalidates the old view so its late callback cannot repopulate sensitive content. Before display, the activities recheck current visibility, unlock status, the display deadline and applicable meeting/note deletion barriers. Barrier listeners invalidate content already on screen. These checks supplement the store's authoritative barriers; an epoch is not an authorization substitute.

Confirmed deletion remains authorized after leaving the page, while its late UI callback is discarded. Ordinary stale view work is skipped. Unsaved editor text still is not serialized into saved-instance state; this slice does not introduce persistent drafts.

Code: [MainActivity.java](../android/app/src/main/java/local/sidecar/copilot/MainActivity.java), [RecordingActivity.java](../android/app/src/main/java/local/sidecar/copilot/RecordingActivity.java), [MeetingPreparationActivity.java](../android/app/src/main/java/local/sidecar/copilot/MeetingPreparationActivity.java).

## Existing synthetic text policy and authenticated cleanup

`RetentionEnvelope` records the existing synthetic-development text policy ID/version, creation origin, absolute deadline and provenance label. New titles and independent manual notes keep separate envelopes with `policy_evidence="development_policy_snapshot"`. That value describes the development policy applied to the record; it is not evidence of a user's policy selection. The current 90-day synthetic-development policy is not an approved production default, and this slice adds no configuration UI.

Legacy migration preserves the original creation time and deadline only when their relationship can be verified against that earlier development policy. It records `legacy_deadline_only`, which is not a claim that a historical user policy selection was recovered. Missing or inconsistent origin/deadline metadata, an unsupported policy, or a media-dependent note presented as independent is rejected before its body is returned. Later notes do not extend an earlier title/note deadline; a metadata migration does not invent a new origin time.

`NoteStore.sweepExpired(BooleanSupplier)` is intended for an already-authenticated background path. MainActivity calls it before loading the authenticated meeting list. It reuses the existing read-time expiry barriers and encrypted rewrite, counts meeting containers with successful expiry cleanup, and releases the shared privacy lock between meetings. Cancellation, a locked device, unavailable authentication/key, an uncertain clock or invalid retention metadata stops the scan. Cancellation is checked at object boundaries; it does not interrupt an in-flight atomic rewrite or `fsync`. A failed cleanup is not reported as complete, and independent lawful notes are preserved.

The scan cannot decrypt while locked and does not run a hidden authentication flow. It is not an exact-deadline background deletion guarantee. Cross-boot trusted-time recovery remains unavailable, and the independent retention policy for consent declarations remains unresolved. This implementation does not assign those declarations a guessed 7-day or 90-day deadline, nor claim that later attachments now solve declaration retention.

Code: [RetentionEnvelope.java](../android/app/src/main/java/local/sidecar/copilot/RetentionEnvelope.java), [NoteStore.java](../android/app/src/main/java/local/sidecar/copilot/NoteStore.java), [NotePolicy.java](../android/app/src/main/java/local/sidecar/copilot/NotePolicy.java).

## Verification scope and remaining limits

The integrated checks are designed to stall preparation, append and recovery while issuing pause/end/deletion; exercise cancellation during recorder start, notification failure, cleanup ordering, stale controls and UI callbacks; and verify retention migration, deadline consistency, cancellation and concurrent cleanup. The new [retention envelope checks](../android/tests/RetentionEnvelopeTest.java), [native note retention checks](../android/tests/NoteRetentionChecks.java) and [UI responsiveness checks](../android/tests/UiResponsivenessChecks.java) remain implementation checks under existing PRD scenarios. Their final outcomes belong in [VALIDATION.md](VALIDATION.md), not an assumed pass here.

An initial synthetic foreground-service fixture run encountered a stale-notification observation race. The fixture now identifies the notification's service instance and uses a bounded wait before asserting the matching notification, rather than accepting a previous instance's notification. Notification-construction failure and deletion concurrent with a blocked append have additional coverage. Preparation-page and UI checks were extended after the earlier successful software runs, so those earlier results do not establish the final source revision's result; the final combined regression passed with 107 JVM, 226 native emulator and 173 synthetic FGS checks. Exact artifact hashes, intermediate failures and evidence limits are recorded in VALIDATION.md.

The design comparison is documented in [the Oboe/WebRTC control review](OPEN_SOURCE_CONTROL_REVIEW.md). It informed cancellation and ownership choices without importing source, native code, binaries or dependencies. The current reader still performs synchronous frame persistence; no bounded PCM FIFO or separate acquisition-to-storage handoff has been introduced. Slow persistence can therefore stall sample consumption, and the actual overflow/gap and timing behavior still requires work and device evidence.

No physical microphone, OEM driver or flash timing is established by injected stalls or a synthetic foreground-service fixture. A blocked platform start/stop can still delay physical stopping even when the main and storage threads are separated. Persistent I/O can leave sealing/recovery pending; this is disclosed rather than converted into a successful-save claim. The real-device capture gate remains false, and these changes alone establish neither G2 nor G3 completion.
