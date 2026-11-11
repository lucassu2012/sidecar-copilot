# UVC source and license gate handoff

**Native adoption remains blocked for the inspected distributions. The synthetic UVC slice and its ordering fix are unaffected.** This investigation closes the unexplained Hutool source-version difference and recovers historical libyuv license material. It also identifies concrete remaining work: mixed Hutool notices, modified libyuv provenance, unidentified UAC native source, and the actual selected Android dependency graph.

Project baseline: `fd9825ee57c1eec9e773ac8853195664adbb5038`; isolated branch `codex/uvc-license-provenance`, worktree `C:\Users\USER\Documents\Codex\2026-11-10\task-4\sidecar-uvc-licenses`. This work adds static audit scripts and records only. Application/runtime code, dependency declarations, manifests, historical evidence and the frozen PRD are unchanged. Downloaded archives and native files are audit inputs under ignored `android/uvc-sim/build/provenance`, outside all application build inputs. No downloaded code, native library, upstream build or real device was executed; nothing was published.

## Evidence and next actions

| Item | What is now established | Action before adoption |
| --- | --- | --- |
| UVCAndroid 1.0.13 publication / Hutool origin | The release tag is `6018c7f20238832929cf61427427bb7828056260`. Its 74 ordinary Java sources exactly match the published sources JAR; the extra source is generated `BuildConfig`. The next commit `61e9f8fde9ee0071b661d230a33b58542ddf84bc` removes Hutool and replaces the MIME helper. The earlier audit's `fcdae5f` is two commits after the release tag. | Treat the release tag and later Hutool-free source as two explicit candidates. If using the later source, name/hash the locally built result separately. Published Java source correspondence does not prove that the complete AAR/native bytes were built reproducibly from that tag. |
| Hutool 5.8.35 | All 693 ordinary Java sources in the published sources JAR match tag commit `ced8bf817ab4448e9b1162af6ce697cef8640827`. The binary contains 906 class files; no embedded native library or nested archive was found in the inventory. Its parent/root license is MulanPSL-2.0, but several source files retain Apache-2.0 or separate NeuQuant terms. | If retaining the full artifact, prepare a notice inventory that preserves these additional headers/terms. The JAR/source JAR lack standalone license/notice-named entries. A POM label is insufficient. If selecting the later source without Hutool, prove the final code and resolved graph omit it; do not merely exclude the dependency from the old AAR. |
| libyuv vendored source | The 1.0.13 tag and `fcdae5f` share tree `b0ae177444ab6f818c4848180362308c3e8cfa9c`. Two consecutive official commits carry version 1840. Against the closer `3e38ce50589d9319badc0501f96d6c5b2b177472`, 87 of 98 vendored files differ only in CRLF; 11 differ substantively. The vendor omits 84 upstream files, including LICENSE/PATENTS/AUTHORS. Exact historical copies and hashes of those materials are now recorded. | Use the pinned vendor tree and explicit difference manifest as source identity. Review the 11 modifications and applicable attribution, restore the historical materials in the future source package, and bind locally built outputs to that input set. Do not label this an unmodified or uniquely proven upstream 1840 import. |
| AndroidUSBCamera UAC | Four fixed ABI blobs, hashes, JNI methods, import names and introduction/update commits are recorded. They reference `libusb100.so` and contain the source-path clue `jni/usbAudio/native_usb_audio.cpp`. The corresponding implementation was not established in the inspected repository/archive. | Keep these blobs excluded until corresponding source, incorporated dependencies and applicable terms are supplied. For a video-only build, prove `libUACAudio.so` and reachable UAC loader paths are absent in every final ABI/package. This is an exclusion option, not clearance of the binaries. Do not remove `libusb100.so` blindly: the video libraries also depend on it. |
| Java/Kotlin transitives | A recursive published-POM declaration inventory, parent/BOM origins, version conflicts, module variants and static archive notices are recorded with exact URLs/hashes. Retrieval failures are separated from confirmed absence. | Choose the intended Android build variant and resolve it in a separately reviewed minimal build. Produce the actual selected coordinates/variants, constraints, exclusions, checksums and lock/verification data, then inspect notices for those final artifacts. A union of declarations is not an Android runtime lockfile. |

Component evidence: [Hutool source/publication trace](hutool/trace.json), [libyuv comparison](libyuv/report.json), [UAC static evidence](uac/static-evidence.json), [transitive inventory](transitives/inventory.json). Each subdirectory includes its own replay script and report.

The static transitive scope contains **119 coordinate/version pairs, 124 POM models including parents/BOMs, 24 modules with multiple declared versions, and 119 primary archives**. There are 51 present Gradle module files and 68 confirmed absences at both repositories, with no unknown retrieval states in the final run. The tracked input lock contains 299 content inputs plus 136 repository-specific 404 observations. Extra JVM candidates named by module metadata, including `annotation-jvm:1.6.0` and two `coroutines-core` versions, are explicitly listed but not recursively selected. These counts describe the conservative inventory, not the number of libraries that would ship.

## Reproduce the recorded evidence

Use the component instructions, which include online cache reconstruction and strict offline verification:

- [Hutool replay and integrity checks](hutool/README.md): 25 locked inputs; the existing AAR/POM/module audit directory is supplied explicitly.
- [libyuv comparison and integrity checks](libyuv/README.md): 21 locked materials, the vendored Git-blob manifest, both official 1840 candidates and historical notices.
- [UAC replay and integrity checks](uac/REPORT.md): 23 recorded inputs and four ABI blobs. The parser uses the environment's existing `pyelftools==0.33`; no package was installed for this task.
- [Transitive inventory replay](transitives/README.md): 435 raw-input pins and explicit metadata coverage; archive inspection runs sequentially to bound working memory. An earlier parallel attempt exhausted memory; the final locked offline run completed successfully.

Missing or changed inputs are errors, not silently refreshed evidence. The lockfiles protect the reviewed bytes/observations; they do not constitute a Gradle dependency lock or publisher signature verification. The scripts read ZIP/ELF/class/source data and do not execute the candidate code. A successful audit replay means the stated evidence checks passed; it does not open the native adoption gate.

[Root replay results](replay-results.json) record successful Hutool integrity checks, libyuv offline comparison, UAC offline parsing and 13 transitive-tool checks. The full sequential transitive archive replay is recorded in [VALIDATION.md](transitives/VALIDATION.md). Two libyuv GitHub metadata inputs use a documented semantic projection so HTTP formatting/account-display changes do not prevent reconstruction; commit/file identity changes still fail. Audit files preserve recorded bytes across Git checkouts to keep their recorded hashes stable.

## Corrections to the earlier assessment

The original `LICENSE_REVIEW.md` and `review-evidence` remain historical records. Their blanket native-adoption hold remains appropriate, but these specific unknowns have narrowed:

- The Hutool difference is explained by comparing a release with later source that deliberately removed the dependency. It is no longer an unexplained publication mutation. The Java source comparison is stronger evidence than the previous substring/POM observation; full native build reproduction is still unproven.
- libyuv's required root materials remain missing from the vendor tree, but they are now recoverable from the inspected historical 1840 snapshots. The new evidence also establishes substantive source changes; copying today's license and calling the vendor an untouched release would misstate the result.
- UAC is no longer merely a filename inventory: ABI hashes, imports, JNI identity, native path clue, history and repository-source search coverage are documented. None of these alone establishes the missing implementation or a license grant for every incorporated component.
- Transitive work now extends beyond selected POM examples. The exact scope and unresolved variant boundaries are recorded in the inventory; they remain distinct from actual Gradle resolution and a complete source-notice audit.

## Distribution materials to prepare

Use the selected source/artifact notices, not one wrapper-wide license label. For the future package:

- MulanPSL-2.0 requires a license copy and retention of copyright, patent, trademark and disclaimer statements; preserve applicable additional source notices as well. [License text](https://spdx.org/licenses/MulanPSL-2.0.html).
- Apache-2.0 requires the license, applicable attribution/NOTICE material and modification notices where relevant. [Apache text, section 4](https://www.apache.org/licenses/LICENSE-2.0).
- BSD-family source and binary distribution must retain or reproduce the applicable copyright, terms and disclaimer; carry the exact libyuv PATENTS/AUTHORS materials with their source identity. [BSD-3-Clause text](https://spdx.org/licenses/BSD-3-Clause.html).
- libusb and any retained LAME need their own LGPL-family plan: identify the exact source/modifications and final linkage, provide the applicable source/materials and notices, and verify the chosen replacement/relinking route for the shipped package. Dynamic linkage alone does not establish the complete route. [LGPL 2.1 text, sections 4 and 6](https://spdx.org/licenses/LGPL-2.1-only.html). The observed libusb source offers 2.1-or-later; the linked page supplies the 2.1 text, not a change to that grant.

This is the engineering input to distribution review, not a completed legal determination or permission to ship unidentified code. Final build identity, source/notice packaging and actual selected artifacts still need verification.

## Operational gate for main integration

1. Keep the current simulated adapter enabled for development; this audit introduces no runtime dependency.
2. Recommended next investigation: continue with the already pinned UVCAndroid source `fcdae5f9a194e2111d57019176b53470a120415b`, which removed Hutool, as a separately named local video-only build. This avoids adopting the alternative AndroidUSBCamera UAC stack. Include the pinned libyuv vendor-tree/difference record and recovered historical materials. Resolve this source's graph afresh; the published-tag inventory here is evidence, not a transferable lock for that build.
3. Exclude the unidentified UAC implementation from the chosen package, or obtain and verify its corresponding source and terms before including it. For an AndroidUSBCamera video-only investigation, disable its default automatic audio/system-microphone fallback as well; the `NONE` setting alone does not remove packaged UAC binaries. Validate the remaining libusb dependency separately.
4. Resolve the selected Android variant, prepare final notices/source materials, and record toolchain, input patches and output hashes. Keep the inspected Maven binary and any locally rebuilt artifact distinct.
5. Only after those adoption conditions are reviewed should the main integration link a native adapter. Distribution and real hardware capture retain their separate existing gates; static provenance and ELF inspection are not device acceptance.

No PRD feature, state or acceptance identifier is added by these actions.
