# AndroidUSBCamera UAC 固定二进制溯源核查

核查日期：2026-11-10。固定仓库 `jiangdongguo/AndroidUSBCamera`，提交 [`426ed0a5da2c826197761cc5a89ba3ae9c52bd7c`](https://github.com/jiangdongguo/AndroidUSBCamera/tree/426ed0a5da2c826197761cc5a89ba3ae9c52bd7c)。本工作区基线 `fd9825ee57c1eec9e773ac8853195664adbb5038`。

**结论：UAC 接入和二进制分发门禁继续关闭。** 已建立四个文件的字节身份、引入历史和动态依赖，但没有找到能够与四个 blob 绑定的 UAC 原生源码、完整构建材料或明确覆盖其全部组成的授权说明。此结论表示证据缺口，不是对侵权或“闭源”的认定。未编译上游、未加载 `.so`、未操作设备、未改生产配置，未据 README 或符号推断硬件兼容。

## 1. 复现与证据完整性

文件：

- `collect_uac.py`：读取官方固定 Git tree、raw blobs、路径提交历史、codeload ZIP 和列出的官方 API 快照；仅作字节/ELF/文本解析。
- `static-evidence.json`：四个 ELF 的完整动态符号/选定字符串/notes，20 个预编译库的动态依赖图，历史、源码片段和搜索规则。
- `retrieval-index.json`：**所有脚本输入**（包括源码 ZIP、API JSON、四个 `.so`）的 URL、缓存名、SHA-256、字节数。作为需随代码审阅的可信清单。
- `check_cache_integrity.py`：完整离线解析与缺项/篡改失败检查。

审计环境原有 Python **3.14.2**、[pyelftools **0.33**](https://pypi.org/project/pyelftools/0.33/)；后者上游为 [eliben/pyelftools](https://github.com/eliben/pyelftools)，是审计工具依赖，不是应用新增依赖。本轮没有安装包。解析脚本校验 pyelftools 版本，不能将本轮描述为“全程仅 stdlib”。其他脚本模块来自 Python 标准库。

在工作区根目录运行：

```powershell
# 已有完整缓存时，禁止联网，重算但不改证据：
python android/uvc-sim/provenance/uac/collect_uac.py --offline --verify-only
# 实际缺项/篡改检查：
python android/uvc-sim/provenance/uac/check_cache_integrity.py
# 获准联网且首次补缓存时；已有清单的输入必须匹配原 SHA：
python android/uvc-sim/provenance/uac/collect_uac.py
```

下载仅落到 ignored `android/uvc-sim/build/provenance/uac/`。离线缺缓存或缺清单项即失败；在线与离线都会先对已有缓存验清单 SHA/长度，损坏缓存不会静默重新接受。四个 `.so` 还逐个核对固定 tree 的 Git blob SHA-1。所有读取、解析成功后才写报告，所以缺项/损坏输入不会覆盖现有 JSON。校验脚本只在该 ignored 目录的临时子目录生成篡改副本，测试前后比较证据哈希，原缓存保持不变。

清单防止相对于已审阅证据的缓存意外改变，不是独立签名或版权证明。首次抓取依赖官方 HTTPS；API 评论和 PR 状态是此次快照，离线复跑不会刷新状态。重生成 JSON 的 `observed_at_utc` 会变化；`--verify-only` 不写文件。

## 2. 精确二进制身份

所有路径为 `libuvc/src/main/jniLibs/<ABI>/libUACAudio.so`；ELF SONAME 均为 `libUACAudio.so`。

| ABI | 字节 | SHA-256 |
|---|---:|---|
| arm64-v8a | 22432 | `e4c6af125ef73573e9334fb9ac6eff2a50856c33f34ab758c7bb830499dc054f` |
| armeabi-v7a | 26208 | `adcde2a84392d9ce95a6db2b6a169792cb6c2a077d7eef224ff28c07afd28672` |
| x86 | 17952 | `8c3ba934a42a833d95f772e60eff2ebcfeb4dd42dd7bcd85f1de4b3c9e0d673d` |
| x86_64 | 22696 | `e0b7e998d24fcfb41d401c2b139bef67ccaa6f0689dc26cbf90dc6082f06ca38` |

| ABI | Git blob SHA-1 | GNU build ID |
|---|---|---|
| arm64-v8a | `67d3f29decb2594babb06370387deea105ebba18` | `5d7f4a1664c7182b8434518a44b362f923bb9d52` |
| armeabi-v7a | `1d85d6f9fc2843b48fe6b3941d47abdff6b8d330` | `9d347a52ec665382b69961c02ce257c882fb87fe` |
| x86 | `97608acbd5f14a068af72dc464565c6732be92e1` | `5913b2857c720c9204d739fd5396f0bdfd971915` |
| x86_64 | `ed02062380e2d99b422c45fa7f0439890a1ba42c` | `bec80954624b7c9970d299dd7dafc6b4bafba818` |

四者 `DT_NEEDED` 集合相同：`libusb100.so`, `liblog.so`, `libc.so`, `libm.so`, `libstdc++.so`, `libdl.so`。这确证动态依赖名称，不确证依赖的作者、版本和许可。

`.note.android.ident` 标记 NDK `r21`、build `6113669`；64 位 API 21，32 位 API 17。Clang 9.0.8 等字符串是嵌入构建线索，不能代替可复现 recipe；build ID 也不是源码承诺。

## 3. 引入和更新历史

脚本分别查询四 ABI 的新旧路径，到固定提交为止；对跨 100 文件的提交继续翻页。下列首次引入指这些已知路径的可达官方历史，不覆盖未知改名、重写或私有历史。

| 时间 UTC | 提交与可核查变化 |
|---|---|
| 2022-09-14 10:22:09 | [`0781eb5c`](https://github.com/jiangdongguo/AndroidUSBCamera/commit/0781eb5c6fea87c69a81077c8bbc8564c39a15a3) 首次加入 `libuvc/libs/{arm64-v8a,armeabi-v7a}/libUACAudio.so`，信息为 support record device mic(UAC)。ARM64 当时 blob `4865dd8a8780ebce50df7823a99e9106aaa0218c`，ARMv7 `ca0c2bcf3071cf35a633ea53a8f2dcfb50c96bf3`。Java UACAudio 在该提交为修改而非首次加入。 |
| 2022-09-15 02:25:37 | [`bd1d02fb`](https://github.com/jiangdongguo/AndroidUSBCamera/commit/bd1d02fbfc9e14657194a93264577eeafb3c3b9f) 将两个 ARM 文件移到 `src/main/jniLibs`，blob 不变。 |
| 2023-02-10 09:14:55 | [`ccdb068d`](https://github.com/jiangdongguo/AndroidUSBCamera/commit/ccdb068d3aa0e89ce0147ba82228f4c48752c2a4) 更新两个 ARM 文件为本次固定 blob。提交含 108 个改动文件，核查两页。 |
| 2023-02-10 09:39:04 | [`829d32ed`](https://github.com/jiangdongguo/AndroidUSBCamera/commit/829d32edf581bd9edf7766adfb1d30985f892db3) 首次加入两个 x86 文件，恰为本次固定 blob。 |

## 4. 来源、许可证和证据等级

| 等级 | 发现 | 能支持什么／不能支持什么 |
|---|---|---|
| 字节确证 | 四库均含 `jni/usbAudio/native_usb_audio.cpp` 字符串；导出 `JNI_OnLoad`、`JNI_OnUnload` 和八个 `Java_com_jiangdg_uac_UACAudio_native...`；导出 USBAudio 方法及 `_uac_process_payload_iso`、`_uac_stream_callback`。 | 与 Java JNI 接口相符，给出缺失源码的搜索线索；不证明版权归属或开源许可。 |
| 固定源码确证 | 固定 ZIP 的 427 个 C/C++/header 文件对 JSON 中列出的 UAC、USBAudio、PortAudio 等明确模式零匹配；引入时 tree 也只发现 UAC Java/Kotlin 包装和 ARM 二进制。顶层 native Android.mk 没有 UAC 构建目标。 | 本次搜索范围内没找到对应 UAC native source/recipe；不能据字符串搜索证明功能或源码在全世界不存在。 |
| 依赖确证 | UAC undefined 符号含 `libusb_init2`、`libusb_get_device_with_fd`、`libusb_set_device_fd` 等，且 `DT_NEEDED libusb100.so`。 | 使用改造 libusb 接口的强线索，仍须单独核对对应 blob/source/license。 |
| 候选来源 | vendor libusb 宏版本 **1.0.19.10903**；有同名构建目标和定制 API。 | 是候选对应源码，不是现有 `.so` 的可复现绑定。 |
| 未确证 | 无 `libuvc` 或 `PortAudio` 动态依赖项，未发现 PortAudio 显式字符串/源码模式；`libUVCCamera` 字符串可能是日志 tag。 | 没有证据把 UAC 归为某个 libuvc/PortAudio 版本；动态依赖和字符串缺失无法排除静态复制、改名或 stripped 代码。 |
| 工具链线索 | ARMv7 含 unwind 导出和 NDK libunwind 路径。 | 需在最终构建组成清单核对工具链 runtime 的适用许可；无法直接推导全部 runtime 版本/组成。 |

根目录 `LICENSE` 为 Apache 2.0，但没有发现对这些预编译 UAC 文件中全部代码的作者/授权范围说明，不能以根许可证自动填补第三方原生组成许可。也未发现维护者明确声明 UAC“闭源”或允许特定用途分发；不要把未找到授权当成没有授权。

维护者 jiangdongguo 在 [issue #494 的 2022-11-21 回复](https://github.com/jiangdongguo/AndroidUSBCamera/issues/494#issuecomment-1321350701)说“音频还没调通，还有点问题。”该 OWNER 评论已原样入缓存；它是开发状态，不是源码发布或许可声明。[#677](https://github.com/jiangdongguo/AndroidUSBCamera/issues/677#issuecomment-2295123988)、[#723](https://github.com/jiangdongguo/AndroidUSBCamera/issues/723#issuecomment-3332338416) 中用户称找不到源码，仅作第三方线索，不能替维护者表态。

[PR #753](https://github.com/jiangdongguo/AndroidUSBCamera/pull/753) 此次快照为 open、未合并，base 恰为固定提交，head `ffbf0fb17ad9baefd229e87ea990d11e56f421fe`。其文件列表删除四个 UAC blob。这不是为四库补充源码或授权，也没有为本项目提供音频或硬件验证；不得把该 PR 当成已合并的解决方案。

## 5. libusb100 的候选源码与视频保留义务

固定仓库前缀 `libuvc/src/main/jni/libusb/`：

- `COPYING` L1–2 为 LGPL 2.1；`libusb/core.c` L12–15、`libusb/libusb.h` L9–12、`android/jni/libusb.mk` L7–10 明确 **LGPL-2.1-or-later**。
- `libusb/version.h` L2/4/7/10 与 `version_nano.h` L1 组合为 **1.0.19.10903**。这是 vendor 源码版本，未调用二进制获取版本。
- `core.c` L3–5 标注 saki 的 Android no-root 改造；L1249 与 L2039 分别实现 `libusb_get_device_with_fd`、`libusb_init2`。
- 顶层 `libuvc/src/main/jni/Android.mk` L6 → `libusb/android/jni/Android.mk` L21 → `libusb.mk`。后者 L30–40 列出源文件；L63–64 为 `libusb100_static`；L73 whole-static 链接；L75–76 为共享目标 `libusb100`。

这些固定源码和 recipe 支持进一步重建审计，但尚缺实际构建输入、工具链配置与四 ABI 结果的验证。声明 LGPL 和记录 recipe 本身并不完成随软件提供通知、适用源码与修改、重新链接等发行义务；具体发行方案应按 [LGPL 2.1 原文](https://www.gnu.org/licenses/old-licenses/lgpl-2.1.html)核对。

**排除 UAC 不等于排除 libusb。** 缓存中四 ABI 的 `libUVCCamera.so` 与 `libuvc.so` 都有 `DT_NEEDED libusb100.so`；`libuvc.so` 还需要 `libjpeg-turbo1500.so`。视频最小路径仍需处理 libusb、libuvc、JPEG 与工具链 runtime 的源码/许可证据。不能将 libusb 统称为可删除的“音频依赖”。

## 6. 默认打包与视频最小路径

固定 [`libuvc/build.gradle`](https://github.com/jiangdongguo/AndroidUSBCamera/blob/426ed0a5da2c826197761cc5a89ba3ae9c52bd7c/libuvc/build.gradle) 没有 UAC 条件、JNI 包装排除或 `sourceSets` 重定位，四库处于标准 `src/main/jniLibs/<ABI>`。按 [Android 官方预编译库打包约定](https://developer.android.com/studio/projects/gradle-external-native-builds#jniLibs)，这些文件默认进入模块的原生打包集合；consumer 排除/ABI 过滤可改变最终包。本次未生成 AAR/APK，因此这属于配置和官方规则支持的结论，不能冒充最终产物检查。

`libausbc/build.gradle` 引用 `project(':libuvc')`，而 `UACAudio.java` L27 静态调用 `System.loadLibrary("UACAudio")`。`libuvc/build.gradle` L36–59 在 Java 编译前调用 ndk-build，但其 native targets 仅列 UVCCamera、libjpeg-turbo、libusb、libuvc，并未提供 UAC 源码构建目标。

[`CameraRequest.kt`](https://github.com/jiangdongguo/AndroidUSBCamera/blob/426ed0a5da2c826197761cc5a89ba3ae9c52bd7c/libausbc/src/main/java/com/jiangdg/ausbc/camera/bean/CameraRequest.kt) L37 默认 `SOURCE_AUTO`；L221 起含 `NONE`。[`MultiCameraClient.kt`](https://github.com/jiangdongguo/AndroidUSBCamera/blob/426ed0a5da2c826197761cc5a89ba3ae9c52bd7c/libausbc/src/main/java/com/jiangdg/ausbc/MultiCameraClient.kt) L414–447 中，AUTO 在设备麦克风不适用时可回退系统麦克风，NONE 落入返回 null 的分支。Sidecar 视频路径必须显式选择 NONE 并验证没有其他入口初始化 UAC/麦克风；运行期 NONE 不会自动删除包内 `.so`。

20 库依赖图未见视频 `.so` 的 UAC 动态边，因而**定制视频打包排除 UAC 有静态可行性**。仍需独立工作项验证 Java 类初始化/其他 native 加载路径、所有目标 ABI 的 APK/AAR 内容、link/load 行为及保留库的许可证。这里没有修改配置或以“尚未调用音频”替代二进制分发审查。

## 7. 门禁关闭条件与搜索局限

以下条件尚未完成；继续维持真实采集、候选二进制进入应用和分发门禁关闭：

1. 若保留 UAC：取得四 blob 与相应 native 源码/完整构建输入的映射，或足以覆盖全部原生组成的可验证授权；确认作者/第三方清单、许可证、通知与源码提供义务。版本号、JNI 名称、根 README 或许可文件都不替代此映射。
2. 若选视频最小路径：先审阅明确排除 UAC 的构建改动，再验证每 ABI 产物无 UAC 文件/加载入口，显式关闭音频和系统麦克风回退。不得将排除 UAC 推导为整个候选栈许可已清。
3. 对保留的 libusb/libuvc/JPEG 和工具链 runtime 形成固定源码、构建材料、组成和许可证证据，并核对实际产物；把“候选源码存在”提升到可审阅的 source-to-artifact 链。
4. 静态和模拟验证不替代另行授权的受控实机验证：设备支持、USB 权限、连接/断开/重连、帧格式与设备生命周期仍未实测。许可闭环也不等于硬件兼容。

搜索范围包含：固定源码完整 ZIP 与非截断 tree；已知新旧路径的可达提交历史、首次 ARM 引入 tree；官方 issues 中 `libUACAudio`/`libuac`/`UAC source`/`UAC 源码` 及关联评论；官方发布记录/version.md；作者公开项目列表中相关 `UVCCameraLib` 的固定 tree `3d7186e73efe4d3c317b61992e5d200e8b21949d`；PR #753。除脚本列入清单的输入外，补充网页调查仅作搜索范围记录，不声称已完整离线归档。

局限：未遍历所有 fork、私有/删除历史、全部语言拼写或无符号实现。源码模式扫描仅限列出的扩展名、每文件不超过 4 MB，大小写敏感；完整模式列于 JSON。`DT_NEEDED` 不揭示静态组成；符号、日志 tag、路径和版本字符串都不能证明作者与授权。未发现不是不存在。
