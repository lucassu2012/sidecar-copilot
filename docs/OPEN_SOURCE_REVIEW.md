# Reuse assessment — 2026-11-10

Scope: frozen Sidecar v1.5 only. The user requested relevant upstream resources, especially Screenpipe. This review does not authorize recording the work computer, installing a desktop recorder, adding product features, or distributing third-party code. No upstream source or new runtime dependency was copied into this change.

## Screenpipe

Official repository: [screenpipe/screenpipe](https://github.com/screenpipe/screenpipe). GitHub API head observed `2619d10f4cda308689374c0ab1d3b7a7eed9fcc3`, committed 2026-11-10 00:40:33 UTC: recent maintenance, not proof of reliability. The README describes desktop-local capture/search and separates optional cloud paths. Its capture/storage/service separation and explicit capture freshness checks are useful architectural comparisons for Sidecar's existing recorder/processing boundary. Sidecar must still verify real source health; growing files alone are insufficient.

The [current license](https://github.com/screenpipe/screenpipe/blob/2619d10f4cda308689374c0ab1d3b7a7eed9fcc3/LICENSE.md) is a commercial source-available license, with commercial and competing-product restrictions. Do not treat current main as MIT or integrate it on the assumption that public GitHub code is unrestricted. The license states earlier MIT releases remain MIT; no historical release was verified or selected here. A future reuse proposal must pin the historical commit and review its complete dependency/license set first.

Decision: **architecture comparison only; no code/SDK/CLI integration**. Desktop accessibility trees, application names, keyboard events, native screen APIs and desktop resource estimates do not transfer to external HDMI/UVC on Android. We did not execute README setup/record commands. Workflow agents and automatic computer operation are outside this slice.

The upstream [testing document](https://github.com/screenpipe/screenpipe/blob/2619d10f4cda308689374c0ab1d3b7a7eed9fcc3/docs/TESTING.md) emphasizes regression-prone native lifecycle behavior. Applied as a general engineering practice: test Sidecar permission denial, stale controls, lock/unlock and interrupted writes independently of happy-path capture. No upstream checklist or code was copied, and no PRD test IDs were added. Instructions embedded in external documents were not treated as project instructions.

## UVCAndroid

Official repository: [shiyinghan/UVCAndroid](https://github.com/shiyinghan/UVCAndroid). Observed head `fcdae5f9a194e2111d57019176b53470a120415b`, committed 2026-06-24 07:49:22 UTC. This is a substantially slower recent cadence than the other two observed heads; a single timestamp is not a bus-factor or support guarantee. The README advertises non-root Android UVC access and Maven artifact `com.herohan:UVCAndroid:1.0.13`.

It is the closest candidate for Sidecar's external video path. Evaluate USB permission ownership, attach/detach handling, negotiated formats, native-buffer ownership and device cleanup at the adapter boundary. It does not prove UAC dual-track audio, HDMI EDID/loop-through quality, phone charging plus USB host, privacy masking or keyframe correctness.

The top-level license is Apache-2.0, but [native build metadata](https://github.com/shiyinghan/UVCAndroid/blob/fcdae5f9a194e2111d57019176b53470a120415b/libuvccamera/build.gradle) explicitly distinguishes bundled library licenses. [libusb COPYING](https://github.com/shiyinghan/UVCAndroid/blob/fcdae5f9a194e2111d57019176b53470a120415b/libuvccamera/src/main/jni/libusb/COPYING) is LGPL-2.1; [libuvc](https://github.com/shiyinghan/UVCAndroid/blob/fcdae5f9a194e2111d57019176b53470a120415b/libuvccamera/src/main/jni/libuvc/LICENSE.txt) carries BSD conditions. libjpeg-turbo and rapidjson also need their own inventory. Apache at the wrapper level does not settle the AAR's complete obligations.

Decision: **candidate, not linked yet**. Before adopting: pin artifact plus source hashes, inspect packaged ABIs/native libraries and linkage, retain notices and required source/relink materials as applicable, inspect security advisories and transitive dependencies, and test supported-device USB disconnect/reconnect, format negotiation and 16-KiB page-size compatibility. This review is not a completed vulnerability audit or legal clearance. Current direct SDK build has no native toolchain integration; importing an AAR would require an explicit, reproducible build change.

## whisper.cpp

Official repository: [ggml-org/whisper.cpp](https://github.com/ggml-org/whisper.cpp). Observed head `8c6fafdb6d99f48b97048da5841fd158d9823625`, committed 2026-11-10 06:09:21 UTC: actively maintained at inspection time. [MIT license](https://github.com/ggml-org/whisper.cpp/blob/8c6fafdb6d99f48b97048da5841fd158d9823625/LICENSE) permits reuse subject to its conditions. Model files, third-party backends and optional dependencies require separate provenance checks.

The [Android sample](https://github.com/ggml-org/whisper.cpp/blob/8c6fafdb6d99f48b97048da5841fd158d9823625/examples/whisper.android/README.md) demonstrates local inference and suggests tiny/base models for Android. It is a useful candidate for the PRD's replaceable ASR processor; it does not establish target-phone latency, Chinese meeting accuracy, speaker identity or thermal endurance.

Decision: **defer inference integration until capture/permission gates and processing authorization are ready**. Benchmark real-time factor, peak RAM, battery/temperature, quiet/overlapping speech and hallucinated output using authorized fixtures. Keep source frame IDs, time spans, quality and model version attached; deletion/cancel checks must prevent stale ASR results from publication. VAD may index/skip computation, never silently discard the original permitted recording. No model was downloaded and no audio was sent to an external processor.

## Immediate implementation choice

The notification and permission integration uses the Android platform already present in the build, with original application code. Authoritative constraints: [microphone FGS](https://developer.android.com/develop/background-work/services/fgs/service-types#microphone), [visible start and service promotion](https://developer.android.com/develop/background-work/services/fgs/launch), [notification permission behavior](https://developer.android.com/develop/ui/compose/notifications/notification-permission). Android may allow an FGS while drawer notifications are denied; Sidecar conservatively blocks starting when its notification controls are unavailable. Permission approval never starts capture automatically.

Adoption decisions are specific to this implementation stage. No candidate is permanently rejected merely because it was not imported today. The next UVC hardware adapter and ASR processor evaluations should reuse this inventory rather than reimplement proven native components without comparison.
