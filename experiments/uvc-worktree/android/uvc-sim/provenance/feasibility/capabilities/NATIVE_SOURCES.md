# Native subtree inventory

Every regular file in each retained candidate subtree has a SHA-256 in capabilities.json. Whole subtrees include unused tests/tools; build lists are static source evidence and have not been evaluated by ndk-build/CMake.

| Candidate subtree | Fixed revision | Regular files | Build files inspected |
| --- | --- | ---: | --- |
| UVCCamera | fcdae5f9a194e2111d57019176b53470a120415b | 23 | libuvccamera/src/main/jni/UVCCamera/Android.mk; libuvccamera/src/main/jni/UVCCamera/CMakeLists.txt |
| libusb | fcdae5f9a194e2111d57019176b53470a120415b | 204 | libuvccamera/src/main/jni/libusb/CMakeLists.txt; libuvccamera/src/main/jni/libusb/android/jni/Android.mk; libuvccamera/src/main/jni/libusb/android/jni/Application.mk; libuvccamera/src/main/jni/libusb/android/jni/libusb.mk |
| libuvc | fcdae5f9a194e2111d57019176b53470a120415b | 41 | libuvccamera/src/main/jni/libuvc/CMakeLists.txt; libuvccamera/src/main/jni/libuvc/android/jni/Android.mk |
| libjpeg-turbo | fcdae5f9a194e2111d57019176b53470a120415b | 519 | libuvccamera/src/main/jni/libjpeg-turbo/Android.mk; libuvccamera/src/main/jni/libjpeg-turbo/CMakeLists.txt; libuvccamera/src/main/jni/libjpeg-turbo/fuzz/CMakeLists.txt; libuvccamera/src/main/jni/libjpeg-turbo/java/CMakeLists.txt; libuvccamera/src/main/jni/libjpeg-turbo/md5/CMakeLists.txt; libuvccamera/src/main/jni/libjpeg-turbo/sharedlib/CMakeLists.txt; libuvccamera/src/main/jni/libjpeg-turbo/simd/Android.mk; libuvccamera/src/main/jni/libjpeg-turbo/simd/CMakeLists.txt |
| rapidjson | fcdae5f9a194e2111d57019176b53470a120415b | 151 | libuvccamera/src/main/jni/rapidjson/thirdparty/yajl/src/CMakeLists.txt |
| official_libyuv | 3e38ce50589d9319badc0501f96d6c5b2b177472 | 182 | Android.mk; CMakeLists.txt |

The target official libyuv subtree replaces the vendored modified libyuv; no mixing of their source/header objects is proposed. No source files were copied into the build or application.
