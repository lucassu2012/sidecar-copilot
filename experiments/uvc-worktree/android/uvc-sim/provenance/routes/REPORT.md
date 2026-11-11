# 冻结 PRD 所需真实 UVC 最小链路：来源、能力与实施结论

**结论：通用 Android USB 接入优先研究“自有 Android/JNI adapter + 官方 libusb + 官方 libuvc”；它已有授权 fd 的入口，但尚不能原样准入。** 静态审查发现 libuvc 的 status transfer 关闭排空和无超时 probe/commit 需要先解决；现有 Sidecar 模拟接口还缺少 wire/output 格式分离与跨层停止合同。若选定手机已经通过系统 Camera2 暴露这张采集卡，平台路线可进一步减少应用自带 native 依赖，但该前提必须以后用具体设备证实。libyuv 仅做像素转换，不替代任何 UVC 驱动层。

本次只增加研究文档、固定文本输入清单及证据复放脚本；没有下载运行时二进制、引入依赖、编译/加载 native、打开 USB/摄像头/录音或操作实体设备。**真实采集、原生接入和分发门禁继续关闭。**

## 基线与证据范围

- 隔离分支：`codex/uvc-minimal-source-route`，起点 `bbd053664bebb06ce83539a3f95e6fa5758745b7`。
- 变更仅在 `android/uvc-sim/provenance/routes/`；下载缓存仅在 ignored `android/uvc-sim/build/routes/`。
- 冻结需求来源：Library 文件 `PRIVATE_ARTIFACT_REFERENCE_OMITTED`，`Sidecar_Copilot_统一产品需求文档_v1.5.md`，**version 0，1021 行**。前轮已通过 Library read 全文读取，本轮沿用同一版本；没有手工重建无元数据副本。
- 保留原单物理输出/显示链路、显式选择确认、断连与格式变化处理、隐私遮盖、原清晰帧和停止要求；没有新增功能、产品状态或 PRD 验收编号。源码支持某格式不等于其满足显示时序、文字可读性或既有 G1/G2/G3 条件。
- 所有“维护”结论均是 **2026-11-10 的有界观察**；日期、release 和仓库未归档都不能证明质量、兼容或持续维护承诺。

## 能力比较

