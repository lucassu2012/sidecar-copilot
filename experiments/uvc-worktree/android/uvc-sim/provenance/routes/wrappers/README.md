# UVC wrapper maintenance and source comparison

Within this bounded default-branch sample, **UVCAndroid has the most recent
observable changes to the Android/native capture path**. Its observed HEAD is
still the exact `fcdae5f` source already studied, so this refresh does not close
its existing libyuv provenance, native dependency, or device-validation gaps.
AndroidUSBCamera has useful wrapper/UAC interfaces but the previously identified
UAC binary-to-source and authorization gap remains. The older saki UVCCamera is a
traceable source ancestor/reference; choosing it is not evidence of modern
Android or capture-card compatibility.

Collection began **2026-11-10 15:15:44 UTC**. This is a read-only primary GitHub
study on local baseline `bbd053664bebb06ce83539a3f95e6fa5758745b7`. It adds research
artifacts only. No binary/archive downloads, upstream code execution, native
build, dependency installation, device operation, publication, or commit occurred.
Native binary filenames, sizes and Git blob IDs below came only from tree JSON.

## What "maintenance" means here

We distinguish: (1) archived/disabled flags; (2) fixed default-branch commit and
its recorded author/committer dates; (3) observable release/tag refs; (4) a bounded
sample of actual changes touching relevant native or USB paths; and (5) unresolved
source/notice correspondence. These are evidence dimensions, not a score.

The sample includes the first ten commits from each fixed HEAD, the first five
commits returned for each selected native/USB path (plus AndroidUSBCamera UAC),
and five selected commit patches. It does not survey every branch, fork, PR,
issue response, CVE, dependency update or maintainer channel. Commit dates and
messages are repository metadata; a fix title does not prove a tested fix.
`pushed_at` can concern other refs, and `updated_at`/stars are not maintenance or
quality measures. Neither an unarchived repository, a commit, a release, a CI
badge nor an Apache root license establishes device, security or full dependency
clearance. No CI outcome is used as a guarantee in this comparison.

## Fixed observation table

All three repositories returned `archived=false` and `disabled=false`.

