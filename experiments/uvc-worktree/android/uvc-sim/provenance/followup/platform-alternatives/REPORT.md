# Camera2 external-camera alternative: static feasibility only

**A platform-only camera adapter is a candidate for a phone whose shipped firmware exposes the selected capture card through Camera2. It is not a general replacement for an app-side USB stack, and no such phone/card combination has been verified here.** No adapter or dependency is added by this investigation.

## Fixed source evidence

The Android 15/API 35 reference was chosen to match the existing simulation's Android discovery compilation target, not as a claim about the user's phone. Resolving `android-15.0.0_r1` produced these commits; replay uses the immutable commit URLs, not the tag:

| Repository | Commit | Tree |
| --- | --- | --- |
| platform/frameworks/base | `4e43ad12e1b211f15152e9c5b16b0fe88e6b93f3` | `9583c6d9586b84169697623a44af09e8ac3d79b9` |
| platform/hardware/interfaces | `488942f82bd1bc9ad1cb65a02c71421dc3a6a3d6` | `484d45fd7355830b7b66d1cffdb679f8bf484a6a` |

[INPUT_LOCK.json](INPUT_LOCK.json) pins nine raw HTTP responses: two commit records and seven base64 source responses. [static-evidence.json](static-evidence.json) records decoded SHA-256, computed Git blob identifiers, source URLs and anchor line numbers. The computed blob hashes identify the bytes; this small audit does not independently verify source-tree membership. No mutable website snapshot or latest Android branch is used by the replay.

