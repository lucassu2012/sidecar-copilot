# Adapter shell 的实现缺口与来源边界

工作区基线 `e02b3fd93da49d4e1a6f76c3e3dd0f7d99c325c5`。本轮范围是原创 Java adapter shell、受控 synthetic Backend SPI 及公共合同适配。目录名 `native-experiment` 不表示 JNI、libusb/libuvc 或 Android USB 已接入；本轮不下载原生二进制、不执行 native 或旧模型、不安装依赖、不接 APK、不操作设备。实际职责映射见 [CONTRACT_MAPPING.md](CONTRACT_MAPPING.md)。

公共合同以 [contract-baseline/manifest.json](contract-baseline/manifest.json) 中的快照哈希为准，而不是主线 HEAD：采集时该主线模块尚为 untracked。9 项快照含 3 个 Java 合同、README/VALIDATION 和历史结果材料；原文链接仍保留原模块语境。本轮未重跑这些历史 Java/C++ 检查，也不把它们计作 adapter 的新结果。

## 本轮能够落实的范围

Java shell 可以实现并检查不可变 Binding/OperationKey、native 义务账本、canonical stop、受控后端回调交错、NativeEvidence 的原子版本与终态，以及借用帧到自有 RGBA 的交接。这里“native”是被计数的合同类别；其可靠性依赖 Backend 如实登记并兑现事件。Synthetic Backend 的 open/submit/cancel/pump/context 不能证明对应 OS 或上游调用已发生。

外层仍须实现 permission nonce 注册/消费、真实 Android 授权与 attachment epoch、FD owner/borrow、开始确认和发布门禁、遮盖与应用队列/worker/preview，以及 OwnerEvidence/BufferEvidence 的可信生产者。构造公共值对象只能检查字段形状；不授予系统权限、不创建真实 FD，也不验证报告者诚实。`StopBarrier.drained` 不能由 adapter 单方面产生。

## 真实驱动仍未关闭的缺口

| 层次 | 已有固定依据 | 后续必要工作；本轮不能代替的部分 |
|---|---|---|
| libuvc status teardown | 固定 `device.c` 在 open 提交 status_xfer，回调可重提；close 路径未建立显式 cancel + terminal/callback 排空，后续直接 free。 | 在精确上游源上实现并审核 closing 锁存、禁止重提、取消/回调退出/调用栈退出后的唯一释放及失败清理。Java shell 统计三类义务，不能改变原版 libuvc 私有 transfer 的释放位置。 |
| submit/cancel 与调用栈 | 固定 libusb 允许完成回调先于 submit 返回；cancel 的 NOT_FOUND 不等于 callback 已退出。 | 为真实 API 调用和 operation 控制块先登记引用，协调 submission cycle/BUSY、terminal、callback、cancel 返回与 transfer 所有权。选择保守延迟释放时还须协调自动 free 标志；不能把外部计数器当 native 指针寿命实现。 |
| control/open/start 与有界停止 | 同步 control 内部处理 events；timeout=0 无界。libuvc probe 未传播部分 SET/GET 返回值，stream stop 等待与 join 无界。 | 补实际失败传播、超时/取消实现及真实调用退出证据。外层期限只产生 TIMEOUT/停止意图；无返回/无完成时安全 pending，不等于已满足有界停止或允许新采集。迟到 open 资源留在原 owner 账本。 |
| event pump/context | supplied libusb_context 由调用方处理事件；interrupt、event API 返回和线程 join 不等价。 | 真实 transfer/业务栈排空后退出并 join 本方 pump，处理同步调用也参与 events 的情形，最后释放 context。Synthetic pump 回调或 Java worker 结束不是 native thread join 证据。 |
| JNI 与物理资源 | 公共合同只有 Java 类型；历史 C++ 宿主实验与 Java 合同是独立实现。 | 实际 JNI ABI、global/local refs、pending exception、native scratch、线程附着、分配失败、返回路径、真实 FD 借用和统一字节账本均未接通。不能把 Java 异常隔离替代 JNI exception 测试。 |
| 真实 wire 与像素 | WireFormat 与固定 640×360/stride 2560 的 OwnedRgba 已分离。 | 选择实际格式、解码器、stride/plane、颜色/方向与完整预算；不能暗中缩图、将 MJPEG 当 RGBA 或修改旧帧时间来过门禁。受控 tight RGBA 拷贝不证明真实转换，也不覆盖 PRD 的清晰原图与先遮盖后局部降采样链。 |
| 全停止 | NativeEvidence、OwnerEvidence、BufferEvidence 是三份独立证据。 | native 静止、平台 connection close 和所有应用 owned 缓冲清零分别兑现；进行中的 sink worker、preview 或迟到自有副本不能因 native 已排空而漏账。失败仍保留，安全回收不改成成功采集。 |

上述源码锚点继续使用 [NATIVE_CONTRACT.md](../interface-contract/NATIVE_CONTRACT.md)、[native-contract.json](../interface-contract/native-contract.json) 与 [ANDROID_CONTRACT.md](../interface-contract/ANDROID_CONTRACT.md)。它们是固定 libusb `87a55632db62c9bdc58cd31d3ccfa673f1bb017f`、libuvc `448d78b9aa4f54172c1ca5b5d1b4a6b9b93f7957` 和 API 35 参考源码的静态证据，不是目标 OEM 的行为保证；本轮不重复抓取或运行这些源。

