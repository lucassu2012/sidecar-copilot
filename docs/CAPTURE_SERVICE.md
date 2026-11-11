# Android preparation, permission and foreground-service integration

This implements part of existing v1.5 sections 3.7, 5.4 and 7. No additional PRD features, lifecycle states or acceptance IDs were introduced.

## Current behavior

The app now has a meeting preparation page reachable from the native UI. It shows the observed built-in input, local identity space, free storage, battery, unmeasured time estimate, retention, local-only processing boundary, notification/microphone permissions and an explicit consent declaration. It never opens AudioRecord to check input level. Permission requests occur only from the user's permission button. Denial or approval returns to preparation, resets confirmation and does not start recording.

`CaptureStartGate` is an ephemeral single-use ticket: visible Activity, explicit consent, microphone access, visible notification controls, unlocked device and supported configuration must all pass. Tickets expire after ten seconds, cannot survive process death and are invalidated when the Activity leaves the foreground. The non-exported service rechecks prerequisites and the selected input before attempting capture. No intent extra alone grants recording permission.

`CaptureService` declares the microphone FGS type, promotes before opening the source, then joins the existing encrypted PCM coordinator. Its generic lock-screen notification contains separate immutable pause/end PendingIntents bound to the current session token. Old or forged stop intents cannot control another session. No meeting title, note body or transcript is shown on the lock screen. It checks notification/microphone permission availability during capture and stops on loss. Stop closes the source, flushes the admitted tail via the existing barrier and closes storage; an end action marks the session ended only after successful stopping. Failure does not claim a saved tail. The service is non-sticky and has no boot/USB auto-start receiver.

The manifest now declares RECORD_AUDIO, POST_NOTIFICATIONS, FOREGROUND_SERVICE and FOREGROUND_SERVICE_MICROPHONE. **No INTERNET or CAMERA permission is declared.** Prior documentation saying there are no recording permissions is historical. A declared/granted microphone permission is not evidence that recording has occurred.

## Deliberate release gate and remaining integration

`CaptureEnvironment.supported()` remains false. This build cannot start real capture, even if runtime permissions are granted or an internal intent is forged. This is a release-readiness barrier, not a product option or a claim of hardware support.

Do not enable it merely because the source compiles. Before enabling, complete the supported-device evidence, persisted meeting name/consent and audio-to-meeting linkage, live in-app status/timer/level and large stop controls, explicit resume handling, measured battery/space thresholds, safe interruption reporting and the PRD's test-recording/playback flow. The current service controller is an implementation slice, not a complete meeting product. Storage I/O and sealing still need stop-latency measurements and moving suitable work off the main thread without weakening admission barriers.

Android notification permissions do not establish that a particular OEM lock screen actually shows usable stop controls. Positive microphone FGS start, real sound, competing recorders/calls, app/OS kill behavior, lock-screen interaction and physical stopping latency remain unverified. The emulator tests deliberately exercise negative starts and notification objects; they are not positive microphone FGS acceptance.

## Source review

See [OPEN_SOURCE_REVIEW.md](OPEN_SOURCE_REVIEW.md) for pinned Screenpipe, UVCAndroid and whisper.cpp assessments, license findings and adoption decisions. No upstream code, binaries or models were imported. The new service uses existing Android platform APIs; this does not add an external dependency.
