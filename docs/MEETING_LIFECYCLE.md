# Meeting consent, audio binding, controls and guarded preview

Implementation under frozen v1.5 sections 3.7, 6.2 and 7. Existing acceptance scenarios are exercised in subsets; no PRD features, states or test IDs were added.

## Delivered

- `NoteStore.createInPerson` encrypts the meeting name and explicit operator declarations. Each declaration records its timestamp, local identity, observed input name, local-only processing, seven-day audio retention and no cloud permission. The statement is not proof of participant or organizational permission.
- `MeetingSessionRepository` binds the meeting and audio session to the same UUID. Provisioning writes encrypted meeting data before permitting audio; failure tombstones the partial meeting. A fresh declaration is recorded before each explicit resume. Its revision is bound to the audio generation in the same SQLite transaction that admits capture. Reusing a consumed declaration fails. Raw frames retain their authenticated generation, so the consent revision can be traced without treating physical offsets as identity.
- `CaptureService` provisions or resumes through this repository. It exposes only a process-local status snapshot to the control page and keeps notification controls scoped to the live token. No restart, Activity restore, page navigation or permission callback starts capture implicitly.
- `RecordingActivity` displays meeting identity, paused/ended/recording state, available submitted audio duration, consent count, quality uncertainty and manual notes. The live branch shows admitted sample duration and input energy, explicitly distinguishing energy from intelligibility, plus free space and battery. It provides pause/end controls, explicit return to preparation for resume, post-meeting notes and whole-meeting deletion. The audio store is authoritative for the audio end state; supplementing notes does not move audio boundaries or restart capture.
- Meeting deletion first persists the existing shared tombstone, requests active-source shutdown, removes both meeting and audio Keystore keys, and blocks future sample admission, reads, replay and reprovisioning. Recovery tombstones indexed frames and removes managed segment files. Old encrypted meeting files do not override the tombstone. Deletion and frame/key operations have a shared process-local ordering lock, so acknowledgement cannot race a later commit of a previously admitted frame. This ordering may wait for current I/O; no latency claim is made.
- Private content clears on leaving the page, screen lock, expiry/time-change handling or observed deletion. Existing note retention and version guards remain in use. Note supplements preserve their own timestamps and deadlines.

## Next chain implemented: explicit local preview

`AudioFrameReplay` reads one authenticated encrypted frame and writes at most 100 ms of PCM per output call, checking lock, deletion and retention before each call and while draining queued output. Owned PCM is cleared on completion/failure. Preview requires an explicit confirmation warning about possible speaker output. It does not join discontinuous streams or imply full-meeting coverage, and it never opens a microphone. Leaving the page, stopping preview or privacy deletion cancels it. Other edits wait for preview to stop so the exclusive audio-store owner is not bypassed.

Automated replay checks use an in-memory output, including deletion during the first output chunk and inspection of cleared PCM. The real AudioTrack backend only compiled: no speaker output, route, audio-focus interaction, playback-head behavior, or actual acoustic stop latency has been validated.

## Coverage and limits

`MeetingSessionChecks` covers encrypted declaration/name persistence, shared meeting/audio IDs, fresh resume declarations, old-token rejection, native paused/end controls, no capture on page entry, post-end note supplement without audio changes, page hiding after deletion, destruction of the audio key, stale ciphertext restoration, deleted-session refusal, active-buffer deletion, guarded preview, and injected concurrent commit/deletion ordering. These are synthetic samples on a disposable emulator.

The real-recording release gate stays **false**. Positive microphone FGS startup, the live UI branch, real notification stop taps, physical input levels, phone calls/competing recorders, battery/thermal/storage thresholds, full explicit test-recording flow, and measured stop behavior remain unverified. This slice does not pass G2/G3. There is no network permission or upload/model path, and no new dependency was introduced.

Remaining engineering work includes eliminating blocking I/O from user-facing control paths while preserving ordering, complete session-end timestamp projection into meeting metadata, background TTL cleanup and conservative cross-boot clock recovery. Audio state is currently canonical; the legacy note `ended_at` field is not used to infer audio state. The encrypted meeting container retains declaration history with its meeting metadata; a separate per-declaration retention/cleanup policy is not yet implemented. Whole-database rollback, filesystem metadata power-loss guarantees and forensic flash erasure are outside the demonstrated results. The release gate must not be removed until the applicable frozen requirements are complete and physical evidence exists.

Open-source choices remain those in [OPEN_SOURCE_REVIEW.md](OPEN_SOURCE_REVIEW.md). No Screenpipe code, UVC native library or ASR model was imported in this slice.