| 路线 | 发现与权限 | USB 传输、UVC 协商 | 帧格式与交付 | UAC 音频及主要缺口 |
| --- | --- | --- | --- | --- |
| **Android Camera2 外接相机** | 系统 cameraId / CAMERA；应用通常不直接取得 USB fd | OEM kernel UVC、external provider、HAL/config/SELinux 承担底层；应用不能仅靠 SDK 添加缺失 provider | 固定 Android 15 参考 HAL 接受 MJPEG/Z16，输出 ImageReader 等平台格式；具体 OEM 可能不同，不能假定 YUYV 卡必可见 | 视频能力不含 UAC；单独走系统音频。最小应用依赖，但必须先证明选定系统真实暴露且满足原显示/生命周期合同 |
| **Android UsbManager / UsbDeviceConnection / UsbRequest** | 枚举、attach/detach、USB 逐设备授权；目标 P+ 的 USB 视频访问还需 CAMERA；需绑定连接代次 | control、bulk、interrupt；所核 Java/JNI/libusbhost 路径拒绝 iso。没有现成 UVC probe/commit、帧重组或解码 | 原始端点数据，不是可直接交付的图像；实现 bulk-only UVC 需自写协议、重组、设备差异和停止 | 非 UAC engine；不建议作为通用 UVC 从零实现路线。限制只适用于该平台 API 路径，不代表 Android 内核或独立 native 禁止 iso |
| **官方 libusb 1.0.30 + libuvc 0.0.9 + 自有 adapter** | 自有平台发现/授权；NO_DEVICE_DISCOVERY + uvc_wrap 接入授权 fd | libusb 处理 control/bulk/interrupt/iso；libuvc 描述符、probe/commit 与 stream 路径。外部 context 要自管事件泵 | YUYV/UYVY、MJPEG、部分压缩格式标识需区分传输、转换和解码；不含通用 H.264 decoder。借用回调必须复制；RGBA 输出与像素合同由 adapter 明确 | 不提供 UAC。来源较容易固定，但 status 取消/排空、无界同步请求、错误传播、实际构建和设备均未闭合 |
| **UVCAndroid** | USBMonitor 和 Android 权限连接封装 | Android fork 的 libusb/libuvc、JNI 和预览链路 | 源码有 MJPEG/YUYV 转 RGBX 与可复用 DirectByteBuffer；不能跨回调持有 | 所检音频路径是系统 AudioRecord 默认/MIC/CAMCORDER，不能证明选中 USB 输入；最近相关修改可见，但既有 libyuv 11 文件修改来源、原生/传递依赖门禁未关闭 |
| **saki UVCCamera** | 早期 USBMonitor 和授权 fd 扩展 | 较老 Android fork，历史源头可追溯 | MJPEG→YUYV/RGBX 等源码路径，旧 JPEG 1.5.0 名称 | 所检音频同为系统来源；默认 HEAD 2018，不能仅以现代 API 修补替代全依赖/生命周期验证 |
| **AndroidUSBCamera** | USBMonitor + Kotlin 封装、授权 fd | Android fork libusb/libuvc 和 native bridge | 预览/格式选择代码；回调内存同样借用 | 有 UAC wrapper，但四 ABI UAC 二进制的对应源码、构建与授权链仍未核清；SOURCE_AUTO 的默认麦克风回退不能照搬 |
| **官方 libyuv / 可选 libjpeg-turbo** | 无 | 无 USB 权限、传输、UVC/UAC 协议 | 前者转换像素；后者解码 JPEG/MJPEG payload。二者不能发现或驱动采集卡 | 无 UAC。只有经过协商的 wire 格式需要时才选择，不能借转换库掩盖驱动缺口 |

平台能力、引用行和文件校验见 [platform 报告](platform/REPORT.md)；官方驱动的实际入口、控制流和许可证见 [upstream-driver 报告](upstream-driver/REPORT.md)；三种 wrapper 的逐层能力与维护证据见 [wrappers 报告](wrappers/README.md)。

## 固定候选与许可决策

