# UAC 第三轮：精确源码线索与系统音频替代边界

日期：2026-11-10。工作区基线 `e189341cdf339be8bd6d72284dcec44bccfa7319`。原始对象仍为 AndroidUSBCamera `426ed0a5da2c826197761cc5a89ba3ae9c52bd7c` 的四 ABI `libUACAudio.so`；身份详见既有 `provenance/uac/static-evidence.json`，本轮以该文件 SHA-256 绑定继承证据。

**找到更强的来源线索，但没有完成四个旧 blob 的对应源码和授权链。** `jim0608/android_usbaudio` 的 15 个 USBAudio 方法名均与旧库导出相符，NDK 版本也吻合；然而 JNI、模块名和 ABI 配置不同，相关源文件没有找到明确授权。另一个项目后来重写相同 JNI 接口，其许可和构建栈独立，不能反向证明旧 blob 的来源。原生候选继续禁用，不将公开 fork 或相同符号当作许可证明。

系统 USB 音频输入加 `AudioRecord` 是可继续 G0 设计的替代，但设备枚举、首选路由、实际路由和健康是不同证据。尚未验证具体设备、OEM 音频策略或四路实听回放，不能据 API 推定 G2 完成。

## 证据包与重放

- `collect_followup.py`：Python **3.14.2** 标准库静态采集器；本轮未安装依赖、未编译、加载或运行 native 库。此前 ELF 审计使用环境原有 pyelftools 0.33，仍单独记录，不将整个历史审计称为 stdlib-only。
- `retrieval-index.json`：57 个官方 API/raw/AOSP/文档响应的 URL、SHA-256、字节数。下载仅在 ignored `android/uvc-sim/build/native-research/uac/`；没有复制第三方源码进应用。
- `evidence.json`：6 个固定仓库 tree/源码身份、导入史、JNI/方法名比较；AOSP 固定源码和官方文档快照。GitHub raw 源文件还核对固定 tree 的 Git blob SHA-1。
- `discovery-log.json`：精确查询、返回的作者项目列表和跟进候选；代码搜索依赖默认分支索引，不是完整历史或全网证明。
- `check_replay.py`：离线结果重放、缓存缺失/篡改、在线篡改和锁检查。

在工作区根目录：

```powershell
# 默认离线；不仅验缓存，还把重算结果与现有 evidence 和输入集合逐项比较：
python android/uvc-sim/provenance/followup/uac/collect_followup.py --verify-only
python android/uvc-sim/provenance/followup/uac/check_replay.py
# 新机器只有已审阅清单时，补齐缺失缓存；已有输入不能被重新定 SHA：
python android/uvc-sim/provenance/followup/uac/collect_followup.py --online --verify-only
```

只有明确 `--online --record` 才允许新增 URL 的首次快照；已锁定 URL 仍必须匹配原 SHA。已有缓存全部先验证，离线禁止网络回退。独占 `collection.lock` 防并发采集，已有锁不自动删除；缺缓存、内容变化或锁冲突会失败，保留先前证据。临时篡改副本只在本轮 ignored 缓存子目录；原缓存和证据不改动。JSON 在所有输入与解析成功后原子替换每个文件。

`--verify-only` 能发现相对于已审阅结果的算法/证据变化，不只是“读得开”。可信根仍是审阅过的脚本和清单，不提供数字签名，也不保护被同时恶意重写的脚本、清单和结果。人工报告与搜索记录由审阅绑定；采集器不声称重新运行搜索引擎或自动证明报告文字。重放比较包含记录的 Python 版本，换解释器后需要审阅版本差异。

四个官方文档 HTML 是可变页面的锁定快照，仓库元数据 API 也是快照。未来空缓存在线补齐时，若官方已改页面/元数据而返回字节不同，会因 SHA 不符拒绝重建；不得自动刷新清单来让验证变绿。复用经核验的现有缓存可离线回放，或在单独审阅中新增快照并说明差异。核心路由判断绑定六个 AOSP 固定源文件，而非依赖网页长期不变。

本轮验证结果：完整离线重算与记录结果一致；缺缓存、离线/在线篡改、已持有锁均被拒绝；另将结果副本改写后，`--verify-only` 明确报重算不一致。所有失败检查均保持原结果、清单和原缓存不变。检查 PASS；6 仓库、6 固定 AOSP 文件、4 文档、57 输入数量一致。

## 1. 精确搜索及来源等级

连接的 GitHub code search 查询 `native_usb_audio.cpp`、`_uac_process_payload_iso`、`Java_com_jiangdg_uac_UACAudio_nativeInit`、`USBAudio fill_iso_transfer`。作者范围 `jiangdongguo` 对前三种原生名称/USBAudio 没有命中；UACAudio 只命中既有 Java/Kotlin 包装。作者项目搜索返回 77 个条目，按名称/职责选取 UVCCameraLib、AndroidCamera；没有声称全部逐源文件审计。

