# Review of the UVC synthetic slice

**Decision: fix the concurrent-frame ordering defect before merging the slice.** This review found one P2 correctness defect. Two independently written reproductions expose the same defect, not two separate findings. The reviewed source remains unchanged. Native integration and real capture remain blocked independently of this finding.

## Baseline and isolation

- Reviewed commit: `b793449047021f102ec2ce59cebd9025cb57977b` (`codex/uvc-simulation`), whose parent is `496afd3936c7a601a5fcb34af4a4820aa76d51f3`.
- Review worktree: `C:\Users\USER\Documents\Codex\2026-11-10\task-4\sidecar-uvc-review`, branch `codex/uvc-quality-review`, created at the reviewed commit with a clean working tree.
- Review additions are confined to this directory: this report, an independent runner, review tests and evidence. No changes to production source, original tests, app/public contracts, manifest or main build scripts. No merge into the integration branch.
- Requirements remain frozen PRD v1.5, Library version 0, canonical ID `PRIVATE_ARTIFACT_REFERENCE_OMITTED`. The complete 1,021-line Library source was read previously; this review does not reconstruct it or add PRD features, states or acceptance IDs.
- Endpoint remains generated pixels, in-memory redaction/preview and metadata-only simulated commit. No image persistence, native dependency integration, device access, release or deployment.

## Finding: P2 — enforce ordering at the final publication boundary

Location: [UvcCoordinator.java](src/local/sidecar/copilot/uvcsim/UvcCoordinator.java), lines 198–206; publication in [FramePipeline.java](src/local/sidecar/copilot/uvcsim/FramePipeline.java), lines 145–148.

`UvcDriver.start` explicitly permits callbacks on any thread. The coordinator validates and advances `lastSequence`/`lastReceive` while holding its control lock, then calls `sink.accept` after releasing that lock. A permitted interleaving is:

1. Frame 1 passes admission, then pauses before entering the actual pipeline.
2. Frame 2 passes admission and is processed and published.
3. Frame 1 resumes delivery. It carries the same valid lease, so the pipeline publishes it over frame 2.

Both independent reproductions observed commit order **2, 1**, final unmasked-area preview pixel **`111111ff`** instead of the newer frame's **`222222ff`**, and coordinator diagnostic sequence **2**. Their assertions fail with exit code 1. The normal synthetic driver's synchronized emit methods hide this allowed concurrent-callback path.

Impact: preview and commit metadata can regress to an older frame even though coordinator admission diagnostics remain monotonic. This violates the documented newest-frame behavior and can mislead downstream chronological consumers. The reproduced frames still pass through masking; this is not evidence of an unredacted or post-stop publication.

Recommended correction: enforce a sequence/reception ordering watermark per lease at the final publication boundary, or provide bounded ordered delivery without holding the coordinator/control lock across the sink. If an older admitted frame is discarded, record the existing gap metadata and release its owned buffer. Preserve new-lease sequence reset, the stop barrier and the current memory bounds. Do not serialize slow sink work under the control lock.

Acceptance for the correction: both reviews should show monotonic commit order and the latest preview; an old frame arriving after a newer publication should be dropped and cleared; a new lease may restart sequence numbering; stop while delivery or processing is blocked must still revoke publication immediately. Re-run the existing suite and independent safety checks after the correction. These are implementation regression conditions, not new PRD acceptance IDs.

## Lifecycle and ownership review

| Area | Evidence and conclusion | Boundary that still matters |
| --- | --- | --- |
| Detach and old callbacks | Existing coordinator/integration checks pass. Current operation, handle and lease checks reject and clear old frames; old failures cannot stop a replacement operation. | `detached` compares `attachmentId`, so the caller must assign a new attachment epoch on each physical enumeration. Reusing a USB path string as the epoch is not safe. The discovery helper does not create epochs. |
| Stop admission barrier | Stop invalidates the lease before clock evaluation or asynchronous cleanup. Final publication and preview reads share the gate. Existing pre-mask, pre-publication and slow-sink checks pass. | An already executing synchronous open/start may finish after stop acceptance. The receipt waits for drain/close. No immediate physical-stop claim follows. |
| Stop confirmation | Returned handles receive at most one stop and close attempt. Confirmation requires cancelled operation, drained driver tasks and successful close. Close failure stays pending and prevents new selection. | A successful close guarantees release only by the injected driver contract. Stop throwing followed by close succeeding is accepted under that contract. No native implementation has been verified. |
| Masking | Existing independent expected-image/hash checks and new safety checks exercise redaction before preview/commit. | Fixed 640 × 360 RGBA and a rectangular fixture mask; no product UI, real format or readability acceptance. |
| Buffer ownership | Exclusive transfer, once-only release, bounded queue/reservation and stale-lease cleanup are exercised. | `awaitWorkerIdle` waits for driver tasks, not the separate `sinkControls` cancellation executor. Even combined with media-worker exit, tests must wait for asynchronous reservations to return to zero. A suspended processing frame stays inaccessible until its worker resumes and clears it. |
| Clock | Exceptions, negative time and regression close admission and preserve uncertainty in receipts. | Clock runs under the coordinator lock. The injected clock must be fast; these checks do not bound stop latency for an arbitrarily blocking clock. |

The last column records integration contracts and limits, not additional reproduced defects. No exhaustive concurrency proof or complete PRD exit is claimed.

## Reproduction and recorded results

From the review worktree:

```powershell
.\android\uvc-sim\run-review-checks.ps1
```

The runner compiles to a fresh ignored directory, executes the unchanged baseline, compiles the independent tests with Java 8 compatibility, then runs both ordering reproductions and the independent safety checks. It writes raw outputs and [checks.json](review-evidence/checks.json). **It exits 1 on the reviewed commit because the ordering regressions fail; known failures are not converted into successful checks.**

The baseline contains 324 assertions: coordinator 204, pipeline 77, integration 43. Android discovery compilation uses API 35. Those counts are assertions, not independent scenarios or added PRD tests. Test status and the exact observed buffer/timing sample are in the generated logs. The reported JVM stop-admission timing is a laboratory observation, not a device latency guarantee.

The independent safety run passes **5 scenarios / 86 assertions**: boundary redaction and full-frame hash, replacement ownership, old/new lease interleavings at both pre-mask and pre-publication boundaries, close with preview/queue/in-flight ownership, and processing plus notification failure cleanup. Both independent ordering reproductions fail as described above. The coordinator reproduction also permits serialized delivery; the pipeline reproduction deliberately requires frame 2 to make progress while frame 1 is held, and accepts explicit rejection of frame 1 afterward. If choosing a serialized-delivery repair, adapt that fixture's scheduling while preserving the monotonic output and nonblocking stop checks.

- [Baseline output](review-evidence/baseline.txt)
- [Independent coordinator reproduction](review-evidence/coordinator-review.txt)
- [Independent pipeline reproduction](review-evidence/pipeline-review.txt)
- [Independent pipeline safety output](review-evidence/pipeline-safety-review.txt)
- [Coordinator review source](review-tests/CoordinatorReviewChecks.java)
- [Pipeline review source](review-tests/PipelineReviewChecks.java)

## Native and transitive dependency gate

The [original dependency assessment](LICENSE_REVIEW.md) remains the baseline; the [independent license handoff](review-evidence/license-review.md) explains the additional findings, and [static audit JSON](review-evidence/license-audit.json) records pinned source URLs, retrieval status and hashes. [The audit script](review-evidence/license-audit.py) reads existing artifacts as ZIP/class/ELF data. It does not execute native code or resolve Gradle dependencies. The audit exited 0 with all three artifact hashes matching and 31 exact source requests: 28 HTTP 200 and the three expected libyuv HTTP 404 responses, with no unexpected retrieval failures.

The second inspection confirms:

- The existing Maven AAR/POM/module SHA-256 values match the baseline; the 2,761,961-byte AAR is `4f1295a6c6b5e8590fb7dde3c60f30a94166db45c2d07efa63c85d5da8bfff17`. Module metadata consistency does not establish its source build provenance.
- Published `FileUtils.class` contains an actual member reference to `cn/hutool/core/io/FileUtil.getMimeType(String):String`. The pinned source file, library build and publication script do not mention Hutool. Published dependency metadata adds `hutool-core:5.8.35`; its parent POM declares MulanPSL-2.0. Removing a dependency declaration alone does not repair this divergence.
- The pinned UVCAndroid tree declares libyuv version 1840, but its referenced vendored-root LICENSE, PATENTS and AUTHORS files are absent from the non-truncated tree and their exact raw URLs return 404. Exact upstream revision, vendored changes and complete applicable materials remain unresolved.
- LGPL-family components require their own review: libusb remains separate from the Apache wrapper, and AndroidUSBCamera's native recipe includes LAME. AndroidUSBCamera also retains four-ABI UAC prebuilts whose matching implementation/provenance is not established. No wrapper-wide Apache clearance is inferred.
- Published/source manifests include broad audio/storage capabilities. Permission minimization and actual reachable API paths must be reviewed before any app merge.
- All 20 AAR ELF libraries have 60 total PT_LOAD segments with 16,384-byte alignment and offset/address congruence. DT_NEEDED names were parsed. This extends the prior static evidence, which had not checked congruence or parsed those dependencies. It does not establish full dynamic dependency closure, final APK alignment, native runtime behavior or 16-KiB device support.
- Direct dependency declarations and selected POM edges were rechecked; complete Gradle variant resolution, deeper transitives, artifact verification/locks and applicable notices remain incomplete. No dependency is newly introduced or distribution-cleared by this review.

Re-run local artifact inspection without network using the already present audit directory:

```powershell
python .\android\uvc-sim\review-evidence\license-audit.py `
  --artifact-dir C:\Users\USER\Documents\Codex\2026-11-10\task-4\uvc-slice\audit `
  --output .\android\uvc-sim\build\license-local-recheck.json
```

Add `--online` to fetch the pinned official source/POM/tree URLs again. This does not download a new AAR or run an upstream build. The saved JSON preserves the online inspection used for this review. The artifact directory is deliberately outside all compilation inputs; a fresh machine needs the exact artifacts identified by the original assessment, verified against their hashes.

## Integration handoff

Hold the simulated slice for the single ordering correction, preserving its isolated boundary. Review-only artifacts can be carried with that correction. After green regression results, the integration owner still owns shared interfaces and the final merge.

Native adoption remains a separate blocked step: establish source-to-build identity, missing license/provenance materials, the actual transitive graph, minimal permissions and final binary checks first. Real stop latency, hotplug, permission/lifecycle handling, HDMI topology, thermal/power behavior and long-duration operation still require separately authorized hardware evidence. No phone/card combination is declared supported.
