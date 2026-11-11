# Concurrency, recorder stop ordering and retention clocks

Implementation review for the frozen v1.5 requirements. This work adds no PRD feature, state branch or acceptance-test identifier. The final build and emulator regression for this change completed successfully; see [VALIDATION.md](VALIDATION.md) for this run's results and [GATES.md](GATES.md) for stage decisions. Software verification does not establish real-device acceptance.

## Note mutations across store instances

`NoteStore` now uses the shared `MeetingPrivacy.LOCK` for the complete read-modify-write operation, including reading the current revision, checking the caller's expected revision and persisting the replacement. A lock on each `NoteStore` instance could allow two instances to accept the same revision and overwrite one another. The shared boundary makes the second writer observe the first writer's revision and report the existing conflict instead.

Meeting deletion uses the same privacy boundary. A stale instance cannot recreate a deleted meeting through an append. The guarantee is within the application's process; this change does not establish a general multi-process transaction protocol.

Code: [NoteStore.java](../android/app/src/main/java/local/sidecar/copilot/NoteStore.java), [MeetingPrivacy.java](../android/app/src/main/java/local/sidecar/copilot/MeetingPrivacy.java).

## Stop input before sealing a frame

`AndroidPcmSource` delegates recorder lifetime to `PcmSourceLifecycle`. Its explicit close and source-exception path stop the hardware recorder before calling the coordinator's pause operation, which can seal and persist the pending frame. The helper does not hold its lifecycle monitor during that persistence. Repeated stop and release calls are idempotent, and release follows stop.

After requesting stop, close waits up to one second for the source worker. If the worker remains alive, close reports the timeout and leaves release ownership with that worker. It does not forcibly release an `AudioRecord` that the worker may still be using. The worker's `finally` block clears its PCM buffer and releases the recorder.

This ordering improves the path once execution reaches the source stop call. It is not a measured bound on user-action-to-hardware-stop latency: `CaptureService` still performs I/O on its main thread, and `AudioCaptureCoordinator` failure recovery can perform I/O before control returns to the source. Both can delay the actual stop. Separating the control path from persistence remains unfinished work under the existing requirements.

Code: [AndroidPcmSource.java](../android/app/src/main/java/local/sidecar/copilot/AndroidPcmSource.java), [PcmSourceLifecycle.java](../android/app/src/main/java/local/sidecar/copilot/PcmSourceLifecycle.java), [AudioCaptureCoordinator.java](../android/app/src/main/java/local/sidecar/copilot/AudioCaptureCoordinator.java), [CaptureService.java](../android/app/src/main/java/local/sidecar/copilot/CaptureService.java).

## Replay admission and output share one boundary

`AudioSegmentStore.writeReplayChunk` holds the store monitor and `MeetingPrivacy.LOCK` while it checks whether the frame remains replayable and submits that chunk to the output. This removes the gap in which meeting deletion could complete after the permission check but before submission. Range deletion is ordered by the store monitor. Once the applicable deletion barrier wins, a later chunk cannot pass admission.

The platform output uses `AudioTrack.WRITE_NON_BLOCKING`. A zero return means no progress: replay leaves the privacy critical section, waits five milliseconds and retries admission. Partial writes advance only by the accepted sample count. Two seconds without progress aborts replay; a successful write resets that deadline. Buffer-space waiting therefore does not deliberately hold the privacy lock. Admission itself still includes store checks and can persist a newly observed wall-clock advance, so the entire operation is not claimed to be free of I/O.

Samples already accepted by Android's audio queue are outside this admission boundary. Stop attempts to pause, flush, stop and release the track, and replay clears its owned PCM array on exit, but neither action promises to retract sound that has already been queued or emitted. Speaker latency and deletion-to-silence timing need physical-device evidence.

The API behavior was checked against Android's official [AudioTrack source](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/media/java/android/media/AudioTrack.java): nonblocking writes submit available data without waiting for buffer space and may accept less than the requested amount. This is an API reference only; no source code or new dependency was imported.