| 固定对象 | 确认事实 | 证据边界 |
|---|---|---|
| [`jim0608/android_usbaudio@35ba8eee`](https://github.com/jim0608/android_usbaudio/tree/35ba8eee66a5c212a6f7f90caab8814c392b137e) | 有 `usbAudio/native_usb_audio.cpp`、USBAudio 类及 `_uac_process_payload_iso`；15 个旧库 USBAudio 方法名全部出现。 | 强来源相似线索；没有证明直接复制关系、完整 patch 链或四 ABI 字节对应。 |
| [`sky22333/camplayer@5ff668d0`](https://github.com/sky22333/camplayer/tree/5ff668d0cd60a506c1db7fc8bbe0b4001c557d7b) | `app/src/main/cpp/uac_jni.cpp` 实现八个相同的 com_jiangdg JNI 名称。路径首次公开提交在 2026-07-19。 | 后来的接口兼容实现，不能仅凭相同 JNI 认定为 2022/2023 二进制源。 |
| [`nExtCamera/libuac@17a6ba0a`](https://github.com/nExtCamera/libuac/tree/17a6ba0a09309a6890ec338826de43a256f89813) | 原生 C++ API、明确 Apache 2.0 声明、源码头作者 Jakub Księżniak；可追溯到 2023-06-18 的 `uac_context.cpp` 首次提交。 | 可单独研究的新实现；不提供旧四库授权。硬件适用性未验证。 |
| [`jiangdongguo/UVCCameraLib@3d7186e7`](https://github.com/jiangdongguo/UVCCameraLib/tree/3d7186e73efe4d3c317b61992e5d200e8b21949d)、[`AndroidCamera@ab1a097c`](https://github.com/jiangdongguo/AndroidCamera/tree/ab1a097c8051fa955d0e65d32080c16e6e1bcf9a) | 固定非截断 tree 无 UAC/USBAudio 名称路径。 | 文件名层面的负面证据；没有据此证明改名实现或其他历史不存在。 |
| [`siddhesh-bhadale/AndroidUSBCamera-fork@ffbf0fb1`](https://github.com/siddhesh-bhadale/AndroidUSBCamera-fork/tree/ffbf0fb17ad9baefd229e87ea990d11e56f421fe) | 即此前 PR #753 固定 head；UAC 相关路径保留包装，四旧二进制已从 tree 删除，无 UAC native 文件。 | fork 删除/复制二进制及根 Apache 文件都不补足原四库授权；未采用该分支。 |

方法名、目录名和编译器标记只能支持候选关系；“来源相似”“可重建的对应源码”“完整授权链”是三个不同结论。本轮只增强第一项。

## 2. Jim 候选：相似最强，但 JNI、ABI 和授权仍断开

固定目录 [`app/src/main/jni/usbAudio/`](https://github.com/jim0608/android_usbaudio/tree/35ba8eee66a5c212a6f7f90caab8814c392b137e/app/src/main/jni/usbAudio) 首次出现在 [`79491886c7584a9a99b9dadab552ceac5a0004c8`](https://github.com/jim0608/android_usbaudio/commit/79491886c7584a9a99b9dadab552ceac5a0004c8)，2021-11-26T02:47:23Z。其父 `7b0d728d0e5768c345e1fe797ddcc071092a04ca` tree 无该目录；首次引入的五个源文件及 Android.mk 的 blob SHA 与固定版本一致。两份 tree 和两条路径完整历史均入缓存。

| 项目 | Jim 固定源码 | 旧 AndroidUSBCamera 四库 |
|---|---|---|
| JNI | 9 个 `Java_au_id_jms_usbaudio_USBAudio_*`，含 Create/Close/StartCapture/StopCapture/IsRunning。 | 8 个 `Java_com_jiangdg_uac_UACAudio_*`，含 Init/Release/StartRecord/StopRecord/GetRecordingState；完整 JNI 名精确重合 **0**。 |
| native 方法 | 15 个 USBAudio 方法名重合，包括 fill_iso_transfer、scan_audio_interface、set_sample_rate_v1。 | ELF dynamic symbol 记录同名方法；这不是机器码或 ABI 等价证明。 |
| 模块 | `usbAudio/Android.mk` L7：`USBAudio`；L8：动态链接 libusb100。 | SONAME 为 `libUACAudio.so`。 |
| ABI | `Application.mk` L1–2：android-17、仅 armeabi-v7a 和 arm64-v8a。 | 另有 x86、x86_64；64 位 note 标 API21，32 位 API17。 |
| 工具链 | `app/build.gradle` L43：NDK 21.0.6113669。 | ELF note 标 r21/build6113669，与其吻合。尚缺修改、flags、runtime 组成及字节重现。 |
| 权利主体与授予 | 五源文件开头为 Created by Admin 日期；未发现文件授权条款，固定 tree 无根 LICENSE，仅嵌套 libusb/COPYING。 | 对应 native 的版权主体、全部组成和授权范围仍未确定。GitHub 用户 jim0608 不是据此即可认定的唯一权利人。 |

嵌套 libusb 的 LGPL 文本不能自动给 USBAudio 新代码授予许可证；缺少声明也不等同于侵权或不可能另获授权。没有联系维护者，也没有得到维护者对四 blob 的新增声明。

## 3. 后来的兼容实现和可单独研究的 libuac

[`camplayer/uac_jni.cpp`](https://github.com/sky22333/camplayer/blob/5ff668d0cd60a506c1db7fc8bbe0b4001c557d7b/app/src/main/cpp/uac_jni.cpp) 头部说明其使用 libuac 加 AUSBC libusb100；首次路径提交为 [`765b3a9cec3a01c508fecd8b45cc30ea9592f515`](https://github.com/sky22333/camplayer/commit/765b3a9cec3a01c508fecd8b45cc30ea9592f515)，2026-07-19。根 `LICENSE` 声明 Copyright 2026 sky22333、GPL v3；JNI 文件没有发现另授 Apache 的例外。`third_party/libuac` 有自己的 Apache 2.0 LICENSE，`uac_context.cpp` 头标 Jakub Księżniak 2023，并注明针对 AUSBC init2/get_device_with_fd 的修改。两层许可必须区分；不能以子树 Apache 为由自动复制整个 JNI/应用。

其 `app/build.gradle.kts` L23 仅 ARM64/ARMv7，L34 NDK 28.2.13676358；CMake 使用 C++17，为 64 位设置 16KB alignment，仍导入预编译 `libusb100.so`。这不是原四 ABI、NDK r21 的构建链。该项目原生实现也未由本轮编译或验证。

独立上游 [`nExtCamera/libuac`](https://github.com/nExtCamera/libuac/tree/17a6ba0a09309a6890ec338826de43a256f89813) 的 `src/uac_context.cpp`、`src/uac_streaming.cpp`、`include/libuac.h` 有同一作者的 Apache 2.0 头；`android/libuac.mk` L9/11/33 构建静态 libuac 并依赖 `libusb1.0`，没有旧 com_jiangdg JNI。此处明确权利声明能支持**另起实现审查**，不能给旧 UAC blob 补授权。若选此方向，仍需验证 Android FD/权限接入、libusb 固定源码和 LGPL 义务、JNI/生命周期、所有目标 ABI/runtime，不能沿用 camplayer 的未知预编译 USB 库或把同名 API 当作现成闭环。

README 的 UAC1/应用测试表述只记录为上游自述，本报告不据此声称采集卡兼容或采集可用。

## 4. 系统 USB 音频 + AudioRecord 的固定证据

AOSP 固定 tag **android-15.0.0_r1** 解析到 commit `4e43ad12e1b211f15152e9c5b16b0fe88e6b93f3`，tree `9583c6d9586b84169697623a44af09e8ac3d79b9`。实际 OEM/系统版本行为仍需验证，不将该 tag 当成目标手机的实测结果。

| 证据 | 可得结论 |
|---|---|
| [`AudioDeviceInfo.java`](https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-15.0.0_r1/media/java/android/media/AudioDeviceInfo.java) L87、134、455 | TYPE_USB_DEVICE/TYPE_USB_HEADSET 和 isSource 可帮助筛选系统已识别的 USB 输入；不代表所有 UsbManager 设备均成为音频端点。 |
| [`AudioManager.java`](https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-15.0.0_r1/media/java/android/media/AudioManager.java) L7842、7925–7940、8047–8069 | GET_DEVICES_INPUTS、getDevices 和设备增删通知；该 tag 注释还提示同类型多设备的枚举限制，不能保证任意多卡。 |
| [`AudioRecord.java`](https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-15.0.0_r1/media/java/android/media/AudioRecord.java) L2144–2177 | setPreferredDevice 是偏好；getPreferredDevice 明确不保证实际录音设备。传 null 会恢复默认选择，不能作为外置输入丢失的恢复操作。 |
| 同文件 L1908–1919、2018–2035 | getRoutedDevice 仅在录音时有效，可为 null；路由回调经 Handler 异步投递。设备列表回调不能代替实际路由监听。 |
| [`UsbAlsaManager.java`](https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-15.0.0_r1/services/usb/java/com/android/server/usb/UsbAlsaManager.java) L182–188、316–351、421–428 | 自动 USB 路由可被系统设置禁用；系统据 descriptor/denylist 建立音频输入；拔除后会尝试默认或其他设备。应用不能假定系统留在失效外置路由。 |
| AudioRecord L2230 起及 [`AudioRecordingConfiguration.java`](https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-15.0.0_r1/media/java/android/media/AudioRecordingConfiguration.java) L287–294 | 配置变更/策略静音有额外观察接口；isClientSilenced 只是框架策略静音，不能涵盖链路无声、接错线或只录一方等问题。 |

[Android 官方 USB audio 文档](https://source.android.com/docs/core/audio/usb)描述 UAC1 子集、host、PCM Type I/单声道双声道与 OEM USB/音频策略、互操作条件；不能扩张为 UAC2、多通道或任意采集卡保证。系统识别、供电、设备描述符、输入输出端点和 OEM 策略仍有设备验证缺口。

系统 AudioRecord 仍需 RECORD_AUDIO。后台或并发录音可能被系统静音；target 30+ 录音前台服务需要 microphone 类型，target 34+ 还涉及 FOREGROUND_SERVICE_MICROPHONE 与 while-in-use 限制。具体生命周期应按应用最终目标 SDK 与系统验证，不能借系统 USB 路线绕过这些条件。[共享输入](https://developer.android.com/media/platform/sharing-audio-input)、[Android 11 前台服务](https://developer.android.com/about/versions/11/privacy/foreground-services)、[Android 14 服务类型](https://developer.android.com/about/versions/14/changes/fgs-types-required#microphone)

**切换窗口仍是未决边界。** 首选设备设置成功、实际路由匹配一次、read 返回正数、未被策略静音，各自都不足以证明整段音频来自目标外置输入。公开 route 查询/回调没有与每个 PCM 帧的原子绑定保证；系统重选与应用获知之间可能存在窗口。未来设计至少应隔离 pending 数据、对断开/路由不符/null/权限与配置变化停止接收并保留未知，禁止主动静默改用默认麦克风。仅此设计仍不能宣称零窗口误收，必须通过目标平台证据和实听验证；若验收要求不可证明，应继续阻断该路径。

## 5. 替代路线与 PRD 边界

| 路线 | 当前可继续工作 | 仍阻断的事项 |
|---|---|---|
| 系统识别外置 USB 音频，AudioRecord 接收 | G0 的设备身份、实际路由/健康观测、失败停止策略和模拟生命周期设计；可避开这组自带 UAC native blob。 | OEM 枚举、真实路由、策略静音、切换窗口、双向内容和 G2 四路实听回放。系统路线不证明视频 native 许可已清。 |
| PRD 允许的外置音频接口/混音或提取链路 | 明确 C 与 D 的实际接线和语音来源，设计可观测链路。 | 是否真的双向可听、回声/混音/延迟/供电与每路回放；手机监听中转不成为默认。 |
| 单独从明示许可的 nExtCamera/libuac 构建新接入 | 固定源码、完整传递依赖/许可和构建 recipe 的独立研究。 | 不是旧库授权修复；新 JNI、USB FD、libusb/runtime、ABI、生命周期和设备验证均未完成；本轮不接入。 |
| 保留原四库，或直接搬 Jim/camplayer 代码 | 继续源码/权利证明调查。 | 原四库来源未决；Jim 授权未找到；camplayer 的根 GPL 与 Apache 子树边界未形成项目采用方案。 |
| 暂缓真实采集集成 | 保持既有模拟与 G0 工作，记录未知和失败原因。 | 不把模拟或静态结果转记成 G2 验证通过。 |

按已核 PRD：5.1 允许外置音频接口/混音或提取链路，手机监听中转非默认；5.2 只通 C 不能生成双向纪要，可标远端记录，绝不静默换麦；5.4 必须保留实际路由和健康未知；G0 可继续，G2 四路实听回放不可替代。本轮没有增加 PRD 状态、功能或测试编号。

## 6. 尚未满足的解除条件

原四库若继续采用，需取得与全部四 ABI blob 绑定的原生源码、修改/构建输入和完整作者/第三方授权范围；相似类、匹配 NDK、复制进公开 fork 或后来兼容 JNI 都不够。若改用系统路线，需明确目标平台的输入识别和 route/PCM 可验证关系及失效策略，再完成受控 G2；若另建 native 实现，需独立完成其整条源码、许可、构建和设备链。门禁仍关闭，无联系第三方、生产修改、安装、发布或 commit。

搜索局限：代码索引只覆盖其可见默认分支，可能遗漏 fork、改名、私有/删除历史；只跟进有精确命中的候选及相关作者项目，没有穷尽世界上所有实现。来源搜索中的阴性结果、GitHub license=null、或源码缺少许可头均不等价于法律认定。官方文档为锁定响应快照，AOSP 为固定 tag；它们描述 API/平台实现边界，不替代目标实机结果。
