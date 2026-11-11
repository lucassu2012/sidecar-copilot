# Android upload source interface checkpoint

This checkpoint implements the first Android source adapter prerequisite identified by `task-2/android-upload-review/HANDOFF.md`. It does not add PRD features, states or acceptance IDs, and does not connect Android to a network sender.

## Implemented source boundary

`AudioSourceReference` is an immutable local value containing the original frame/session/stream IDs, capture generation, Android boot count, half-open sample positions, host receive-completion nanoseconds, eligibility nanoseconds, expiry UTC milliseconds, ciphertext length/hash, AAD hash and PCM format. It contains no sample-rate guess, derived capture start, segment/offset, raw key, plaintext or transport authorization. Checked arithmetic rejects malformed lengths, the wrong 900-second fence and overflow. `requireMature` checks only supplied time prerequisites; it cannot establish clock trust or authorize sending.

The package-private `AudioSegmentStore.inspectUploadSource` reads the local frame under the existing store and meeting-privacy locks, obtains a metadata snapshot, and reuses the full existing RSA/AEAD read. A checksum by itself is not treated as authentication. The temporary authenticated PCM is cleared in `finally` before returning a description, including exception paths. The method then checks current ciphertext consistency, deletion, corruption, clock/boot/expiry, and the immutable snapshot again. A private fingerprint also binds nonce and wrapped-key identity across authentication; these parameters are not exported into the reference.

The reference may be inspected before it becomes upload-eligible. Inspection does not skip the buffer or provide a sender admission ticket. A later worker must reacquire and revalidate live authorization, deletion and source state at the actual handoff. References contain stable identities rather than physical offsets so compaction does not define a new source. No local or network caller is allowed to treat this object as proof of cloud consent.

## Representation deliberately preserved

Android frames retain per-stream sample indices and a host receipt time. They do not retain an independently usable capture-start mapping for historical streams. `sessions.start_ns/start_utc` changes when a new stream starts; multiple frames can share one callback's receipt time. Therefore subtracting a frame duration, using the previous frame's receipt, or reading the current session origin would invent a start time.

The existing receiver v1 Manifest requires monotonic source intervals, while Android deletion is scoped to a stream and sample range. The v1 deletion request has no stream field. This adapter does not manufacture a wire Manifest or silently broaden a local deletion to a session-wide time range. Note UTC milliseconds and Android boot count also do not become a trusted monotonic/device identity merely through numeric conversion.

The current host protocol is documented separately in [TRANSFER_WIRE_V1.md](TRANSFER_WIRE_V1.md) and [transfer-v1.schema.json](../contracts/transfer-v1.schema.json). The old local frame schema and local synthetic API are kept distinct. This documentation/schema version does not introduce a new wire header, endpoint or protocol negotiation.

## Validation and exact scope

- Pure Java value-contract run: **214 checks passed**, strict JDK 17 `--release 8 -Xlint:all -Werror` compile and execution exit 0. [Exact two-file evidence](../android/build/audio-reference-4bf0239a5f9c4bcc93b43638c066fb08/RESULT.json). These check units, half-open scope, malformed identity, expiry/maturity, overflow and equality; they do not exercise Android storage or authentication.
- Final combined compile: **59 Java sources**, comprising 26 production and 33 test sources, plus the test build script in a **60-input** manifest. Android API 35/JDK 17 compilation and the host wrapper exited 0, without source drift. [Result](../android/build/audio-reference-integration-20261110T184531Z/result.json), [inputs](../android/build/audio-reference-integration-20261110T184531Z/inputs.json), [command](../android/build/audio-reference-integration-20261110T184531Z/command.json) and [archive index](../android/build/audio-reference-integration-20261110T184531Z/index.json).
- `AudioSourceReferenceChecks` is registered in `StoreInstrumentation` and `build-tests.ps1`. It prepares actual Android GCM, retained-PCM clearing, old/new stream identity, deletion-after-authentication, changed ciphertext with a recomputed checksum, changed AAD/metadata, and changed nonce/wrapped-key cases. **These instrumentation checks have compiled but have not executed.** There is no new APK or device result.
- Independent review found the initial post-authentication snapshot omitted nonce/wrapped-key identity and that the first metadata test only exercised AAD inconsistency. The private fingerprint and precise post-authentication negatives correct those omissions. The review verified source paths; it did not execute Android assertions.
- The earlier `audio-reference-integration-20261110T184402Z` compiler exited 0, but the enclosing Python wrapper exited 1 while interpreting the compiler's Windows-encoded stderr as UTF-8. That evidence remains unchanged. The final run records raw stdout/stderr bytes and an actual wrapper exit 0; it also includes the later fingerprint fix.
- Wire schema/documentation validation: **139 checks passed, exit 0**, covering schema structure, local references, positive/negative values, strict-number/cross-field limitations, hash vectors, request mappings, error codes and the four unchanged production module hashes. [Execution receipt](../android/build/transfer-wire-v1-validation-20261110T184727Z-98d95b97/receipt.json). The initial AST inspection accidentally selected the client's same-named method; its failure is preserved in `PRIOR_PROBES.md`. This validation does not execute Android or a receiver service, and does not add to the earlier 173 runtime test total.

| Current Java file | SHA-256 |
| --- | --- |
| AudioSourceReference.java | `0ccd8094dc62662eb0b9652e38aad030dbb57ff929b2b7c3d67abcc315797725` |
| AudioSegmentStore.java | `cda27813178196dc752f96e38d1b5d0c046ecb0a82cb603f9aadda6d054b5a38` |
| AudioSourceReferenceChecks.java | `ee9e15062e01aee7601ea35e828c589e3c7066a5a681ee6ceead55dfe0597a74` |

All 12 Python production files still match the earlier [173-test checkpoint](DURABLE_TRANSFER_VALIDATION.md). That is retained Python evidence, not a rerun or Android acceptance. The new store source supersedes the earlier Android source hash; neither the old 56-source compile nor F134 runtime qualifies this new method.

## Next integration boundary

Before Android egress can be connected, the source/deletion range representation must preserve original stream/sample coordinates or supply actual capture-time mapping evidence. Then Android needs a shared durable transaction for local barriers and upload/delete intents, final-send admission, persistent scheduling/status reconciliation, authorized OS network/unlock/account inputs and product callers. These are still absent. Unknown outcome must retry the existing persistent deletion identity; a new request ID is not the same retry. Receiver cleanup and processing-cancel receipts must remain distinct.

The new Android source tests require a freshly built candidate, authenticated synthetic Android runtime and version-bound receipts. No emulator, physical device or live input was used here; the existing real-capture gate and absence of Android network upload remain unchanged. The parallel UVC experiments are reviewed separately and have not been merged into the application.
