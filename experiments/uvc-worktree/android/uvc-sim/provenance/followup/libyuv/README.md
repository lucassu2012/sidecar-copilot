# libyuv local-extension provenance follow-up

The added conversion code has a reproducible textual relationship to the corresponding ARGB implementations in official libyuv `3e38ce50589d9319badc0501f96d6c5b2b177472`. Fourteen added functions and their declarations, row wrappers and macros are mapped below. This narrows the source investigation but does **not** establish who authored every change, a contributor permission chain, or equivalent pixel behavior. No original-byte or CRLF-only equality with the differently named upstream units is claimed.

The inspected UVCAndroid source remains `fcdae5f9a194e2111d57019176b53470a120415b`. The earlier audit's 11 modified files and 708 added source/header lines are unchanged. This follow-up only adds static research under this directory; its cached inputs are under `android/uvc-sim/build/native-research/libyuv/` and are never executed, linked or published.

## Evidence levels and complete line coverage

[report.json](report.json) records original fragment hashes, CRLF-normalized hashes, each explicit replacement, renamed hashes, token hashes/differences, and vendored/upstream line ranges for every unit. [units.json](units.json) declares the 37 comparisons: 14 functions, one structure, seven assembly macros, two wrapper invocations, ten declarations and three feature defines. The report binds the unit specification and input lock by SHA-256.

| Evidence level | Units | What was actually checked |
| --- | ---: | --- |
| Original bytes equal | 0 | Exact extracted bytes, including line endings |
| CRLF-only equal | 0 | Only CRLF is converted to LF |
| Rename-only equal | 17 | Explicit ABGR/ARGB and related symbol replacements, then exact LF text |
| Rename plus token equality | 9 | Those replacements plus removal of comments and whitespace outside strings |
| Related, tokens still differ | 11 | All remaining token differences are retained in the report |

The replacements include `ABGR→ARGB`, `abgr→argb`, `YUVTOBGR→YUVTORGB` and `BGRTOBGR8→RGBTORGB8`; STOREABGR has its separate declared STORERGBA/destination-name mapping. Replacements affect referenced routines/macros as well as the top-level name. String contents, assembly register operands, numeric indices and matrix constants are retained. Therefore even a token-equal result is a **text comparison under declared substitutions**, not proof that calling the two units produces the same output.

All 708 added source/header diff lines are classified: **620** fall in named unit ranges, **51** are blank/comment lines, **8** are LIBYUV_API markers, **4** are wrapper conditionals, and **25** are repeated context that the line diff aligns as an insertion. For those last 25 lines the script verifies that the entire containing pre-existing declaration/function/macro remains LF-identical to upstream; they are not silently treated as a new named unit. There are **zero unclassified added lines**. Android.mk is separate: **6 additions and 30 deletions**. These are diff-line counts, not a count of independently authored executable lines. The verifier fails if an added line escapes these classifications.

## Added-function source matrix

