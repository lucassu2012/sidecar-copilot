# libyuv source provenance at the pinned UVCAndroid tree

The evidence identifies a modified, CRLF-converted subset consistent with upstream libyuv commit `3e38ce50589d9319badc0501f96d6c5b2b177472`. It does **not** establish an unmodified complete snapshot, a unique historical download event, or equivalence to the Maven binary. This review executes no upstream code and changes no build or application dependency.

## Fixed identities and import history

Both UVCAndroid source commit `fcdae5f9a194e2111d57019176b53470a120415b` and publication tag commit `6018c7f20238832929cf61427427bb7828056260` contain exactly the same libyuv Git tree: `b0ae177444ab6f818c4848180362308c3e8cfa9c`. See the [source directory](https://github.com/shiyinghan/UVCAndroid/tree/fcdae5f9a194e2111d57019176b53470a120415b/libuvccamera/src/main/jni/libyuv) and [tag directory metadata](https://api.github.com/repos/shiyinghan/UVCAndroid/contents/libuvccamera/src/main/jni?ref=6018c7f20238832929cf61427427bb7828056260). Their source content therefore needs one comparison. Identical source trees do not prove that a published AAR was built from either commit.

[Import commit `05435d7`](https://github.com/shiyinghan/UVCAndroid/commit/05435d727fcdd74fc44fd2a3826ee57213dbb07e), committed 2024-09-18 02:32:13 UTC, added 98 files: Android.mk, 6 docs, 24 headers, 55 source files and 12 utility files. Its libyuv tree is `129b2465e047bc1be9d8a3597cbf65ecdec8fab5`. Its message describes MJPG-to-RGBX optimization and does not identify an upstream commit. The [directory history](https://api.github.com/repos/shiyinghan/UVCAndroid/commits?sha=fcdae5f9a194e2111d57019176b53470a120415b&path=libuvccamera/src/main/jni/libyuv&per_page=100) then contains only `569e118d7514193da204b953ebbe47c9c87cdbe9` and `c06f4edf10b6e67bffc5364642857af75944f294`, adding the 16 KiB max/common-page-size linker flags to Android.mk. The other **97 Git blobs are unchanged since import**. All three root documents LICENSE, PATENTS and AUTHORS are absent from both imported and target trees.

## Official version interval and comparison

GitHub's libyuv mirror helped locate the version history; the evidence is independently tied to commit-addressed metadata, version headers, tree entries and source archives from the official Chromium host. Its verified consecutive parent chain is:

| Official commit | Committed UTC | Version |
| --- | --- | --- |
| `1c5a8bb17ac4092da557e55cf519bf4df105d8f1` | 2022-08-12 01:28:13 | 1839 |
| [`65e7c9d5706a77d1949da59bfcb0817c252ef8d6`](https://chromium.googlesource.com/libyuv/libyuv/+/65e7c9d5706a77d1949da59bfcb0817c252ef8d6) | 2022-08-16 22:07:38 | 1840 |
| [`3e38ce50589d9319badc0501f96d6c5b2b177472`](https://chromium.googlesource.com/libyuv/libyuv/+/3e38ce50589d9319badc0501f96d6c5b2b177472) | 2022-08-17 18:39:05 | 1840 |
| `f71c83552d373f0ff41833b17e2880632d8561d7` | 2022-09-16 19:46:47 | 1841 |

Thus version 1840 alone names at least two commits on this verified history. Both were compared, without assuming that the version macro uniquely identifies the vendored source.

| Comparison of all 98 vendored files | Earlier 1840 commit | Later 1840 commit |
| --- | ---: | ---: |
| Original bytes identical | 0 | 0 |
| Identical after CRLF-to-LF only | 83 | 87 |
| Content differs after that normalization | 15 | 11 |
| Vendored paths absent upstream | 0 | 0 |
| Upstream files absent from vendor | 84 | 84 |

The later candidate's `source/convert.cc`, `source/planar_functions.cc`, `source/row_gcc.cc` and `source/scale.cc` match after CRLF normalization while the earlier candidate does not. All 98 original Git blobs and SHA-256 hashes, normalized blob hashes, both candidate comparisons, and the full 84-file omission lists are in [report.json](report.json). Both upstream archives contain 182 regular files. The omissions include license material, build/configuration files and unit tests; an omitted file is not automatically a runtime dependency.

The 11 content differences against the later candidate are:

| File | LF-normalized added / removed lines | Observed difference |
| --- | ---: | --- |
| Android.mk | 6 / 30 | Select jpeg-turbo212, remove native test target, add later 16 KiB flags |
| include/libyuv/convert_argb.h | 21 / 0 | YUY2ToABGR and MJPGToABGR declarations |
| include/libyuv/convert_from_argb.h | 18 / 0 | ABGRToRGB565 and ABGRToYUY2 declarations |
| include/libyuv/row.h | 22 / 0 | Associated row prototypes and feature macros |
| source/convert_argb.cc | 76 / 0 | YUY2ToABGR implementation |
| source/convert_from_argb.cc | 238 / 0 | ABGR conversion implementations |
| source/convert_jpeg.cc | 133 / 0 | MJPGToABGR callbacks and conversion |
| source/row_any.cc | 6 / 0 | Row wrappers |
| source/row_common.cc | 44 / 0 | C row implementations |
| source/row_neon.cc | 77 / 0 | 32-bit NEON additions |
| source/row_neon64.cc | 73 / 0 | 64-bit NEON additions |

The ten source/header files preserve the official candidate's existing lines and add 708 lines under this comparison. This proves the described content relationship, not who authored or supplied the additions. Full normalized patches stay in the ignored cache and their hashes are recorded in the report.

## Historical license material

The official LICENSE is the project's BSD three-clause text; PATENTS is its additional Google patent grant, including its stated scope and termination condition; AUTHORS is the historical attribution list. All three are byte-identical across the two 1840 candidates. Each recovered file is checked against the official archive, the official root-tree Git blob and the separately fetched official file. No current-main license text was substituted.

| Historical file at later candidate | Bytes | SHA-256 |
| --- | ---: | --- |
| [LICENSE](https://chromium.googlesource.com/libyuv/libyuv/+/3e38ce50589d9319badc0501f96d6c5b2b177472/LICENSE) | 1506 | `2b2cc1180c7e6988328ad2033b04b80117419db9c4c584918bbb3cfec7e9364f` |
| [PATENTS](https://chromium.googlesource.com/libyuv/libyuv/+/3e38ce50589d9319badc0501f96d6c5b2b177472/PATENTS) | 1387 | `4da84211f947c6f9f7dd8fbce513e1522e01833407ae732b8c9a2bbb75fed495` |
| [AUTHORS](https://chromium.googlesource.com/libyuv/libyuv/+/3e38ce50589d9319badc0501f96d6c5b2b177472/AUTHORS) | 148 | `406e9dee7651ff7a415ef126fe6f649b29312ea3f2b28f11bb7f0bf458edb3bd` |

The patent text explicitly excludes claims arising only from further modification. Recovering that text cannot determine its application to the added conversion implementations. Maintainer evidence is still needed for the provenance and applicable notices of the ten modified code/header files, the selected build inputs, and source-to-binary mapping. Restoring verified historical notices into a future reviewed distribution is separate from this evidence-only task; this directory does not clear native adoption or distribution.

## Reproduce and verify integrity

From the worktree, with Python 3.9 or later and no third-party packages:

```powershell
python android/uvc-sim/provenance/libyuv/verify_libyuv.py --offline
python android/uvc-sim/provenance/libyuv/check_integrity.py
python android/uvc-sim/provenance/libyuv/check_metadata_rebuild.py
```

The first command checks all **21 locked artifacts** from `android/uvc-sim/build/provenance/libyuv/` and reruns the content comparison. Nineteen inputs retain their original byte SHA-256 checks; the two GitHub metadata inputs use the explicit semantic projections below. Missing inputs, changed source bytes or changed selected metadata fail nonzero. Without `--offline`, missing files may be downloaded from the recorded URLs but must match [artifact-lock.json](artifact-lock.json) before being written. `--refresh` also checks the appropriate hashes before replacing existing cache bytes. The original GitHub-tree manifest independently checks every vendored archive file's Git blob and size.

The default verifier does not overwrite report.json. `--write-report` explicitly regenerates it; `--record-artifacts` is a bootstrap-only option that refuses an existing lock. The initial bootstrap retrieved source archives via static HTTP because the GitHub connector cannot return binary archives. No archive is extracted or executed.

GitHub import-history/tree JSON was first obtained through the connector and stored as its JSON representation. A direct HTTP response has different formatting but equivalent provenance content. To allow automatic reconstruction on a new machine, online, bootstrap and offline reads now use the same explicitly versioned projection:

- `github-tree-semantic-v1` retains the root sha/truncated fields and every entry's path, mode, type, sha and blob size, sorting entries by path. Duplicate JSON keys and duplicate tree paths are rejected.
- `github-commit-history-semantic-v1` retains commit order, each SHA, message, Git tree SHA, Git author/committer name/email/date, and ordered parent SHAs. Commit links are derived from the fixed repository and SHA. GitHub account display fields, avatars, node IDs and verification-status metadata are excluded because they are not immutable Git commit content.
- Both projections serialize with UTF-8, sorted object keys, compact comma/colon separators, unescaped Unicode and one terminal LF. The lock records the projection version, canonical SHA-256/size, and the separately observed connector and HTTP raw response SHA-256/size. Raw cache responses remain intact. This scheme is task-specific, not a claim of RFC 8785 canonicalization or original-byte equality.

`--metadata-only` checks or reconstructs those two inputs without downloading or processing source archives. Combining it with explicit `--write-report` updates only their envelopes and the lock digest, preserves the original source-comparison timestamp/content, and records a SHA-256 link to the previous report. The 21-input inventory and the source comparison conclusions remain unchanged.

[integrity-checks.json](integrity-checks.json) preserves the original successful offline run, corrupted-archive rejection, missing-license rejection and report hash. The updated report links to that original report hash. [metadata-rebuild-checks.json](metadata-rebuild-checks.json) records successful downloads into an initially empty metadata cache, subsequent offline verification, acceptance of formatting/account-display-only changes, and rejection of changed commit messages and tree blob SHAs. These checks use separate ignored caches and leave the original cache untouched. The generated source diffs and downloaded original texts remain ignored and are not production inputs.

Closed: source/tag subtree identity, import history, the consecutive 1840 interval, exhaustive 98-file comparisons, omissions, historical license-text identities, offline reproducibility and tamper detection. Open: origin/terms of the added code, exact importer acquisition history, final build selection and binary provenance, notice packaging, and device/runtime behavior.
