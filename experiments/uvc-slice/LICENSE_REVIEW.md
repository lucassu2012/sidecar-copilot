# UVC candidate dependency assessment

Inspected 2026-11-10. Decision: **no third-party runtime integration yet**. Fixed commits and public package metadata are recorded below; the full resolved dependency graph, provenance and distribution obligations are not cleared. The prototype itself is newly written Java and imports no upstream source. Audit downloads are separate from the application.

## Fixed candidates

| Candidate | Inspected source / artifact | Decision |
| --- | --- | --- |
| AndroidUSBCamera | `jiangdongguo/AndroidUSBCamera` commit `426ed0a5da2c826197761cc5a89ba3ae9c52bd7c`; root build declares `versionNameString = 3.3.3` | Do not equate that declaration with a verified 3.3.3 publication; native audio provenance and broader dependency set need work |
| UVCAndroid | `shiyinghan/UVCAndroid` commit `fcdae5f9a194e2111d57019176b53470a120415b`; Maven `com.herohan:UVCAndroid:1.0.13` independently inspected | Narrower video candidate, but source-to-binary mapping is unproven and published dependency metadata differs from inspected build source |

The Maven AAR was fetched from [Maven Central](https://repo.maven.apache.org/maven2/com/herohan/UVCAndroid/1.0.13/UVCAndroid-1.0.13.aar) solely as a ZIP for inspection. SHA-256:

| File | SHA-256 |
| --- | --- |
| UVCAndroid-1.0.13.aar | `4f1295a6c6b5e8590fb7dde3c60f30a94166db45c2d07efa63c85d5da8bfff17` |
| UVCAndroid-1.0.13.pom | `5b1ff27d872d1fa3720fa1e367bab05d6cf422695e7457fb44b5934be005add7` |
| UVCAndroid-1.0.13.module | `f22e2960d4eca439c19eeBDC82d4ea51450735335fb889cded6511e1b07de20b` |

The actual AAR is 2,761,961 bytes; its SHA-256 agrees with its published Gradle module metadata. That consistency does not prove the inspected Git commit produced it. The AAR contains `classes.jar` plus `libUVCCamera.so`, `libjpeg-turbo212.so`, `libusb1.0.so`, `libuvc.so`, `libyuv.so` for each of arm64-v8a, armeabi-v7a, x86 and x86_64. The ZIP name scan found no standalone LICENSE/NOTICE entries; embedded files inside classes.jar were not exhaustively audited. No native code was loaded.

## Native license findings

| Component | Evidence at fixed source | Consequence / remaining work |
| --- | --- | --- |
| AndroidUSBCamera / UVCAndroid wrapper | Both top-level licenses Apache-2.0; their native build comments explicitly exclude separately licensed subtrees | Preserve license, applicable notices and modification notices; do not label the aggregate binary solely Apache |
| libusb | AndroidUSBCamera `libusb/libusb/core.c` says LGPL-2.1-or-later; COPYING supplies LGPL 2.1. Existing UVCAndroid review also identifies libusb LGPL | Confirm every selected binary's exact source and linkage; prepare corresponding source/modifications and applicable replacement/relinking materials before distribution. Dynamic linkage alone is not a blanket exemption |
| libuvc | AndroidUSBCamera native build has BSD three-clause terms; UVCAndroid LICENSE.txt includes BSD three-clause terms | Retain copyright/conditions/disclaimer; inspect incorporated source and binary mapping |
| libjpeg-turbo | AndroidUSBCamera vendors 1.5.0; its LICENSE.md lists IJG, BSD three-clause and zlib. UVCAndroid source LICENSE.md describes the same license families; its binary is named turbo212 | Carry relevant IJG attribution and all applicable notices; file name is not sufficient proof of exact library build/version |
| RapidJSON | Vendored `license.txt` is MIT | Preserve notice; inspect which bundled third-party/demo/test directories actually enter the binary rather than assigning their licenses to runtime by assumption |
| LAME in AndroidUSBCamera | `libnative/src/main/cpp/CMakeLists.txt` collects `module/mp3/lame` into nativelib; `lame.h` says GNU Library GPL v2-or-later | Additional LGPL-family review even if Sidecar only wants video; do not assume audio helpers disappear without verified build/link analysis |
| AndroidUSBCamera UAC binary | `libUACAudio.so` prebuilt for four ABIs; Java UACAudio loads it. No matching native implementation/provenance established by the inspected tree/build files | Adoption blocker until exact source, third-party content and redistribution rights are verified; wrapper license alone does not settle the binary |
| UVCAndroid libyuv | AAR contains libyuv.so; fixed source has libyuv include/source/build files, but LICENSE and PATENTS at that subtree root returned 404 and were absent from the matching tree license inventory | Do not infer a cleared license from the library name. Recover the precise vendored revision, complete license/patent/notice files and its binary mapping before adoption |

Primary source links: [AndroidUSBCamera fixed tree](https://github.com/jiangdongguo/AndroidUSBCamera/tree/426ed0a5da2c826197761cc5a89ba3ae9c52bd7c), [native link recipe](https://github.com/jiangdongguo/AndroidUSBCamera/blob/426ed0a5da2c826197761cc5a89ba3ae9c52bd7c/libuvc/src/main/jni/UVCCamera/Android.mk), [libuvc build and license](https://github.com/jiangdongguo/AndroidUSBCamera/blob/426ed0a5da2c826197761cc5a89ba3ae9c52bd7c/libuvc/src/main/jni/libuvc/android/jni/Android.mk), [LAME header](https://github.com/jiangdongguo/AndroidUSBCamera/blob/426ed0a5da2c826197761cc5a89ba3ae9c52bd7c/libnative/src/main/cpp/module/mp3/lame/lame.h), [UVCAndroid fixed native tree](https://github.com/shiyinghan/UVCAndroid/tree/fcdae5f9a194e2111d57019176b53470a120415b/libuvccamera/src/main/jni).

## Java/Kotlin and transitive dependencies

AndroidUSBCamera's fixed libausbc build declares appcompat 1.3.1, constraintlayout 2.0.4, material 1.3.0, xlog 1.11.0 and project modules libuvc/libnative. libnative additionally declares core-ktx 1.3.2, appcompat 1.2.0 and material 1.3.0; the root Kotlin Gradle plugin is 1.5.20. These overlapping versions need real resolution, not a manual list assumed to be a lockfile. The demo app is not part of the proposed integration; its unrelated SDKs should not enter the app transitively by copying the demo project.

[XLog 1.11.0 POM](https://repo.maven.apache.org/maven2/com/elvishew/xlog/1.11.0/xlog-1.11.0.pom) declares Apache-2.0. [Appcompat 1.3.1 POM](https://dl.google.com/dl/android/maven2/androidx/appcompat/appcompat/1.3.1/appcompat-1.3.1.pom) declares Apache-2.0 and expands to annotation 1.1.0, core 1.5.0, cursoradapter 1.0.0, activity 1.2.4, fragment 1.3.6, appcompat-resources 1.3.1, drawerlayout 1.0.0, savedstate 1.1.0, collection 1.0.0, lifecycle-runtime/viewmodel 2.3.1. These are declared transitive edges, not the final selected versions after conflict resolution; their own deeper dependency and notice inventory remains pending.

UVCAndroid's [fixed libuvccamera build](https://github.com/shiyinghan/UVCAndroid/blob/fcdae5f9a194e2111d57019176b53470a120415b/libuvccamera/build.gradle) declares core 1.13.1, legacy-support-v4 1.0.0, appcompat 1.6.1 and annotation 1.8.0. Both the **published** [1.0.13 POM](https://repo.maven.apache.org/maven2/com/herohan/UVCAndroid/1.0.13/UVCAndroid-1.0.13.pom) and Gradle module metadata additionally declare `cn.hutool:hutool-core:5.8.35`. Its parent POM declares **MulanPSL-2.0**, not Apache-2.0: [parent license metadata](https://repo.maven.apache.org/maven2/cn/hutool/hutool-parent/5.8.35/hutool-parent-5.8.35.pom). Resolve this source/publication difference before choosing a build origin. The parent’s JUnit/Lombok test dependencies are not automatically runtime dependencies.

This review has not run a Gradle dependency resolution/build, recursively verified every selected POM/Gradle variant, audited all bundled classes, validated ELF DT_NEEDED/16-KiB page alignment, compared reproducible native builds or performed a vulnerability scan. Therefore neither candidate is distribution-cleared. Before adoption, generate the actual resolved graph and artifact checksums, preserve complete source/notice material including native dependencies, examine optional feature flags and packaged output, and qualify the exact build on the selected device. No license statement in this document is a hardware-support claim.