All official counterparts below are in the same named file at the [fixed official libyuv commit](https://chromium.googlesource.com/libyuv/libyuv/+/3e38ce50589d9319badc0501f96d6c5b2b177472). The corresponding vendored definitions are under [the pinned UVCAndroid libyuv directory](https://github.com/shiyinghan/UVCAndroid/tree/fcdae5f9a194e2111d57019176b53470a120415b/libuvccamera/src/main/jni/libyuv). Full SHA-256 and line-range evidence is machine-readable in the report.

| Added function / file | Official counterpart | Strongest demonstrated relationship |
| --- | --- | --- |
| YUY2ToABGR / source/convert_argb.cc | YUY2ToARGB | Tokens differ at `kYvuI601Constants` versus `kYuvI601Constants` after renaming |
| ABGRToYUY2 / source/convert_from_argb.cc | ARGBToYUY2 | Rename plus token equality |
| ABGRToRGB565 / source/convert_from_argb.cc | ARGBToRGB565 | Rename plus token equality |
| JpegI420ToABGR / source/convert_jpeg.cc | JpegI420ToARGB | Rename-only equality |
| JpegI422ToABGR / source/convert_jpeg.cc | JpegI422ToARGB | Rename-only equality |
| JpegI444ToABGR / source/convert_jpeg.cc | JpegI444ToARGB | Rename-only equality |
| JpegI400ToABGR / source/convert_jpeg.cc | JpegI400ToARGB | Rename plus token equality; gray callback still calls I400ToARGB |
| MJPGToABGR / source/convert_jpeg.cc | MJPGToARGB | Rename plus token equality |
| ABGRToRGB565Row_C / source/row_common.cc | ARGBToRGB565Row_C | Pair-path indices 0/2 and 4/6 are exchanged; the odd-width tail is unchanged by this comparison |
| YUY2ToABGRRow_C / source/row_common.cc | YUY2ToARGBRow_C | Destination channel indices are exchanged in paired and tail paths |
| YUY2ToABGRRow_NEON / source/row_neon.cc | YUY2ToARGBRow_NEON | Rename plus token equality; referenced renamed conversion macros have separate differences |
| ABGRToRGB565Row_NEON / source/row_neon.cc | ARGBToRGB565Row_NEON | Rename plus token equality; ABGRTORGB565 macro differs separately |
| YUY2ToABGRRow_NEON / source/row_neon64.cc | YUY2ToARGBRow_NEON | Rename plus token equality; referenced renamed macros differ separately |
| ABGRToRGB565Row_NEON / source/row_neon64.cc | ARGBToRGB565Row_NEON | Final store selects `v16.16b` instead of `v18.16b` |

Thus 10 of the 14 functions meet rename-only or rename-plus-token equality; four retain other token differences. These findings identify plausible source templates. They do not establish that the original contributor actually copied from the inspected commit, and they do not clear the modifications' authorization or correctness.

| Other added units | Official counterpart / relationship |
| --- | --- |
| ABGRBuffers in convert_jpeg.cc | ARGBBuffers, rename plus token equality |
| YUVTOBGR, row_neon.cc and row_neon64.cc | YUVTORGB; register assignments/instruction order differ |
| BGRTOBGR8, row_neon.cc | RGBTORGB8; rename plus token equality |
| BGRTOBGR8, row_neon64.cc | RGBTORGB8; narrowing instruction order differs |
| ABGRTORGB565, both NEON files | ARGBTORGB565; channel registers differ |
| STOREABGR, row_neon.cc | STORERGBA is a related local candidate; move registers and instruction count differ |
| Two row_any.cc wrapper invocations | Corresponding ARGB wrapper calls, rename-only equality; surrounding conditionals are accounted separately |
| Ten added declarations across three headers | Nine are rename-only equal; YUY2ToABGR retains uppercase `dst_ABGR`/`dst_stride_ABGR` identifiers, so its declared substitution is not token-equal |
| Three new row.h feature defines | Corresponding ARGB feature defines, rename-only equality |

All ten modified code/header files retain exactly the official initial copyright/license block after CRLF normalization. The additions contain no new copyright/author/source-URL lines in the inspected patch. This is an attribution observation; absence of a new notice does not by itself determine infringement or permission.

## Import, build recipe and public copy history

[UVCAndroid import `05435d727fcdd74fc44fd2a3826ee57213dbb07e`](https://github.com/shiyinghan/UVCAndroid/commit/05435d727fcdd74fc44fd2a3826ee57213dbb07e), with Git author and committer date 2024-09-18 02:32:13 UTC, is the earliest identified addition in the inspected UVCAndroid path history. The recorded author/committer is hanshiying; its parent is `09cf0a27e5bbf8db3b20dfc76c7e0a0ed9dae2af`. Its message describes MJPG-to-RGBX optimization and does not identify an external patch or upstream SHA. Git author metadata identifies a claimed contribution event, not copyright ownership or an independent authorization statement.

The Android.mk changes against official libyuv are also bounded: the imported recipe substitutes `jpeg-turbo212` for `libjpeg` and removes the native unit-test target. Later commits [`569e118d`](https://github.com/shiyinghan/UVCAndroid/commit/569e118d7514193da204b953ebbe47c9c87cdbe9) and [`c06f4edf`](https://github.com/shiyinghan/UVCAndroid/commit/c06f4edf10b6e67bffc5364642857af75944f294) add max-page-size and common-page-size flags respectively. The prior fixed-tree audit proves the other 97 blobs unchanged since import. The locked metadata in this follow-up records these three contribution events; no additional external origin for the build edits was established.

GitHub code searches for MJPGToABGR and ABGRToRGB565Row_C found the pinned source and six other projects. Their inspected path histories provide later propagation or inheritance evidence:

| Project | Inspected introduction commit | Git committer date |
| --- | --- | --- |
| TechxArtisanStudio/Openterface_Android | `210ab6143e0a1bf50cf638b897656151deba67a4` | 2024-09-20 |
| BEAR678/UVC | `8f700cd500d7be3a55df3caf260bbf0a52f61f80` | 2025-09-04 |
| catchpig/AndroidUVCCamera | `c8db6fa0e33a95b0abc183bd4b643a788bc68b52` | 2024-12-26 |
| LeongOoiTam/BlindNaviApp | Same `05435d727fcdd74fc44fd2a3826ee57213dbb07e` | 2024-09-18 |
| M6M-ar/SeaCucumberMonitor | `aef416f50a4d63bdb210e34b7769de22e8f1e664` | 2026-05-11 |
| i30mb1/Bondcast | `91ec6e25e2fe2ae181ccb36ce5f19267a978947c` | 2026-07-05 |

BEAR retains the 2024 author date but has a 2025 committer date; its entire tree is identical to the UVCAndroid import tree. Openterface's fixed introduction versions of convert_jpeg.cc and row_common.cc match UVCAndroid after CRLF normalization, including the custom additions. Their original bytes differ. These observations do not identify an earlier independent origin or grant. Dates are Git metadata, not proof of public publication time. [search-observations.json](search-observations.json) records bounded queries, fixed heads and history URLs; mutable search results are explicitly separate from deterministic locked inputs.

## saki/libuvc and licensing limits

The seven queried extension names have no hits in the following locked files:

- saki4510t/UVCCamera `c9399e63dfab4b6d260d8cbef92182abc6de0ee0`, `libuvccamera/src/main/jni/libuvc/src/frame.c` and `frame-mjpeg.c`.
- UVCAndroid's pre-import parent `09cf0a27e5bbf8db3b20dfc76c7e0a0ed9dae2af`, the same two paths.
- libuvc/libuvc `448d78b9aa4f54172c1ca5b5d1b4a6b9b93f7957`, `src/frame.c`.

saki's `uvc_yuyv2rgbx` and `uvc_mjpeg2rgbx` perform related conversion work but are different C/libuvc implementations. They are not presented as matches for the new libyuv functions. The inspected source headers contain BSD terms and Ken Tossell/Robert Xiao/saki attribution as applicable. saki's fixed README explicitly distinguishes separately licensed native subtrees from its root Apache notice. Negative symbol results apply to these exact files and commits, not all libuvc history or all forks.

The official libyuv historical LICENSE/PATENTS/AUTHORS hashes are recorded again from the locked 3e38ce5 archive. UVCAndroid's import root LICENSE and Openterface's root LICENSE are Apache-2.0 texts; the ten modified libyuv files still carry the specific upstream BSD-style header. A repository-level license or a downstream copy is not independent proof of who supplied or authorized every local addition. The historical Google patent grant is not enlarged by these similarity results.

Remaining evidence needed: provenance/permission confirmation for the introduced changes or independently reviewed replacements, accurate modification/attribution material, final build/linkage selection and source-to-binary identity. The retained matrix/indices/register differences also require separate correctness work before any claimed equivalence. No hardware/runtime result or native-adoption clearance is declared here.

## Reproduce, reject changes, and compare the report

Use Python 3.9 or later; no third-party packages are required:

```powershell
python android/uvc-sim/provenance/followup/libyuv/verify_sources.py --check-report
python android/uvc-sim/provenance/followup/libyuv/check_replay.py
```

Default operation is offline and fails on missing or mismatched inputs. On a new machine, explicitly add `--fetch-missing`; every download is verified before caching. The 21 inputs in [inputs.json](inputs.json) use raw SHA-256 for source/license bytes and a versioned semantic projection for Git commit metadata. That projection retains SHA, Git author/committer, message, tree and ordered parents, while excluding mutable GitHub account/verification metadata. Bootstrap and online/offline verification use the same serialization; observed raw metadata hashes are retained separately. Original source Git blob hashes are checked where captured independently.

`--check-report` recomputes all evidence and requires equality with [report.json](report.json), excluding only its observation timestamp. It does not overwrite the report. `--write-report` is an explicit regeneration action. [replay-checks.json](replay-checks.json) records successful replay, archive-tamper and missing-cache rejection, harmless metadata-format acceptance, changed-commit rejection, verified HTTP reconstruction of missing metadata, and modified-report rejection. Those checks mutate only a separate ignored cache copy, leaving source evidence and the historical audit untouched.
