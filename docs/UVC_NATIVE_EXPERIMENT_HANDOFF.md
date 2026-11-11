# UVC 下一工作线：隔离原生生命周期实验

2026-11-10 更新：公共接口已新增到 [android/uvc-contract](../android/uvc-contract/README.md)，以其中的 `UvcContract`、`CaptureSession`、`StopBarrier` 为当前实验合同和文件归属。该模块不接 APK；F134 的 73 输入及五包维持独立冻结，留存线设备窗口不被占用。下面保留 ED140 时点交接作为历史，不能将旧阶段状态或静态检查计入新实现验证。

本交接在 ED140E29 隐私候选完成构建、宿主回归和归档核对后给出。该候选及其 72 项冻结输入不变。本交接落实冻结 v1.5 的既有采集与停止要求，不新增产品功能、状态或验收编号；以下类型和约束仅是下一隔离实验的合同，尚未接入生产代码。

已只读核对独立工作树 `task-4/sidecar-uvc-interface` 的提交 `e02b3fd93da49d4e1a6f76c3e3dd0f7d99c325c5`，工作树干净。其 `android/uvc-sim/interface-contract/README.md`、`NATIVE_CONTRACT.md`、`ANDROID_CONTRACT.md`、`LICENSE_BOUNDARY.md` 和 `DEVICE_PREREQUISITES.md` 是本次交接的来源。28 项结果是契约与来源静态核对；旧异步探索达到 4,000 状态上限，不能据此证明原生关闭、线程安全或实机安全。

## 工作范围与文件归属

UVC 工作线可以继续在自己的隔离目录 `android/uvc-sim/native-experiment/` 编写 C 对象所有权核心、可控 backend 及宿主原生实验，先核实已有编译器与检测工具能力并记录版本。缺失工具或不能执行某条路径时明确报未运行，不以重复旧 Python 模型替代原生运行。此处没有授权购买、安装系统级驱动、使用真实 USB 设备或发布依赖产物。

主集成持有公共类型、产品错误映射、跨采集源仲裁、应用缓冲与原生资源的组合停止确认，以及最终 APK 接线。UVC 工作线不得自行修改主线 `UvcDriver`、`Format`、`StopReceipt`、服务、数据库、清单、主构建脚本或已冻结输入；接口不适配时提交具体差异，由主集成处理。现有 `UvcDriver` 明确为合成驱动，不能直接装入真实 USB 实现。当前 `StopReceipt.confirmed` 只涵盖驱动关闭和协调任务归零，不包含 FramePipeline 的全部缓冲清理；`awaitWorkerIdle` 也不能代替完整关闭。

## 固定的实验接口合同

| 边界 | 本轮决定 |
| --- | --- |
| 身份 | 使用不可变 session、selection token、attachment epoch、permission revision 和 connection owner token。权限请求 nonce 在调用前登记且最多消费一次。FD、设备路径、VID/PID 不能代替会话身份。 |
| FD | Java 平台 connection 是唯一 owner，native 只借用；FD 0 合法，负值拒绝。进入 closing 后不再借出；尚未返回的 native 调用、回调、事件泵都退出后才能关闭平台 owner。 |
| 调用 | operation 控制块和 submission 在 native 调用前登记。分别计数调用返回、terminal 与 callback 退出，允许 callback 先于 submit 返回；BUSY 不清除旧 submission，NOT_FOUND 不证明排空。上层等待超时不释放仍在执行栈上的引用。 |
| 帧 | 独立定义 wire descriptor 与 owned RGBA sink，不改变当前 Format 的含义。隔离阶段固定 640×360 紧密 RGBA、独占数组；不引入缩放、JPEG、libyuv 或真实格式选择。携带 boot、generation、sequence、host monotonic time；开始确认前及旧代帧拒绝并回收，不补造时间来接纳旧帧。 |
| 停止 | 先同步撤销发布资格，再分别报告 native obligations、pump/context/FD 和应用 owned buffers 的完成与错误。所有部分完成才能生成产品组合停止确认；安全回收完成也不得抹去原失败。沿用既有未确认/故障含义，不增加 PRD 状态。 |
| 错误 | 权限映射 PERMISSION_REVOKED，断连 SOURCE_LOST，格式改变 FORMAT_CHANGED，资源不足 RESOURCE_PRESSURE，协议/I/O/未完成超时 SOURCE_FAILED。不能因超时而伪造停止成功。 |
| 内存 | 原生隔离实验设显式 8 MiB 图像/传输字节额度，计入实际 transfer、源副本、转换/JNI、队列和预览；分配前预留，唯一归还，报告元数据/运行时额外内存。此参数只约束实验，不是生产总内存或硬件验收预算。 |

## 实验顺序与证据

1. 用真实线程和可控 backend 执行 callback-before-submit-return、stop 插入 open/start、terminal-before-cancel-return，分别注入 BUSY、NOT_FOUND、错误及迟到结果；验证对象实际存活、引用和唯一释放。
2. 分别排空 status、image、control，验证 status resubmit 与停止竞争、同步 control 超时后仍在栈上及之后真正返回。永久缺少完成时保留未确认，不能强行 free 或把等待结束解释为调用结束。
3. 对固定 libuvc 的 status teardown 路径给出具体负例和最小补丁。不能用 stream join 代替 status/control 排空；业务调用和回调完成后，才停止并 join 事件泵，最后释放 context 与 FD。
4. 用真实数组/缓冲和分配失败覆盖 callback 后源缓冲复用、交付异常、队列满、停止时在处理帧。JNI pending exception 仅在实际 JNI 路径可运行时记通过；宿主替身不能代替它。验证实际字节和引用，不只检查日志中的清零标志。
5. 每个结果绑定源码、补丁、编译器、ABI、链接清单、命令及产物哈希；保留失败、超时和不支持项。源码源集以已有官方固定输入及其精确哈希为准；先完成自有 C 核心与可控 backend，再提出 libusb/libuvc 实验源集供主集成核对，不使用旧 UAC AAR 或未审计的二进制替代。

官方版本线索、许可证文字和静态来源核对不等于最终二进制合规审查。实际采用任何源文件/依赖前，列出完整源集、生成文件、构建开关、静态/动态链接方式、修改、通知和适用的可替换/重新链接材料；可选 JPEG/SIMD 等依赖默认不引入。APK 原生依赖和分发门禁仍关闭。

## 仍缺少的实机前提

现有证据不足以指定手机型号、OEM build、采集卡/固件、Hub、电源、线缆和 HDMI 模式组合。待实际清单与可用设备明确后，才单独安排相应验证。宿主实验不能证明 USB 授权路由、真实 FD 生命周期、16 KiB 页加载、停止延迟、显示不变性、声音链路或持续温升/功耗。

ED140E29 冻结时按当时协调信息为留存线程保留了独占窗口。随后收到该线完成 873AA 回归并释放 5594 的报告；这不自动分配下一窗口给 UVC。本交接不启动模拟器、不开启真实采集，也不改变 ED140E29 的 Android 未运行记录。
