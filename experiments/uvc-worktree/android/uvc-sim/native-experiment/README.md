# 隔离 CaptureSession adapter：纯 Java 合成验证

本目录提供调用主线公共 `CaptureSession` 契约的原创 Java adapter 外壳，以及受控后端、线程屏障和独立资源账本。它把借用 FD、提交/回调/调用栈、停止清理与 owned RGBA 交接接到实际公共类型上；没有 JNI、USB 驱动、解码器或 Android 运行时接入。真实采集、实体设备和原生分发门禁保持关闭。

## 固定边界

- 独立工作区基线：`e02b3fd93da49d4e1a6f76c3e3dd0f7d99c325c5`；所有增量都在 `android/uvc-sim/native-experiment/`。
- 主线交付模块当时为未跟踪文件，不能用主线 HEAD 代表其内容。[manifest.json](contract-baseline/manifest.json) 记录 9 份逐字快照的来源路径、长度和 SHA-256；其中 3 份 Java 是本实验实际编译的公共类型。原 README/验证记录及其元数据原样保留。
- 契约快照不构成第二套接口定义。合并时主集成应比对散列；若公共类型改变，先审查差异再更新快照和验证。实际产品依赖主线模块，不应同时打包快照中同名类。
- 冻结 PRD v1.5 的 Library version 0 是需求依据；本目录不重建 PRD，不新增产品能力、状态分支或 PRD 测试编号。本地测试名称只是实验定位符。

## 实现

[IsolatedCaptureSession.java](src/local/sidecar/copilot/uvcadapter/IsolatedCaptureSession.java) 实现实际 `CaptureSession`。构造只建立账本；`open/start` 在进入后端前登记引用，按完整 binding/operation/cycle 路由义务。FD `0` 作为有效借用值传递，adapter 不取得平台 owner 的关闭权。身份匹配只能检查数据，不能替代 Android 权限或活跃借用登记。

[BackendPort.java](src/local/sidecar/copilot/uvcadapter/BackendPort.java) 是本地实验 SPI。受控后端在分配源缓冲前通过 session 预留预算，在终态回调中取得/关闭 callback lease。image、status 和 control 的传输义务独立于正在执行的 submit/cancel/control 调用。取消返回、外层超时和停止请求都不能代替回调退出或调用退栈；提交抛异常、接受状态未知时仍保留资源义务。

`requestStop` 同步关闭入口并请求异步清理，不在调用栈上运行后端释放或 join。注入的 executor 必须可信、非阻塞且延后执行；inline/reject 会被识别为协议失败并保持未完成，任意会阻塞的自定义 `execute()` 无法由此接口强制改成非阻塞。清理依次等待传输与回调、释放传输、关闭设备、退出/汇合事件泵、释放 context。释放异常保留未决义务，不自动重试可能已经释放过的资源。证据修订仅随所表达的资源状态变化，终态固定。

[OwnedFrameBridge.java](src/local/sidecar/copilot/uvcadapter/OwnedFrameBridge.java) 只复制合成的紧密排列 RGBA。单个非阻塞交付槽限制并发，使用公共 `OwnedRgba.allocate` 在分配前预留预算，私有失败路径清零并归还。进入 `FrameSink.accept` 即移交所有权，sink 抛异常也不能由 sender 关闭帧。native 账本不把 sink 持有的帧算成自己的 bytes；实际 `StopBarrier` 仍需独立的 owner 和 application buffer 证据。

复制/交付不持有 session 或 bridge 账本锁。已登记的帧可能在停止竞态中迟到进入 sink；主线的 publication gate 必须拒绝并关闭它。此处不执行遮盖、预览、哈希、存储、采样或留存，也不能替主线证明这些路径已经清空。640×360 RGBA 是公共 owned-frame 夹具，不代表已实现原始 UVC 格式转换、原画质或任何设备兼容性。

预算覆盖显式登记的后端 payload 与 owned 帧，并回滚部分进入后端前的登记失败；不包含 JVM 对象/线程/Map 的全部开销，也没有证明整个 VM 内存耗尽时所有失败记录和证据对象仍可分配。合成的 `stopPumpAndJoin()` 只模拟调用与返回条件，未创建或验证真实 native 事件泵。

## 复现

在仓库根目录，使用已安装的 Python 3.10+ 与 JDK 17：

```powershell
python -B android/uvc-sim/native-experiment/run_checks.py --jdk 'E:\tools\jdk-17.0.19+10'
```

runner 首先验证契约与继承的来源/许可证据散列，再使用 `javac --release 8 -Xlint:all -Werror` 编译明确列出的源文件。它禁用注解处理和隐式编译，使用空的编译 class/source path，并从子进程环境移除 Java 注入选项。输出只写入忽略的 `build/run-*/`，不会安装依赖或运行其他工作线。

本轮结果见 [VALIDATION.md](VALIDATION.md) 和 [validation.json](validation.json)。后者逐字保留最终一次 runner 的结果、源文件散列、命令、退出码和 stdout/stderr。历史主线 Java 352 项和 C++ 实验仅作为带出处的交付记录继承；不计为本轮运行。

## 合并与后续入口

[CONTRACT_MAPPING.md](CONTRACT_MAPPING.md) 给出公共契约到实现与测试的对应；[GAPS_AND_LICENSE.md](GAPS_AND_LICENSE.md) 给出未实现接口、源码许可边界和实机前提。

本目录可作为受控后端适配及回归实验单独合并。不要将它接入 Android 产品路径或作为已完成真实驱动的证明。下一阶段至少需要主线实际 permission/owner/publication/buffer 接线，经过补丁验证的 libusb/libuvc 后端及 JNI 所有权桥，固定 Android 构建与许可分发闭包，以及按冻结 PRD 执行的独立设备验证。当前没有新增第三方运行时依赖。
