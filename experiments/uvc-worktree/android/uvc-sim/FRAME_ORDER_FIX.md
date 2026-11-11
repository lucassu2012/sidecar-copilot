# Concurrent frame ordering fix

**The reproduced P2 ordering defect is fixed. Recommend merging this correction with the isolated synthetic slice.** Both original reproductions now publish only frame 2 and retain its preview. Native adoption, licensing and real-device gates remain unchanged and closed.

## Fixed baseline and minimal change

- Original implementation: `b793449047021f102ec2ce59cebd9025cb57977b`.
- Independent review baseline: `7dfce300ec3d4d94ba6adf2260dd2789f3001403`.
- Tested code and regression-test commit: **`bc6599c2e4621240ffee6ac8f1cdcde415072d38`**.
- Isolated branch: `codex/uvc-frame-order-fix`; worktree: `C:\Users\USER\Documents\Codex\2026-11-10\task-4\sidecar-uvc-fix`.

Runtime source changes are confined to [FramePipeline.java](src/local/sidecar/copilot/uvcsim/FramePipeline.java), at the final publication block: **12 added / 4 removed lines**. The remaining code-commit changes add [FrameOrderChecks.java](review-tests/FrameOrderChecks.java) and let the review runner save results separately and record whether tested code is clean. No coordinator API, shared contract, app manifest, main build script or application file changed. No dependency was added.

Before the correction, concurrent driver callbacks could pass coordinator admission in order 1, 2 but reach the pipeline in order 2, 1. The later delivery of frame 1 then replaced frame 2. The coordinator's accepted sequence remained 2, masking the preview regression.

The final publication block now checks the currently retained preview under the existing gate lock. For the same lease, a candidate must have a strictly greater sequence and a nondecreasing host-reception timestamp. Superseded candidates keep `transferred=false`; the existing finalizer records a `gap` with reason `superseded frame`, increments the drop count and clears/releases the buffer. Rejection does not advance the publication boundary or interrupt the source. A greater sequence with equal reception time remains valid.

The preview is a valid ordering reference because every path that clears it first invalidates that lease, or closes the entire pipeline. A new lease compares by identity and may restart sequence/time values even while an old preview awaits asynchronous cancellation. Old cancellation cannot clear a new lease's preview. The ordering check, lease check and pointer publication share the same short lock; hashing, pixel work, device operations and sink calls remain outside it. Queue capacity and pixel-memory limits are unchanged.

## Reproducible validation

From this worktree, with the existing JDK 17 and Android API 35 paths, run:

```powershell
.\android\uvc-sim\run-review-checks.ps1 -EvidenceDir .\android\uvc-sim\fix-evidence
```

This command exited **0** on the exact code commit above. [checks.json](fix-evidence/checks.json) records that commit and `sourceWorktreeClean: true`. `baselineSourceUnchanged: false` is expected: the original `b793449` source was corrected. The subsequent handoff commit contains documentation and these generated logs only.

| Check | Result |
| --- | --- |
| Original suite | 324 assertions passed: coordinator 204, pipeline 77, integration 43; API 35 discovery compile passed. |
| Original coordinator reproduction | Exit 0; commit sequences `[2]`, preview `222222ff`, accepted sequence 2. |
| Original pipeline reproduction | Exit 0; publication sequence `2`, preview `222222ff`. |
| Independent safety review | 5 scenarios / 86 assertions passed. |
| New ordering regressions | 5 scenarios / 145 assertions passed. |

The new regression scenarios cover lower and duplicate sequence values even with later reception time; higher sequence with regressed time; rejected candidates leaving the ordering reference unchanged; equal timestamps; new-lease restart while an old preview remains retained; delayed old cancellation/admission; and stop or close while an older frame is paused before publication with preview, queue and in-flight buffers present. Checks inspect redacted pixels, gap metadata, exact release outcomes, cleared arrays and zero remaining owned bytes. All test waits are bounded and paused workers are released in cleanup.

An independent reviewer also compiled the same new test against the uncorrected `7dfce30` pipeline: it failed at the lower-sequence replacement assertion. The original two failing reproduction logs remain preserved under [review-evidence](review-evidence/checks.json); the corresponding green outputs are under [fix-evidence](fix-evidence/checks.json). Existing review test sources were not weakened or rewritten for the fix.

The original suite's observed owned-pixel peak remains 3,686,400 bytes. Its stop-admission sample was 22,700 ns in this run. These are synthetic fixture observations, not process-memory bounds, a native stop guarantee or a hardware SLA. No Android app, native library, USB device or physical phone was executed. The main app suite was not rerun because the slice remains outside its build inputs and no main app file changed.

## Merge handoff and retained gates

`PREMERGE_REVIEW.md` and `review-evidence` describe the historical review of `b793449`; their hold recommendation and failed logs apply to that uncorrected baseline. This report supersedes that single ordering hold for the tested correction. No other review finding was silently cleared.

The branch history is `b793449` (synthetic slice), `7dfce30` (review evidence), then `bc6599c` (fix and regression checks), followed by the documentation/evidence handoff. The integration owner can take that sequence, skipping commits already present. Review the runtime-only difference with:

```powershell
git diff b793449 bc6599c -- android/uvc-sim/src
```

The recommendation is limited to the synthetic module and this fix. The [license assessment](LICENSE_REVIEW.md), [independent dependency audit](review-evidence/license-review.md) and their raw evidence are unchanged: published-source/Hutool divergence, missing libyuv materials, full transitive resolution/notices and UAC native provenance remain unresolved. No real capture or distribution clearance follows. Shared persisted controls, actual USB permissions/attachment epochs, physical stop behavior, hotplug, timestamps, thermal/power and long-duration device acceptance still require their existing separate integration and device work. The frozen PRD, product states and acceptance identifiers are unchanged.