| 候选 | 固定引用与维护观察 | 授权证据及本次决定 |
| --- | --- | --- |
| libusb | **1.0.30**，`87a55632db62c9bdc58cd31d3ccfa673f1bb017f`；官方 release 发布 2026-05-17 | [COPYING](https://github.com/libusb/libusb/blob/87a55632db62c9bdc58cd31d3ccfa673f1bb017f/COPYING) 与源文件 LGPL-2.1-or-later 声明可固定。后续需按实际链接/分发方式准备对应源码、修改、许可通知及用户替换/重新链接安排；选动态库也不是自动免责。这里只进入源码研究候选 |
| libuvc | **0.0.9**，`448d78b9aa4f54172c1ca5b5d1b4a6b9b93f7957`；官方 release 发布 2026-10-29 | [LICENSE.txt](https://github.com/libuvc/libuvc/blob/448d78b9aa4f54172c1ca5b5d1b4a6b9b93f7957/LICENSE.txt) 为 BSD-3-Clause；不覆盖 libusb/JPEG。保留声明与修改记录；先关闭下面的停止/协商缺口 |
| 可选 libjpeg-turbo | **3.2.1**，`7b4993786d856cbccabfc0ed2f923ba000e326cc`；官方 release 发布 2026-10-27 | [LICENSE.md](https://github.com/libjpeg-turbo/libjpeg-turbo/blob/7b4993786d856cbccabfc0ed2f923ba000e326cc/LICENSE.md) / README.ijg 与组件声明可核验。IJG/BSD/zlib 不能简化为单一许可证；默认 TurboJPEG/工具还带 libspng/zlib。候选只取 libjpeg API 的构建条件见 [解码器核对](mjpeg-decoder/README.md)，未构建或解码 |
| UVCAndroid | HEAD `fcdae5f9a194e2111d57019176b53470a120415b`，2026-06-24；release 1.0.13 指向 `6018c7f...`，2026-06-10 | 根 Apache-2.0 不覆盖所有 native 修改或预编译库。HEAD 仍是既有审查版本；Hutool 删除发生在 release 后，不能用 HEAD 为既有 AAR 清账。保留作参考，不引入 |
| saki UVCCamera | HEAD `c9399e63dfab4b6d260d8cbef92182abc6de0ee0`，2018-10-02；所查 release/tag 为空 | GitHub license 检测为空不等于无授权；README/所检 wrapper 文件有 Apache-2.0，native 各有条款。但全源码/修改/二进制映射未闭合，不因更早就更易准入 |
| AndroidUSBCamera | HEAD `426ed0a5da2c826197761cc5a89ba3ae9c52bd7c`，2024-09-02；release 3.3.3 是 2023-02-13 | 根 Apache-2.0 不能补全四 ABI UAC blob 的来源与授权。保持禁入；不能把 JNI 名称匹配或后来同名实现当作原二进制许可 |

Android 平台路线调用已安装系统 API，可免去应用打包上述第三方 native 组件；**复制 AOSP HAL/内核到应用或定制系统是另一条构建与许可范围**，不在冻结需求的最小应用路线内。AOSP 参考 HAL 内部使用 libyuv/JPEG，并不代表调用 Camera2 的应用在自身包里引入它们。

官方路线减少了旧 fork 的来源歧义，但不是已审核的 APK 依赖闭包。本次未产生 native SBOM、对应构建产物或最终通知包。实际 Android NDK 配置、系统运行库、可选 JPEG、动态/静态链接与所有修改须在后续构建计划中明确。

## 两项会阻止直接接入的静态发现

1. **control/status 生命周期不能由图像 stop 代替。** 固定 libuvc 源码自动提交 status interrupt transfer，回调可重新提交；所检 close 路径停止 image streams 后关闭设备并释放 status transfer，未见与之匹配的显式取消及完成等待。libusb 规定 active transfer 不应被释放，close 的移出记录不等于取消并排空。此为固定源码的控制流风险判断，**本轮没有 native 复现，没有宣称已证明 UAF 或某设备必崩溃**。接入前需要终止标志、禁止重提交、取消/完成确认、失败分支及 owner 生命周期证据。
2. **probe/commit 和错误传播未满足有界控制。** 所查同步 control_transfer 使用 timeout=0；部分 probe 路径忽略 SET/GET 返回值。不能用 Java worker 超时或 Future.cancel 声称 native 已停止，也不能超时后 free 仍在用的 handle。需局部设计有界请求/返回校验和不确定关闭处理，并与既有状态映射对齐。

详细函数、固定行号、证据强度及后续注入方案都在 [官方驱动报告](upstream-driver/REPORT.md)。这两项阻止“官方库原样替换旧 AAR 后立即采集”，不阻止本轮完成来源比较或后续自有模拟工作。

## 最小可实施顺序

1. **主集成先统一合同。** 将授权连接/唯一 fd owner、attachment epoch、wire 协商格式与 sink 像素格式、单调时钟/序号、总内存预算及停止完成范围写清；当前 synthetic UvcDriver/640×360 pipeline 不能未经决定直接接真实 1080p。公共接口仍由主集成修改。
2. **先做自己的隔离 adapter + fake backend。** 复用既有 CaptureGate 和顺序发布；将新授权连接、取消中的 open、迟到回调、借用帧复制、错误传播、断连和重复 stop 转成确定交错。路径与责任见 [adapter 计划](adapter-plan/README.md)，本次未创建生产 source set。
3. **在隔离源码构建与可控替身实验中验证并关闭生命周期缺口，关闭后才进入真实接入。** 采用授权 fd → 独立 libusb context → uvc_wrap 的单 owner 路径，外部事件泵持续服务直到 status/image/callback 全排空；最后依次释放 context 和授权 fd owner。不要为同一 fd 双重 wrap、双重 close；授权过期和断连先撤销发布权。错误结果不确定时保持既有停止未确认行为。本轮未执行这些 native 实验。
4. **只为所选 wire 格式加入必要解码。** YUYV 与 MJPEG 各自验证布局/颜色/长度/损坏帧和小字标记；如用 JPEG，固定 libjpeg API 目标与全部构建条件。不要为省依赖强制 YUYV，也不要让 RGBX 未定义 alpha、RGB565 量化或降采样改变明细证据。像素复制前先计入额度，遮盖在预览、哈希、存储和衍生物之前。
5. **最后才是具体组合的受控验证与准入。** 手机系统、卡固件、供电 Hub、HDMI 分配/回环、显示器、线材、格式/刷新率、恢复与停止分别留证。当前只完成静态研究/证据检查，实际 USB、JNI、解码、性能、ABI 和设备验证均未执行，不满足 G1/G2/G3。

若确认 Camera2 已暴露合适 source，可走同样的 owned 图像、来源/格式检查、断连和停止合同，以系统 CameraDevice/ImageReader 替代应用 libusb/libuvc；需在借用期内复制，或等访问 Image 的任务退出后关闭相应 Image，排空应用 worker 后关闭 reader，不能以 onClosed 等同应用缓冲清零。若两条路线均无法证明冻结显示/停止条件，**维持桌面视频功能关闭**，不以自动降级、隐式重新连接或新增产品状态掩盖缺口。

## UAC 与会议音频必须单独闭合

视频成功不证明音频。候选优先核验系统 USB audio + AudioRecord 的实际输入路由；设置 preferred device 仅是偏好，不是实际 routed device 保证。USB 断开可能触发系统路由变化，回调与 PCM 边界也不是原子操作；不能默默回退默认麦克风，未知仍为 unknown，受影响记录按冻结要求暂停/处理。应用层逻辑无法仅靠一次路由检查证明从未出现过切换窗口。

分别证明 A（远端到耳朵）、B（本地麦到电脑）、C（远端到 Android 记录）、D（本地麦到 Android 记录）。只录到 C 就不能声称双向纪要；本地手机麦不证明 B。音量非零、文件增长、AudioRecord.read 返回正数都不证明健康或来源正确。视频中断也不能任意改成所有音频自动停止。

旧 AndroidUSBCamera UAC 四 ABI 二进制继续禁入。前轮记录的独立 libuac 源码候选有单独授权材料，但不是该旧二进制的补授权，也没有完成本系统集成验证；本轮不为视频路线自动添加任何 native UAC。依据见 [platform 报告](platform/REPORT.md)、[既有 UAC 追溯](../followup/uac/REPORT.md) 与 [冻结音频判据](../feasibility/audio-gap/REPORT.md)。

## 可复现交接与合并建议

逐项输入锁、报告与复放命令放在各子目录；汇总离线检查运行：

```powershell
python -B android/uvc-sim/provenance/routes/validate_routes.py
```

完整性检查验证的是固定文本、元数据观察、已有报告和错误拒绝路径；不是 UVC 协议实现、native 解码或实机兼容测试。本轮实测记录见 [validation.json](validation.json)。上游源只作为 UTF-8 文本读取，未执行下载的代码；自己的 Python 检查代码在本机执行。

本分支适合以独立的研究提交合并，文件边界仅 `routes/`，不依赖修改主线公共接口，也不带二进制。由于其起点包含前轮研究提交，主集成若尚未接受前置证据，应先确认所引用的既有 provenance/feasibility 文件已在目标分支，不能只复制摘要后丢失来源链。合并此文档不改变任何真实采集、原生或分发门禁。
