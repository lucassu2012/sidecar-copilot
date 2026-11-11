# 官方 libusb + libuvc 的 Android fd 路线

本轮建议：以官方 libusb 1.0.30 + libuvc 0.0.9 为新的最小驱动候选，另写小型 Android/JNI 适配器；**不能把两个原版库直接视为已可满足 Sidecar 的关闭与资源合同**。官方 fd 入口、协商及 bulk/iso 传输实现已存在，不需要仅为 fd 导入而保留 UVCAndroid 的旧 libusb/libuvc fork。状态中断传输释放、无界控制请求/停止等待、分配失败处理仍须核清或作可追踪的小范围修复。以下是固定源码的静态结论；未构建或运行上游代码、未连接设备、未验证手机/采集卡兼容性。

## 固定来源与检查范围

截至 2026-11-10 查询官方 release API，libuvc 最新发布是 **0.0.9**。此前搜索页显示的 0.0.8 不能作为“当前最新”的依据。时间均为 UTC，发布时刻与 commit 时刻分开记录。

| 官方项目 | 发布 / 发布时刻 | 固定 commit / commit 提交时刻 |
| --- | --- | --- |
| [libusb](https://github.com/libusb/libusb/releases/tag/v1.0.30) | v1.0.30 / 2026-05-17 22:06:02 | `87a55632db62c9bdc58cd31d3ccfa673f1bb017f` / 2026-05-17 14:37:21 |
| [libuvc](https://github.com/libuvc/libuvc/releases/tag/v0.0.9) | v0.0.9 / 2026-10-29 07:43:16 | `448d78b9aa4f54172c1ca5b5d1b4a6b9b93f7957` / 2026-10-29 07:40:50 |
| [libuvc 历史对照](https://github.com/libuvc/libuvc/releases/tag/v0.0.8) | v0.0.8 / 2026-09-13 04:48:49 | `4e9fc773914377ec0bcf2f31621f56da5a0fa09f`；本轮仅锁发布/tag 元数据，未据此评审旧版实现 |

[inputs.lock.json](inputs.lock.json) 固定 49 个输入，共 1,052,129 字节，包含 release/tag/commit/tree 元数据和选定文本源码；每项记录来源 URL、原始字节数与 SHA-256。源码另核对 Git blob SHA-1 与固定 commit 的非截断 tree。release API 使用具体 release ID 重放，运行时不查询 `latest`。这不是完整源码包、编译输入闭包、供应链签名验证或最终制品锁。[evidence.json](evidence.json) 记录可复跑字面位置及构建声明；定位不是编译调用图，手工控制流结论不能由“发现函数名”替代。

## 可实现调用链及所有权

1. Android 侧发现并选择正确 USB 设备，取得该次连接的授权，以 `UsbManager.openDevice` 保持一个活着的 `UsbDeviceConnection`，取出 fd。必须把设备插拔代次、授权 revision 和采集会话绑定起来；本库不会实现 Sidecar 的屏幕确认与授权状态机。libusb 自带 Android 示例明确接收 `getFileDescriptor()`，但示例中旧的全局 `libusb_set_option` 写法不能优先于当前 API 文档。[官方示例](https://github.com/libusb/libusb/blob/87a55632db62c9bdc58cd31d3ccfa673f1bb017f/android/examples/unrooted_android.c#L29)
2. JNI 所有者建立独立 `libusb_context`，通过 `libusb_init_context(&usb, options, 1)` 在初始化时设置 `LIBUSB_OPTION_NO_DEVICE_DISCOVERY`。这跳过通用枚举，不授予权限，也不保证 Android 内核/SELinux/USB 控制器接受全部 ioctl。随后 `uvc_init(&uvc, usb)` 借用这个上下文，适配器维护 libusb 事件线程；libuvc 在借用上下文时不会替调用方处理事件或销毁上下文。[libusb core.c 1371–1407](https://github.com/libusb/libusb/blob/87a55632db62c9bdc58cd31d3ccfa673f1bb017f/libusb/core.c#L1371)，[libuvc init.c 94–161](https://github.com/libuvc/libuvc/blob/448d78b9aa4f54172c1ca5b5d1b4a6b9b93f7957/src/init.c#L94)
3. 调用 **`uvc_wrap(fd, uvc, &devh)`**；该公开 API 的 fd 参数是 `int`，内部已经调用 `libusb_wrap_sys_device`，不能再重复包装同一 fd 并把 libusb handle 当 fd 传入。Linux backend 直接使用 fd 并设 `fd_keep=1`，没有在此复制 fd。`uvc_close` / `libusb_close` 均不替 Android 所有者关闭原 fd；原连接至少须活到 native 清理完成。若未来改为 `dup`，必须另定义唯一关闭责任，本轮没有实现该变体。[libuvc device.c 260–298](https://github.com/libuvc/libuvc/blob/448d78b9aa4f54172c1ca5b5d1b4a6b9b93f7957/src/device.c#L260)，[libusb linux_usbfs.c 1407–1492](https://github.com/libusb/libusb/blob/87a55632db62c9bdc58cd31d3ccfa673f1bb017f/libusb/os/linux_usbfs.c#L1407)
4. 从实际描述符选择精确格式、尺寸、帧间隔，检查 probe/commit 与最终控制块，然后 `uvc_stream_open_ctrl` / `uvc_stream_start`。简便函数 `uvc_get_stream_ctrl_format_size` 接收整数 fps，比较 `10000000 / interval`，不构成 29.97/59.94 等精确时序合同；`fps=0` 不可用作 Sidecar 静默选模式策略（连续间隔分支还会除以 fps）。应明确选定描述符/间隔并确认结果。[stream.c 498–565](https://github.com/libuvc/libuvc/blob/448d78b9aa4f54172c1ca5b5d1b4a6b9b93f7957/src/stream.c#L498)，[commit 及当前控制块](https://github.com/libuvc/libuvc/blob/448d78b9aa4f54172c1ca5b5d1b4a6b9b93f7957/src/stream.c#L406)
5. callback 内只做有界检查/复制与投递。`uvc_frame_t` 有尺寸、格式、`data_bytes`、`step`、32 位 sequence 和时间字段，但没有 Sidecar 要求的会话 bootId、授权 revision、色彩矩阵/范围、alpha 等完整合同。不能假定 `step` 对每种未压缩格式都已正确设置：`_uvc_populate_frame:1405–1428` 为 BGR 设 3W、YUYV 设 2W，RGB 仍进入 default 得到 0，MJPEG/H264 也为 0；须依据已验证布局派生或拒绝，不能直接送进要求正 stride 的桥。`strmh->frame` 和 `frame.data` 会在后续帧复用/realloc，close 会释放，不能把该地址的 DirectByteBuffer 留给 Java 异步使用。当前公开头还明确限制 callback 期间调用 `uvc_*`；不能在同 callback 同步 stop/close，或未经论证直接用 `uvc_duplicate_frame` 绕过该限制。[frame 定义](https://github.com/libuvc/libuvc/blob/448d78b9aa4f54172c1ca5b5d1b4a6b9b93f7957/include/libuvc/libuvc.h#L461)，[复用与复制](https://github.com/libuvc/libuvc/blob/448d78b9aa4f54172c1ca5b5d1b4a6b9b93f7957/src/stream.c#L1356)
6. 停止请求先触发 Sidecar 的遮盖与发布失效，再在独立 native 控制线程停止/关闭 stream，保持事件线程运行以收取消完成。**完成下文状态中断修复与验证后**，才可把次序定为：所有 native transfer/callback 完成 → `uvc_close` → `uvc_exit` → 停止并 join 自管事件线程 → `libusb_exit` → 唯一所有者关闭 Android 连接。Java/JNI 自有队列和缓冲另行排空。不能以 timeout 到期、调用过 stop 或流 callback join 作为“全部资源已释放”的成功回执。

## 已有能力与未被证明的能力

| 项目 | 源码实有能力 | 边界及适配责任 |
| --- | --- | --- |
| 无 root fd 导入 | 公开 `uvc_wrap` → `libusb_wrap_sys_device`，可配合禁止通用枚举的独立上下文 | 无 root 的源码路径成立；具体 Android 权限、interface claim、kernel detach、总线/供电仍未实测。不要由 API 存在推出“所有手机可用” |
| UVC 协商 | 描述符、format/frame index、interval、probe/commit、当前控制块 API | 控制请求错误/超时处理见阻断；读取到的 HDMI 主屏、EDID、刷新率和文字可读性需单独验证 |
| bulk / iso | stream.c 根据接口 altsetting 数选择路径，调用 `libusb_fill_bulk_transfer` 或 `libusb_fill_iso_transfer`，解析各自 payload | 有实现，不代表特定卡 endpoint/USB 带宽成功；不能只写“支持 USB 摄像头” |
| YUYV / UYVY | 枚举、传输；内建 `uvc_yuyv2rgb` / `uvc_uyvy2rgb` 输出三通道 RGB | 输出不是 RGBA。现有转换系数形似全范围 601 公式，不能与 limited-range libyuv/设备 color descriptor 直接等同；须另定矩阵、有效长度、步长、偶数宽要求与 alpha |
| MJPEG | 格式枚举/压缩帧传输；只有编译 JPEG 支持才有 `uvc_mjpeg2rgb/bgr/gray` | 依赖另选且固定的 JPEG 实现；JPEG 不存在时 CMake 可警告后继续生成无解码库，构建必须明确特性断言。Android CI 禁用 JPEG，不能证明 Android MJPEG 解码已验证 |
| H264 | enum、GUID 映射、frame-based 描述符与压缩帧交付候选路径 | `uvc_any2rgb` 没有 H264 分支，不存在由此证明的 H264 解码器；专用 payload/码流兼容、外部解码及授权都不在本轮完成范围，不静默引入新格式 |
| 热插拔/恢复 | libusb 返回 transfer error/no-device；Android 可负责重新发现 | `NO_DEVICE_DISCOVERY` 的包装 device 不加入通用 managed list；需 Android 事件、超时和失败通知驱动全会话重建、重新确认，不复用旧 handle/fd/generation |
| UAC 音频 | 无本路线视频代码实现 | 音视频授权仍独立，不可依赖“复合 USB 设备”而自动启麦或静默换麦 |

能力证据：[bulk/iso 和提交](https://github.com/libuvc/libuvc/blob/448d78b9aa4f54172c1ca5b5d1b4a6b9b93f7957/src/stream.c#L1174)，[GUID 表](https://github.com/libuvc/libuvc/blob/448d78b9aa4f54172c1ca5b5d1b4a6b9b93f7957/src/stream.c#L101)，[RGB 转换分支](https://github.com/libuvc/libuvc/blob/448d78b9aa4f54172c1ca5b5d1b4a6b9b93f7957/src/frame.c#L494)，[MJPEG 解码及尺寸一致性检查](https://github.com/libuvc/libuvc/blob/448d78b9aa4f54172c1ca5b5d1b4a6b9b93f7957/src/frame-mjpeg.c#L125)。库内 RGB 转换不要求 libyuv；只有另选官方 libyuv 做颜色/布局转换时才加入它。libyuv 不能替代本节 USB/UVC 驱动。

## 关闭及失败路径的明确阻断

以下是静态推断和需要关闭的工程问题，没有 native 复现，未声称发现可利用漏洞，也未修改上游。

**状态中断的取消/完成确认是进入真实接入前待修阻断。** `device.c:358–373` 对带 VC status endpoint 的设备分配并提交 `status_xfer`；`2116–2142` 的回调在成功/重试状态会再次提交。`uvc_close:1933–1958` 只关闭 streams、release control interface、`libusb_close`、`uvc_free_devh`；后者在 `1917–1918` 直接 free status transfer。固定函数体没有显式 cancel/完成等待。[提交](https://github.com/libuvc/libuvc/blob/448d78b9aa4f54172c1ca5b5d1b4a6b9b93f7957/src/device.c#L358)，[状态回调](https://github.com/libuvc/libuvc/blob/448d78b9aa4f54172c1ca5b5d1b4a6b9b93f7957/src/device.c#L2116)，[释放及 close](https://github.com/libuvc/libuvc/blob/448d78b9aa4f54172c1ca5b5d1b4a6b9b93f7957/src/device.c#L1907)

libusb 不能替它补成已排空：`core.c:1557–1593` 遇仍在飞 transfer 会记录未取消并从内部列表移走/清 dev_handle，不是 cancel 完成；`io.c:1325–1326` 明确禁止释放 active transfer。Linux 包装 fd 的 close 又不关闭原 fd。control interface release 是否令某内核立即杀死 URB、回调是否已返回，不能只由上述调用次序推定。可能产生未完成传输/缓冲生命周期错误；当前不据静态文本断言某设备必然崩溃。[libusb do_close](https://github.com/libusb/libusb/blob/87a55632db62c9bdc58cd31d3ccfa673f1bb017f/libusb/core.c#L1551)，[free transfer 约束](https://github.com/libusb/libusb/blob/87a55632db62c9bdc58cd31d3ccfa673f1bb017f/libusb/io.c#L1317)

最小修复方向：在 libuvc 内部建立同步保护的 closing / status-active 生命周期；关闭先禁止重提交，取消在飞 status transfer，继续泵事件，等待终态 callback 及在途 callback 离开后才 release/free/close。提交失败、完成与 cancel 交错、设备拔出和未曾提交均须准确归账。只设用户 status callback 为 NULL 不停止内部传输；从 Java 额外调用 `libusb_close` 或提前关闭 fd 也不是替代方案。公共 API 未暴露该 transfer，不建议适配器依赖私有结构布局来强行补齐。优先上游修复或一份固定、可审查的小补丁；本轮没写 patch。

建议下一轮在隔离合成 native harness 中用可控 libusb 替身验证：status 持续在飞时 close；close 与一次成功回调重提交竞争；cancel 返回 NOT_FOUND 但完成仍排队；NO_DEVICE/submit-failure；延迟完成；两次关闭和失败回收。验证不重复 free、不在 active 时 free、不在 closing 后 submit，且所有者直到完成确认才释放 fd。之后才做授权范围内实机。这里是验证场景建议，不新增 PRD 测试编号。

**有界 stop / probe 尚未成立。** `uvc_stream_stop:1554–1594` 设置 running=0、逐一 cancel，条件变量一直等 transfer 数组清空，最后 join callback thread；无超时，要求事件线程继续运行且用户 callback 返回。源码 `1586` 仍有 camera-side stop TODO；stream close 另行 release interface/altsetting，不能把 stop-only 解释为物理源停止。`uvc_query_stream_ctrl:249–255` 的同步控制 transfer timeout 为 0；`uvc_probe_stream_ctrl:641–642` 没有传播 SET/GET 错误码，匹配参数不等于证明本次通信成功。须定义可中止/有界的控制请求、错误传播、真正关闭完成和晚返回的隔离策略；超时不能强行 free 仍在使用的资源。[stop](https://github.com/libuvc/libuvc/blob/448d78b9aa4f54172c1ca5b5d1b4a6b9b93f7957/src/stream.c#L1554)，[控制请求](https://github.com/libuvc/libuvc/blob/448d78b9aa4f54172c1ca5b5d1b4a6b9b93f7957/src/stream.c#L206)，[probe](https://github.com/libuvc/libuvc/blob/448d78b9aa4f54172c1ca5b5d1b4a6b9b93f7957/src/stream.c#L636)

**内存失败和预算还需补齐。** `init.c:106–110`、`device.c:291–293/341–343` 的 calloc 后直接使用；stream 的 frame/transfer 分配、`_uvc_populate_frame` realloc 与 callback `pthread_create` 也不能一概假定成功。生产路线须逐项核查失败清理、整数范围、设备声明尺寸上限与分配前预算，必要时小补丁；适配器在收到 frame 后检查已经来不及防止库内先分配。0.0.9 的 release 修复列表不是这部分的全面证明。

## 最小构建边界与依赖

| 部分 | 本轮静态声明 | 裁剪与待决项 |
| --- | --- | --- |
| libusb Android C | `core.c, descriptor.c, hotplug.c, io.c, sync.c, strerror.c, os/linux_usbfs.c, os/events_posix.c, os/threads_posix.c, os/linux_netlink.c`，10 个 C 文件 | 官方 `android/jni/libusb.mk` 已有 shared build，含 `-pthread/-llog`；顶层 Android.mk 还会带 examples/tests，最小包装只选库，不复制整套样例。无 root 不等于删除 hotplug/netlink 源就可链接 |
| libuvc C | `ctrl.c, ctrl-gen.c, device.c, diag.c, frame.c, init.c, stream.c, misc.c`，8 个基本 C 文件 | 以官方 CMake 最小目标构建，关闭 example/test/testing/fuzz；当前不进一步手删内部 C 文件。公开 header、internal header、utlist、生成 config.h 等仍必要 |
| JPEG | 可选第 9 个 C 文件 `src/frame-mjpeg.c`，链接 `JPEG::JPEG` | YUYV-only 研究分支可 `DISABLE_JPEG=ON`；若选定输入需要 MJPEG，则须固定 JPEG 实现、源和许可并验证 Android 解码，不能借“最小”静默改变输入时序/画质。`JCS_EXT_BGR` 亦需所选 JPEG 头/实现支持 |
| Android/JNI | 自写权限/设备事件、单会话所有权、控制线程/事件线程、复制/转换与停止完成桥 | 不需要把 UVCAndroid Java UI、CameraHelper、录像、RapidJSON、Hutool、整个 AndroidX 传递图带进这条纯 C 驱动路线；这是一条新适配器路线，不能把现有 UVCAndroid JNI 原样链接后视为完成 |
| 像素转换 | 可用内建三通道 RGB 或另选固定官方 libyuv | 颜色/步长/alpha/旋转和内存合同由集成方决定；不是 USB 驱动。JPEG、libyuv、Android SDK/NDK 本轮未新增完整锁 |

声明来源：[libusb Android.mk 输入](https://github.com/libusb/libusb/blob/87a55632db62c9bdc58cd31d3ccfa673f1bb017f/android/jni/libusb.mk#L27)，[libuvc CMake](https://github.com/libuvc/libuvc/blob/448d78b9aa4f54172c1ca5b5d1b4a6b9b93f7957/CMakeLists.txt#L24)。`FindLibUSB.cmake` 允许已有 `LibUSB::LibUSB` 目标，或依赖 pkg-config 搜索；需要隔离 Android sysroot/imported target，避免误链接主机库；它不固定库版本。官方 libusb NDK make 的默认输出名是 `libusb1.0`，`USE_PC_NAME=1` 才改为 `usb-1.0`，集成时须验证 SONAME/导入名，不能混用旧 fork 的加载约定。[FindLibUSB](https://github.com/libuvc/libuvc/blob/448d78b9aa4f54172c1ca5b5d1b4a6b9b93f7957/cmake/FindLibUSB.cmake#L16)

官方 libuvc Android workflow 声明 Android API 21、armeabi-v7a/arm64-v8a/x86_64、debug on/off，用 libusb 1.0.30、禁用 udev，且 `DISABLE_JPEG=ON`；它存在可供复核的构建配方。**本轮未查询 job 成功结果，也未实际重建**，故不能声称已构建通过、JPEG 已可用、16 KiB ELF/APK 对齐已合格或某设备可采集。实际工具链/ABI/链接选项仍需固定并产生可检查制品。[固定 Android workflow](https://github.com/libuvc/libuvc/blob/448d78b9aa4f54172c1ca5b5d1b4a6b9b93f7957/.github/workflows/android.yml#L24)

## 实际许可文本及剩余范围

| 部分 | 已核实文本 | 不能省略的后续范围 |
| --- | --- | --- |
| libusb | 根 COPYING 为 LGPL v2.1；上述 10 个 Android C 输入的文件头都明确 v2.1 或更新版本，记录为 LGPL-2.1-or-later；Android 构建配置同样有此头 | 最终完整编译头/平台辅助源、构建及分发方式对应材料仍须核查；使用 shared 库不自动完成义务，不能据根 LICENSE 宣称产品许可已完成 |
| libuvc | 根 LICENSE.txt 及 7 个基本 C + MJPEG 文件头为三条款 BSD 文本，版权所有人/年份分别保留 | `ctrl-gen.c` 是生成文件，开头没有独立许可头；本轮仅记录根许可与来源，未追溯生成器全部输入。不能把自动头扫描 UNCLASSIFIED 改成已全文件审计 |
| utlist | 随库 include/utlist.h 自报 2.0.2；Troy D. Hanson 2007–2017，文本只有保留源分发通知这一条，按 BSD-1-Clause 文本单列 | 根 libuvc 的 BSD-3-Clause 不覆盖为统一三条款；未另追 uthash 发布 tag 与整个外部项目 |
| 局部材料 | `stream.c` 的 Windows gettimeofday 分支标 public domain；`frame-mjpeg.c` 标明默认 Huffman 表来自 ISO/IEC 10918-1 K.3.3 | 前者不是 Android 编译分支；后者不应被头部摘要抹去来源，最终来源/通知清单需保留。JPEG 实现及新增转换依赖另审 |

原文固定来源：[libusb COPYING](https://github.com/libusb/libusb/blob/87a55632db62c9bdc58cd31d3ccfa673f1bb017f/COPYING)，[libusb core 文件头](https://github.com/libusb/libusb/blob/87a55632db62c9bdc58cd31d3ccfa673f1bb017f/libusb/core.c#L1)，[libuvc LICENSE](https://github.com/libuvc/libuvc/blob/448d78b9aa4f54172c1ca5b5d1b4a6b9b93f7957/LICENSE.txt)，[utlist 原文](https://github.com/libuvc/libuvc/blob/448d78b9aa4f54172c1ca5b5d1b4a6b9b93f7957/include/utlist.h#L1)。本轮是许可事实和覆盖边界清单，不是全制品审计完成结论。

## 内存、吞吐与冻结合同

libuvc Android 默认 **100** 个 transfer buffer，每个 iso transfer 最多 **32** packets，可通过 0.0.9 的 CMake 参数调整；数字是固定源码默认值，不是设备建议值。若 endpoint 每 packet 3072 B 且取满 32 packets，transfer 数据缓冲算术上是 `100 × 32 × 3072 = 9,830,400 B`，尚不含 URB/描述符。另有两份 `dwMaxVideoFrameSize` frame 组装缓冲、callback frame 拷贝、metadata、解码/转换、JNI/Java 及 Surface。减小 packets/transfer 可能提高完成频率，不能把降低内存视为无成本。[默认及说明](https://github.com/libuvc/libuvc/blob/448d78b9aa4f54172c1ca5b5d1b4a6b9b93f7957/include/libuvc/libuvc_internal.h#L219)，[缓冲分配](https://github.com/libuvc/libuvc/blob/448d78b9aa4f54172c1ca5b5d1b4a6b9b93f7957/src/stream.c#L1089)

1080p 单帧 RGBA 为 8,294,400 B，YUYV 为 4,147,200 B；一次 1080p30 RGBA CPU copy 的理想读+写流量是 497,664,000 B/s，均为公式算术而非实测吞吐。候选处理 1–5 fps 不会自动降低 USB 输入 fps、传输或压缩解码成本；是否能在解码前丢帧要按格式及预览需要另定。不得为使当前 fixture 通过而暗中缩图。

当前 `UvcDriver` / `FramePipeline` 是合成边界：固定 640×360 RGBA、921600 字节、owned 7 MiB，不能作为实机分辨率/整条 native 链预算。新增驱动必须等待主集成另定“协商总线模式”和“最终像素布局”的分离合同、预算与队列所有权，不能自行扩大公共字段。官方驱动也不实现 EDID/所选主屏保真或 PRD 的变化暂停/人工核屏；维持音视频独立授权、停止屏障和遮盖，G0 静态/模拟证据不替代 G1/G2。

## 路线判定与本轮验证

可选择继续 **官方库 + 小型适配器 + 可审查的必要修复**，以减少旧 fork 和 Java 依赖的来源负担。后续研究需选定真实需要的输入格式和精确模式，定义全部缓冲/颜色/时钟合同，固定 Android 构建及可选 JPEG 来源；通过隔离构建和故障注入关闭 status transfer 生命周期阻断，并验证 probe/stop/失败清理满足主集成的完成语义。上述阻断关闭后才可真实接入，并在已授权范围内开展设备验证。本轮仍未执行 native。没有证据支持“官方库无需任何补丁即可现成替换”，也没有证据要求为 fd 导入继续保留旧 native fork。

本轮只运行自写 Python 校验器：49 项输入离线重放通过；11 项完整性检查通过（其中 10 项拒绝路径覆盖缺输入、篡改字节、payload+receipt 同改、缺报告、报告篡改、拒绝覆盖、路径越界、截断 tree、release 身份、blob 身份）。这些检查不模拟 USB/native 运行；上文建议的 status/stop 合成场景尚未执行。重放命令、网络重建限制见 [README.md](README.md)。未改生产/构建配置，未提交 Git。
