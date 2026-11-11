# Isolated UVC simulation integration

Integrated on 2026-11-10 into the primary Sidecar worktree. This handoff freezes the reviewed simulation snapshot; it does not change the frozen v1.5 PRD, product states, acceptance identifiers or real-device gates.

## Source and file boundary

- Original simulation: `b793449047021f102ec2ce59cebd9025cb57977b`.
- Independent review: `7dfce300ec3d4d94ba6adf2260dd2789f3001403`.
- Runtime ordering correction: `bc6599c2e4621240ffee6ac8f1cdcde415072d38`.
- Reviewed handoff imported: `fd9825ee57c1eec9e773ac8853195664adbb5038`, branch `codex/uvc-frame-order-fix`.
- Source worktree: `C:\Users\USER\Documents\Codex\2026-11-10\task-4\sidecar-uvc-fix`.
- Integration destination: `C:\Users\USER\Documents\Codex\2026-11-10\task\sidecar-copilot\android\uvc-sim`.

The source diff from `496afd3936c7a601a5fcb34af4a4820aa76d51f3` contained only 36 added regular files under `android/uvc-sim/`. The destination did not exist. All 36 files were imported directly from their Git blobs and byte-verified before and after validation. No existing file was overwritten. This integration note is the only additional module file.

Historical failing `review-evidence` and corrected `fix-evidence` are preserved unchanged. No main app source, shared contract, database, manifest, build script or acceptance table was changed by this integration. No Git commit was created. No AAR, JAR, native library or external runtime dependency was imported.

## Validation in the primary worktree

Fresh compiled classes and all new logs are isolated under `android/build/uvc-integration/2ac04b58cbe0482aa1624bc06e8d6fb3/`. [checks.json](../build/uvc-integration/2ac04b58cbe0482aa1624bc06e8d6fb3/checks.json) records the results; [source-manifest.json](../build/uvc-integration/2ac04b58cbe0482aa1624bc06e8d6fb3/source-manifest.json) records each imported Git blob and SHA-256. These generated files are local build artifacts, not historical source evidence.

| Check | Result |
| --- | --- |
| Coordinator baseline | 204 assertions passed |
| Frame pipeline baseline | 77 assertions across 10 scenarios passed |
| Integrated synthetic flow | 43 assertions across 3 scenarios passed |
| Independent safety review | 86 assertions across 5 scenarios passed |
| Frame-order regressions | 145 assertions across 5 scenarios passed |
| Original coordinator and pipeline concurrency reproductions | Both passed; only frame 2 published, preview remained `222222ff` |
| Android discovery | Compiled against API 35; not executed |

All validation commands exited 0: 555 counted assertions, plus the two concurrency reproductions and Android compilation. These counts are implementation assertions, not independent PRD cases. JDK 17.0.19+10 compiled Java 8-compatible classes. The baseline pipeline reported 3,686,400 owned pixel bytes at peak and a 23,600 ns cancellation observation; these are limited JVM fixture measurements, not application RSS, native latency or a hardware SLA. The primary Android application suite is outside this module's independent validation.

Do not invoke `run-review-checks.ps1` with its default evidence destination during a future review: it writes to the tracked historical `review-evidence` directory. Keep new verification output in a separate generated directory, preserving both historical failure and correction records.

## Retained acceptance limits

The module remains outside the application's source/build inputs. It uses generated pixel arrays and an in-memory masked preview and audit. Simulated publication is not durable storage. The fixture's consent/gate booleans do not implement production permissions, identity or single-active-session arbitration; shared persisted controls, deletion/retention checks, complete gap projection, real USB permission receipts and attachment epochs still require integration.

No emulator, Android app, microphone, speaker, USB endpoint or physical device was operated for this integration. Real video, encrypted image storage, native loading, UVC/UAC coexistence, power/thermal behavior, measured timing and sustained hardware operation remain unverified. G1/G2/G3 exits and real capture remain closed.

The [license assessment](LICENSE_REVIEW.md) and [independent native audit](review-evidence/license-review.md) remain unresolved for native adoption, including published-source/Hutool divergence, libyuv provenance/materials and the complete dependency/notices graph. Nothing here authorizes importing native dependencies, distributing their binaries, publishing or deploying the app.