Code: [AudioFrameReplay.java](../android/app/src/main/java/local/sidecar/copilot/AudioFrameReplay.java), [AudioSegmentStore.java](../android/app/src/main/java/local/sidecar/copilot/AudioSegmentStore.java).

## Same-boot retention time without a write on every poll

`AudioSegmentStore.trustedNow` now calls `RetentionClock.anchoredNow`. Within the same boot, the persisted wall/elapsed anchor projects a lower bound for the current time:

```text
projected = anchor.wall + (current.elapsed - anchor.elapsed)
effective = max(current.wall, projected)
```

Ordinary monotonic advancement at or behind that projection does not rewrite the SQLite clock row. The durable anchor already represents that advancement, including after a process restart. Every observed wall-clock advance beyond the projection must be persisted before returning; otherwise a subsequent rollback could erase evidence of that advance. This is a conditional reduction in writes, not a guarantee that all replay polls are read-only.

Rollback validation compares the current wall clock to the current projection with a two-second quantization tolerance. Comparing only with the old persisted wall value would miss a rollback during a long interval without updates. The projected comparison therefore remains stricter after a long gap. Monotonic-clock rollback, arithmetic overflow and a different boot continue to fail closed. This change does not provide cross-boot time recovery or an external trusted-time source.

Code: [RetentionClock.java](../android/app/src/main/java/local/sidecar/copilot/RetentionClock.java), [AudioSegmentStore.java](../android/app/src/main/java/local/sidecar/copilot/AudioSegmentStore.java).

## Verification design and remaining coverage

The following test sources exercise the existing requirements. This change's final regression completed with 76 JVM checks, 180 development-package emulator checks and 33 independent synthetic foreground-service checks passing. [VALIDATION.md](VALIDATION.md) records the completed run and artifacts; these results do not establish a stage exit.

| Test source | Designed check | Evidence boundary |
|---|---|---|
| [NoteConcurrencyChecks.java](../android/tests/NoteConcurrencyChecks.java) | Hold the first instance after its revision check; ensure a second instance cannot enter the same mutation, then observe one save and one conflict. Verify explicit retry and rejection after deletion. | Deterministic application-process contention with synthetic notes. |
| [PcmSourceLifecycleTest.java](../android/tests/PcmSourceLifecycleTest.java) | Block frame sealing and assert stop already occurred; exercise seal failure, concurrent stop/release, repeated release and an already-stopped recorder. | Injected recorder callbacks on the JVM; no hardware timing or `AudioRecord` worker-timeout validation. |
| [ReplayAdmissionChecks.java](../android/tests/ReplayAdmissionChecks.java) | Hold a fake output inside admission while deletion attempts to run; verify ordering and reject a later output after deletion. Exercise zero returns, partial writes and owned-buffer clearing. | Synthetic PCM and output; the intentionally blocked fake exposes the race window and does not model a production nonblocking sink or a speaker. Persistent zero-progress timeout and physical queue drain still need focused coverage. |
| [ReplayAdmissionChecks.java](../android/tests/ReplayAdmissionChecks.java) | Check repeated admission against a controlled anchor causes no SQLite changes, while a forward wall jump is persisted; reject rollback and cross-boot reuse. | Emulator database and controlled anchor values, not system clock or reboot qualification. |
| [NotePolicyTest.java](../android/tests/NotePolicyTest.java) | Check long-gap monotonic projection, the two-second tolerance boundary, forward advance, cross-boot rejection, elapsed rollback and overflow. | Pure clock arithmetic; no physical clock qualification. |

The supported-device capture gate remains false. Emulator checks and synthetic foreground-service checks do not establish real microphone capture, route correctness, stop latency, device performance, G2 completion or G3 completion. Main-thread service I/O and coordinator failure-recovery I/O remain material blockers to claiming a responsive real capture stop path. See [FOREGROUND_CHAIN.md](FOREGROUND_CHAIN.md) for the separate synthetic service fixture and its limits.