Android 路线特别保留：hasPermission/permission callback/open 不构成一个原子授权事务；FD、deviceName、VID/PID 和 UsbDevice 引用不能替代绑定身份。getter 不提供 native borrow 引用计数。既有固定源码还观察到 JNI 与 usb_device_new 失败路径的两个 close 站点，未实测/未修补，不宣称框架所有失败分支已证唯一 close。普通调用 SDK 与复制/分发 AOSP JNI/libusbhost 实现也是不同的来源范围。

## 许可证据已有进展，构建和分发闭包仍缺

本轮自有 Java 实现及 synthetic Backend 不复制或运行第三方 native 实现；公共合同逐字快照的出处与哈希另有记录。这不等于未来 native 依赖或实际分发已经获准；同时也不能把已有根许可证的积极授权证据说成“无许可”。

| 来源 | 继承的确证 | 仍需绑定实际交付物 |
|---|---|---|
| libusb v1.0.30 | 根 COPYING；已核 Android 构建列表的 10 个 C 文件为 LGPL-2.1-or-later。 | 最终选用源/头/配置/修改、构建与链接清单、通知及所选择 LGPL 分发机制的对应材料。shared library 本身不是合规闭包证明；也不预设所有备选机制都必须同时采用。 |
| libuvc v0.0.9 | 根 BSD-3-Clause 是仓库级积极授权证据；基础源有独立作者/年份声明，不能合并覆盖。 | 全部实际纳入文件、生成产物、修改与传递依赖来源；保留适用版权和许可通知。静态抽样不是完整贡献历史审查或最终包清单。 |
| utlist.h | 固定树内自报 2.0.2，Troy D. Hanson 2007–2017，BSD-1-Clause。 | 独立保留该文件声明，不能由 libuvc 根 BSD3 替代；没有认定完整外部 uthash 发布快照。 |
| 可选 JPEG | `frame-mjpeg.c` 自身 BSD3 与 Huffman 表来源说明；另有 libjpeg-turbo 3.2.1 固定文本候选及 IJG/BSD3/SIMD zlib 分层证据。 | 本路线尚未绑定最终 JPEG::JPEG 实现、目标/选项、实际头/扩展接口及依赖；必要时包含 zlib/libspng 等选中部分。关闭 JPEG 分支不自动证明最终包排除了它，更不能暗改所需格式或画质。 |
| 旧 UAC/封装路线 | 四 ABI libUACAudio.so 对应源码/完整授权链仍未确证；相似源码、相同 JNI 或公开 fork 不是旧 blob 的授权链。 | 不引入未核清的 UAC blob、旧 AAR/Hutool 或其隐含依赖。去掉 UAC 不等于去掉视频仍需的 libusb/LGPL；此 shell 不提供音频采集。 |

精确输入和许可声明见 [LICENSE_BOUNDARY.md](../interface-contract/LICENSE_BOUNDARY.md)、[license-evidence.json](../interface-contract/license-evidence.json)、[JPEG 候选核查](../provenance/routes/mjpeg-decoder/README.md) 与 [UAC 追溯](../provenance/followup/uac/REPORT.md)。此处不新建通知包、不提供新的法律结论，也不以 README 代替实际构建/来源核对。

**生成来源不再写为未知。** e02b3fd 已定位固定 libuvc 同树 `src/ctrl-gen.py`、其中内嵌 GETTER/SETTER 模板以及 `standard-units.yaml` 输入候选：40 个控制名称与 80 个 get/set 函数的名称和顺序对应。已提交 C 的头注 `ctrl-gen.py def` 没带输入参数，不是完整重建 recipe；前轮没有执行生成器、比较函数体重生成字节、确认历史实际命令或 Python/PyYAML 版本，本轮也不执行。

固定 CMake 直接编译已提交 `src/ctrl-gen.c`，所以继续审查该 C **不以先运行生成器为条件**。如果后续声称“可复现生成”，再固定准确命令/输入顺序/工具版本并比较结果。生成器、YAML、C 缺独立 copyright/SPDX 头不否定同树根 BSD3，也不等于未授权；逐贡献历史仍未完整审核。详细匹配列表及有限扫描证据仍在上述 JSON。

## 后续交接必须保留的实际缺项

真实 native 构建前需要完整固定源码/头/配置/生成输入与补丁、工具链/NDK/ABI/选项、源码→对象→库→包关系、链接和装载闭包、通知及适用分发材料。当前没有本路线 Android native artifact、JNI ABI、16 KiB 页加载、TSan/LeakSanitizer、真实 USB 权限/FD/采集/恢复、热耗或长时结果；历史 MSVC/ASan 宿主结果不填这些空项。

设备阶段还必须绑定实际 OEM build、手机/卡/固件、Hub/供电/线缆/HDMI、模式与观察时长；静态源、模拟 PASS 和 frame read/file growth 均不能替代真实来源确认、音视频闭环或既有 G2/G3。完整前提继续引用 [DEVICE_PREREQUISITES.md](../interface-contract/DEVICE_PREREQUISITES.md)。这些是未完成实现与验证条件，不增加 PRD 状态、测试编号或本轮额外审批流程；没有发布、部署或远端推送；本目录只形成独立工作区交付。