The [official platform integration guide](https://source.android.com/docs/core/camera/external-usb-cameras), consulted 2026-11-10, requires USB host and kernel UVC support plus an external camera provider, device configuration and SELinux/HAL declarations in the device build. It describes a lightweight reference design and configurable resolution/fps limits. An application cannot infer that an arbitrary retail phone supplies those pieces merely because the Camera2 API exists. Its illustrative configuration is not a measured capture format for any target device.

The fixed [ExternalCameraProvider.cpp](https://android.googlesource.com/platform/hardware/interfaces/+/488942f82bd1bc9ad1cb65a02c71421dc3a6a3d6/camera/provider/default/ExternalCameraProvider.cpp) scans V4L2 nodes, checks capture capability and initializes a candidate device before announcing it. Removal changes provider presence. The provider's own `getCameraIdList` returns an empty list and communicates external additions through its status callbacks; this HAL detail must not be mistaken for an empty application-side CameraManager list.

The fixed [ExternalCameraDevice.cpp](https://android.googlesource.com/platform/hardware/interfaces/+/488942f82bd1bc9ad1cb65a02c71421dc3a6a3d6/camera/device/default/ExternalCameraDevice.cpp), lines 39–43, lists only MJPEG and Z16 as V4L2 inputs in `kSupportedFourCCs`. Z16 supplies depth/Y16, not desktop color video. Lines 637–641 and 658–675 map MJPEG to application-side BLOB/YUV420/IMPLEMENTATION_DEFINED configurations, so a Camera2 client must inspect its negotiated output rather than assume it receives compressed MJPEG. The implementation filters formats and fails initialization when the usable set is empty. Vendor implementations can differ. A YUYV-only card is not established as supported by this reference route. The device marks itself `LENS_FACING_EXTERNAL`; neither that characteristic nor enumeration proves which computer output is being captured or whether text is readable.

## Existing lifecycle and privacy obligations still apply

| Source observation | Consequence for a future adapter; not an implementation in this change |
| --- | --- |
| [CameraManager.java](https://android.googlesource.com/platform/frameworks/base/+/4e43ad12e1b211f15152e9c5b16b0fe88e6b93f3/core/java/android/hardware/camera2/CameraManager.java), lines 295–306 and 432–444: connected IDs may include devices in use; availability callbacks persist independently of Activity lifecycle. | Enumerate characteristics and negotiated stream configuration, release registration explicitly, and distinguish unavailable/in-use from physical removal. Availability alone must not start or resume capture. |
| [CameraDevice.java](https://android.googlesource.com/platform/frameworks/base/+/4e43ad12e1b211f15152e9c5b16b0fe88e6b93f3/core/java/android/hardware/camera2/CameraDevice.java), lines 1561–1584: disconnect includes policy, permission, removal and higher-priority clients, and capture callbacks/new output buffers may still arrive afterward. | Immediately invalidate the current capture lease, conceal the affected preview and reject/release late frames; explicit source confirmation remains necessary before recovery. This maps to the existing lifecycle rather than adding PRD states. |
| The same file, lines 1371–1383: `close` invalidates the device/session, with `onClosed` indicating full device shutdown. | Coordinate framework shutdown with the application's own in-flight frame work, ImageReader buffers and worker drain. A callback from CameraDevice alone does not demonstrate that already handed-off application buffers have been released. |
| [ImageReader.java](https://android.googlesource.com/platform/frameworks/base/+/4e43ad12e1b211f15152e9c5b16b0fe88e6b93f3/media/java/android/media/ImageReader.java), lines 509–535: acquire-latest discards older images only with sufficient free acquisition capacity; unreleased images exhaust the queue. | Use a bounded ownership policy, release every Image on success, rejection and stop, and preserve the pipeline's monotonic publication rule after any asynchronous processing. The framework's latest-image acquisition cannot prevent later application tasks finishing out of order. |

The consequences in the right column are engineering inferences from the API contracts and frozen PRD, not device test results. ImageReader.java lines 773–783 additionally specify that closing the reader invalidates acquired Images and makes access through old plane ByteBuffers undefined. A future stop sequence must drain readers or copy their needed data into application-owned buffers before closing the ImageReader; device closure does not extend buffer lifetime. Image plane stride, pixel layout, timestamps, orientation and alpha handling must be adapted explicitly to the existing frame contract. Raw camera preview must not bypass masking, privacy pause or the final publication guard.

## What this changes about licensing

If an application uses only the installed Camera2 framework and packages no USB/libyuv/UAC implementation, it can avoid distributing the particular unidentified native artifacts examined in this work. This does not determine the license status of a phone's firmware. The inspected [HAL build file](https://android.googlesource.com/platform/hardware/interfaces/+/488942f82bd1bc9ad1cb65a02c71421dc3a6a3d6/camera/device/default/Android.bp) itself links libyuv and libjpeg and declares an Apache-2.0 license group. Copying or shipping this HAL would create a different source/dependency review and is not proposed here. The inspected source files carry Apache-2.0 headers; this evidence is not a complete audit of Android's source tree.

## Frozen PRD fit and selection rule

Using an already supported Camera2 external input could preserve PRD 4.1's selected HDMI output and no computer agent, but still requires the exact PRD 4.3 phone/system/card/Hub/power/cable/display/format support matrix. A missing external provider should not trigger custom phone firmware, a computer capture agent, display degradation or automatic microphone capture. No such scope change is authorized.

This route is worth a separately authorized, device-specific G1 feasibility experiment only if the intended phone exposes the selected card, the original display remains intact, and actual delivered format/readability can be verified. It does not supply the A/B/C/D desktop audio routes or G2 acceptance; see the separate [audio investigation](../uac/REPORT.md). If a valid platform route is absent, the product remains at the existing synthetic G0 boundary until another reviewed route passes its own gates. Camera2 availability and documentation do not close the native, distribution or device gates.

## Replay

From the worktree, with Python 3.9+ for the collector (integrity helper uses Python 3.9+ `Path.is_relative_to`):

```powershell
python android/uvc-sim/provenance/followup/platform-alternatives/audit_platform.py --offline
python android/uvc-sim/provenance/followup/platform-alternatives/check_integrity.py
```

For a fresh cache, omit `--offline`; only missing inputs are downloaded and must match the tracked lock before being saved. Existing changed inputs fail rather than being silently refreshed. All cached inputs reside in ignored `android/uvc-sim/build/native-research/platform-alternatives`; downloaded source is never executed. Reports are compared on ordinary replay; `--write-report` is an explicit regeneration option. Bootstrap `--record-inputs` refuses an existing lock.

[integrity-checks.json](integrity-checks.json) records a successful nine-input offline replay and expected failures for a missing source, changed bytes, changed recorded result, missing report and attempted lock replacement. The original lock and evidence remain unchanged. These are audit-tool integrity checks, not new PRD acceptance test numbers or a simulation/runtime regression run.