| Repository | Default branch and observed HEAD | HEAD committer date | Latest observed GitHub release / corresponding tag ref |
| --- | --- | --- | --- |
| [shiyinghan/UVCAndroid](https://api.github.com/repos/shiyinghan/UVCAndroid) | `main`, [`fcdae5f9a194e2111d57019176b53470a120415b`](https://github.com/shiyinghan/UVCAndroid/commit/fcdae5f9a194e2111d57019176b53470a120415b) | 2026-06-24 07:49:22 UTC | [1.0.13](https://github.com/shiyinghan/UVCAndroid/releases/tag/1.0.13), published 2026-06-10 02:09:40 UTC; tag points to `6018c7f20238832929cf61427427bb7828056260`. |
| [saki4510t/UVCCamera](https://api.github.com/repos/saki4510t/UVCCamera) | `master`, [`c9399e63dfab4b6d260d8cbef92182abc6de0ee0`](https://github.com/saki4510t/UVCCamera/commit/c9399e63dfab4b6d260d8cbef92182abc6de0ee0) | 2018-10-02 07:51:05 UTC | Release-list and matching tag-ref queries both returned empty arrays. No tag/release was observed through those endpoints. |
| [jiangdongguo/AndroidUSBCamera](https://api.github.com/repos/jiangdongguo/AndroidUSBCamera) | `master`, [`426ed0a5da2c826197761cc5a89ba3ae9c52bd7c`](https://github.com/jiangdongguo/AndroidUSBCamera/commit/426ed0a5da2c826197761cc5a89ba3ae9c52bd7c) | 2024-09-02 07:43:31 UTC | [3.3.3](https://github.com/jiangdongguo/AndroidUSBCamera/releases/tag/3.3.3), published 2023-02-13 07:35:22 UTC; tag points to `cd2a301ff5bd76f70930fccfe4b8e1073edc51f7`. |

The explicit `/releases/latest` endpoint confirmed the two nonempty release
selections. Their tag names are also the highest numeric versions in the observed
matching-ref snapshots (13 refs for UVCAndroid and 77 for AndroidUSBCamera); Git
tag refs alone do not provide creation chronology. The connector rejected ordinary
`/tags?per_page=100`, so `git/matching-refs/tags/` was used instead. For saki,
`pushed_at=2021-12-14T13:26:26Z` differs from the 2018 default HEAD; no inference
of a 2021 default-branch code update is made.

UVCAndroid's current HEAD exactly matches the prior video/libyuv study, and
AndroidUSBCamera's current HEAD exactly matches the prior UAC study. The saki
HEAD is the same fixed `c9399e6` reference used in the earlier libyuv follow-up
source search. This is commit identity, not evidence that any distributed AAR
matches these sources. In particular UVCAndroid 1.0.13 predates HEAD's Hutool
removal, and AndroidUSBCamera 3.3.3 predates the 2024 changes.

## Relevant maintenance samples

| Repository and bounded path history | What the inspected change actually contains | Limit |
| --- | --- | --- |
| UVCAndroid JNI path: latest observed path commit [`5b3aa680`](https://github.com/shiyinghan/UVCAndroid/commit/5b3aa6800fc03ef308a63ab48d8c029119ce0c5e), 2026-06-10 | Changes `LIBUVC_PACKETS_PER_TRANSFER_MAX` from 32 to 4. An earlier inspected [`58f7d103`](https://github.com/shiyinghan/UVCAndroid/commit/58f7d103fef245152c3cb23c23e4523089efbc28), 2026-05-13, changed it from 8 to 32 and expanded the Android transfer-pressure explanation. | Concrete tuning activity; no hardware reproduction or proof that a particular capture card now works. |
| UVCAndroid USBMonitor path: latest returned `4c2956ad`, 2024-06-21 | Path history records Android 14/target SDK work; current source uses Android USB permission and connection APIs. | Those path-history titles are observations; this study did not independently verify every patch in the five-item history sample. |
| AndroidUSBCamera JNI path: [`1c08dc57`](https://github.com/jiangdongguo/AndroidUSBCamera/commit/1c08dc57ae5685cd7375966dd07dcd44f1053805), 2024-01-17 | Adds `libusb_clear_halt` in libuvc transfer handling for cancelled/error transfers. | Actual patch inspected; not a demonstrated concurrency, reconnect, or format-change guarantee. |
| AndroidUSBCamera USB path: [`b76acea2`](https://github.com/jiangdongguo/AndroidUSBCamera/commit/b76acea26b8d5f9880a66990474be2cae38eb34c), 2024-06-10, later merged in September | Replaces a locally computed PendingIntent flag with `PendingIntent.FLAG_IMMUTABLE` in USBMonitor. Other returned history entries concern Android 14 receiver/flag changes. | This does not prove permissions work on all modern Android builds; no Android execution occurred. |
| saki JNI path: latest returned `cb433a31`, 2017-03-24; USBMonitor path: `e59b047e`, 2017-01-17 | Recent path-history messages concern MIPS build and copyright/README work. Separately inspected substantive fd lifecycle patch [`25962423`](https://github.com/saki4510t/UVCCamera/commit/25962423ba7ec21f85d07886acf87a3cae7d9a5d), 2016-11-18, removes an extra device reference, nulls a failed device pointer and changes error-return handling. | This demonstrates historical Android-specific fork work, not contemporary maintenance across all refs. |

Patch snapshots retain relevant native/USB diff text. Unrelated IDE-file patches
are omitted from the normalized observations while their filenames/stats remain.
AndroidUSBCamera's selected UAC Java path last returns `ccdb068d`, 2023-02-10;
the existence of that activity does not identify the source of its native binaries.

## Capability coverage established by fixed source

This table describes visible code paths, not tested device capabilities. Native
source/API presence does not prove supported transport modes, bandwidth,
resolution, timing, recovery, FD ownership or successful device negotiation.

| Layer | UVCAndroid `fcdae5f` | saki UVCCamera `c9399e6` | AndroidUSBCamera `426ed0a` |
| --- | --- | --- | --- |
| Discovery and permission | `USBMonitor.java:396-433,492-544` enumerates `UsbManager.getDeviceList`, checks permission, requests permission. | `USBMonitor.java:297-315,417-449` provides the same Android USB discovery/permission role; detach handling is at 493-503. | `USBMonitor.java:321-355,413-465` covers discovery/permission; the Kotlin layer also checks camera permission before opening. |
| Java connection and fd handoff | `USBMonitor.java:1100-1115,1231-1233` opens a device connection/gets fd; `UVCCamera.java:138` passes fd and quirks. | `USBMonitor.java:973,1134-1136` opens/gets fd; `UVCCamera.java:189-210` clones control block and passes fd/VID/PID/bus/address. | `USBMonitor.java:1025-1048,1204-1206`; `UVCCamera.java:194-198` passes fd and device coordinates. |
| Native UVC bridge | `UVCCamera.cpp:88-126` duplicates fd then calls `uvc_wrap`; release calls stop and `uvc_close`. | `UVCCamera.cpp:137-219` duplicates fd, calls fork-specific `uvc_get_device_with_fd`/`uvc_open`, then stops/closes. `device.c:223-302` and `core.c:1249-1263` connect to Android libusb fd extensions. | `UVCCamera.cpp:154-160` similarly uses duplicated fd and `uvc_get_device_with_fd`/`uvc_open`; it is not merely an Android platform-camera wrapper. |
| Video stream and decode | `UVCPreview.cpp:562-623`: libuvc streaming; MJPEG through `uvc_mjpeg2rgbx_tj`, YUYV through `uvc_yuyv2rgbx`. Java loads JPEG 2.1.2-named, libusb, libuvc and wrapper libraries. | `UVCPreview.cpp:517-557`: streaming; MJPEG to YUYV, then `uvc_any2rgbx` for preview. Java loads `jpeg-turbo1500`, `usb100`, `uvc`, `UVCCamera`. | `UVCPreview.cpp:534-578`: streaming-bandwidth entry; MJPEG to YUYV and RGBX preview; analogous native library loads. `CameraUVC.kt` adds size/format selection and Kotlin callbacks. |
| Callback ownership | `UVCPreview.cpp:876-882` wraps native memory in `DirectByteBuffer`, invokes callback, recycles frame. | Equivalent lifecycle at `UVCPreview.cpp:867-873`. | Equivalent lifecycle at `UVCPreview.cpp:909-917`. |
| Audio/UAC evidence | Inspected `MediaAudioEncoder.java:108-132` uses system `AudioRecord` sources DEFAULT/MIC/CAMCORDER. This is not explicit selection/identity proof for the USB device. | Inspected `MediaAudioEncoder.java:105-130` does the same. No dedicated UAC engine was established by these inspected paths. | Explicit `AudioStrategyUAC` -> `UACAudioHandler` -> `UACAudio` -> native methods, using USB fd/device coordinates. Four ABI `libUACAudio.so` entries exist in tree metadata. Native source/authorization correspondence remains unresolved from prior evidence. |

The table's exact source URLs, Git blob IDs, SHA-256 values and marker line numbers
are in [`inputs.lock.json`](inputs.lock.json) and [`static-report.json`](static-report.json).
Representative fixed sources: [UVCAndroid USBMonitor](https://github.com/shiyinghan/UVCAndroid/blob/fcdae5f9a194e2111d57019176b53470a120415b/libuvccamera/src/main/java/com/serenegiant/usb/USBMonitor.java#L396),
[UVCAndroid native connect](https://github.com/shiyinghan/UVCAndroid/blob/fcdae5f9a194e2111d57019176b53470a120415b/libuvccamera/src/main/jni/UVCCamera/UVCCamera.cpp#L88),
[saki native preview](https://github.com/saki4510t/UVCCamera/blob/c9399e63dfab4b6d260d8cbef92182abc6de0ee0/libuvccamera/src/main/jni/UVCCamera/UVCPreview.cpp#L517),
[AndroidUSBCamera native preview](https://github.com/jiangdongguo/AndroidUSBCamera/blob/426ed0a5da2c826197761cc5a89ba3ae9c52bd7c/libuvc/src/main/jni/UVCCamera/UVCPreview.cpp#L534),
and [UACAudio JNI wrapper](https://github.com/jiangdongguo/AndroidUSBCamera/blob/426ed0a5da2c826197761cc5a89ba3ae9c52bd7c/libuvc/src/main/java/com/jiangdg/uac/UACAudio.java#L26).

For all three paths, asynchronous use of callback pixels requires a controlled copy
within the borrowed lifetime or a separately proved ownership mechanism. The
presence of close/join calls is not a stop-barrier proof. A direct native preview
surface also does not establish Sidecar's mask-before-publication ordering.
No wrapper's automatic discovery/open/reconnect or audio behavior is adopted here;
those behaviors must respect the existing project permission and lifecycle gates.

## Authorization and native-source limits

| Source | Positive evidence | What remains open |
| --- | --- | --- |
| UVCAndroid | Fixed root LICENSE is Apache-2.0; exact same HEAD and files as previous evidence. | Root Apache does not replace native notices. Reuse the prior libusb/libuvc/JPEG/transitive findings and the [libyuv snapshot report](../../libyuv/README.md) / [modification-source study](../../followup/libyuv/README.md): vendored libyuv has missing root LICENSE/PATENTS/AUTHORS and eleven substantive changed files; similarity mappings did not establish complete modification authorization. No re-clearance follows from recent commits. |
| saki UVCCamera | GitHub license detection is null and no root LICENSE entry was observed, but README and inspected wrapper Java/C++ headers state Apache-2.0. Inspected `libusb/core.c` states LGPL-2.1-or-later; `libuvc/device.c` contains BSD three-clause terms and modification attribution; JPEG's [LICENSE.md](https://github.com/saki4510t/UVCCamera/blob/c9399e63dfab4b6d260d8cbef92182abc6de0ee0/libuvccamera/src/main/jni/libjpeg-turbo-1.5.0/LICENSE.md) identifies IJG/BSD/zlib components. | Null GitHub detection is not proof of no license. Conversely selected headers are not a complete dependency bill of materials. Android fd extensions are fork changes; no full original-upstream snapshot match, build recipe validation or source-to-final-binary correspondence was established. `libjpeg-turbo-1.5.0` is an observed path/version label, not proof of an unmodified upstream release. |
| AndroidUSBCamera | Root Apache LICENSE blob matches UVCAndroid's root LICENSE exactly; fixed Java/Kotlin and native wrapper sources are available. | Root text cannot establish licensing/source correspondence for every prebuilt component. Reuse the [original UAC report](../../uac/REPORT.md) and [follow-up](../../followup/uac/REPORT.md), whose fixed HEAD remains current: four UAC blobs lack closed corresponding source/build/authorization evidence. Public source similarities and matching symbols did not close that chain. This is an evidence gap, not an infringement or closed-source allegation. |

Five inherited evidence JSON files are bound by byte length and SHA-256 in the new
lock; they are not edited or re-created. Current static comparison inherits their
scope and limitations. No binaries were downloaded again and no new ELF inspection
was conducted. The current study also does not re-audit every Java dependency,
rapidjson inclusion, JPEG component or transitive native license.

## Reproduce and evidence integrity

Deliverables are `inspect_wrappers.py`, `inputs.lock.json`, `observations.json`,
`static-report.json`, `test_replay.py` and this report. The normal replay verifies
**32 metadata observations**, **26 fixed text-source references / 25 unique Git
blobs** (710,580 bytes including the duplicated Apache license), and **five
inherited evidence files**. Source checks require exact size, Git blob SHA-1 and
SHA-256. Root/tree/release/history metadata are small tracked projected snapshots;
they do not depend on recovering a past response from a mutable endpoint.

From repository root:

```powershell
# Fresh cache: static UTF-8 source files only, no binary/archive/build.
python -B android/uvc-sim/provenance/routes/wrappers/inspect_wrappers.py --fetch
# Offline replay and integrity checks.
python -B android/uvc-sim/provenance/routes/wrappers/inspect_wrappers.py
python -B -m unittest discover -s android/uvc-sim/provenance/routes/wrappers -p test_replay.py -v
# Optional current metadata comparison; any material projected drift fails.
python -B android/uvc-sim/provenance/routes/wrappers/inspect_wrappers.py --check-live
```

The primary GitHub connector performed initial metadata/source reads. The
rerunnable script uses the same official URLs for static HTTP retrieval when
requested. JSON is compared through the same explicit semantic projection on
bootstrap and live replay, avoiding connector-vs-HTTP whitespace serialization
problems. Popularity/permissions/generic updated_at fields are deliberately
excluded. The original connector text SHA/size is retained per metadata input;
the original connector response-body text is kept in ignored cache. Projected snapshots are locked
as bytes, and normal replay never overwrites them. A future metadata change must
be reviewed as a new observation rather than silently replacing historical facts.

Eight tests passed, including corrupt cache rejection without refetch, offline
missing-input failure, exact CRLF-preserving source reconstruction, refusal to
cache an invalid download, JSON formatting independence, meaningful archived
state change detection, and stored-report replay. An actual empty-cache download
to `build/routes/wrappers/fresh-files` reproduced the same static report. No
upstream source was executed. The actual `--check-live` run also compared all 32
metadata projections successfully against direct official HTTP responses; the
stored report stayed byte-identical. Future material metadata drift will fail
that optional comparison rather than rewrite this observation. Report SHA-256:
`49e75f520b0f5af7c0880d29c4a59556e116a4f6fcba8aa5c2c346d546976490`.
