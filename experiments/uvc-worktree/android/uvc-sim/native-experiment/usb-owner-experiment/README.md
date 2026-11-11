# Android USB 权限与连接 owner：隔离接线实验

本目录在 `ff46084debcfef8b29420fdb786c5ddfc3e19981` 的实际 `CaptureSession` adapter 上新增权限和 FD owner 边界。实现是原创 Java，平台能力经替身端口注入；没有 Android SDK、BroadcastReceiver、PendingIntent、JNI 或真实 USB 调用。本轮没有新增第三方运行时依赖。

全部增量位于 `android/uvc-sim/native-experiment/usb-owner-experiment/`。父层公共类型、adapter、应用和构建文件保持基线内容。[inputs-lock.json](inputs-lock.json) 固定 18 份继承代码与证据的长度和 SHA-256；创建时主线 9 份公共交付文件仍逐字匹配父层快照。冻结 PRD v1.5 的范围不变，本地检查名称不构成 PRD 测试编号。

## 接线职责

`UsbConnectionOwner` 绑定一个不可变 `Binding` 和同一 binding 的可信 `CaptureSession`，只管理这一轮权限与连接生命周期。构造不请求权限或打开设备。`PlatformUsbPort` 表达需要后续 Android 桥兑现的请求授权、核对当前附件/权限、打开 connection、取得 FD 和关闭 connection 能力。

权限请求先登记 `PermissionRequest`，再进入平台调用。回调必须匹配仍有效的完整 selection 与 nonce，并且只能消费一次。已经发起请求时，必须等该调用成功退栈并收到当前 grant，才能执行 `open()`；系统已有权限不能绕过尚未完成的本轮请求。没有发起请求时可以显式使用系统现有权限，但同样需要重新核对。授权结果不会自动打开或启动采集。显式 worker 调用 `open()` 时复核当前附件与系统权限，打开后再次核对；期间失效或停止所产生的迟到 connection 仍由原 owner 清理。

`open()` 的完整操作引用保护 `getFileDescriptor()` 及前后检查；只有成功且仍有效时才缓存 FD。之后 `borrow()` 在 owner 锁内登记并返回内部生成的 `BorrowLease`，供 native 使用这个 FD。FD `0` 有效，负数拒绝；FD 的整数值不充当连接身份。只有本 owner 创建的实际 lease 能归还其借用，关闭 lease 不关闭原始 FD。native 排空和 borrow 归还是两个独立条件，任何一个缺失都不能关闭 connection。

拒权、拔线、权限撤销或显式生命周期失效关闭本 owner 的准入；旧回调不能重新打开准入。重试/重连需要外层明确选择并创建新的 binding/owner，不能沿用旧 grant 自动恢复。`invalidate` 是外层事件入口，不擅自把 Activity 的某个回调或后台运行映射为产品停止策略；这一映射仍由主线按冻结 PRD 决定。

## 停止与证据

主集成先撤销发布准入，再传入既定 canonical `StopKey`。owner 同步关闭自身准入，在锁外调用可信且非阻塞的 `CaptureSession.requestStop`；平台 connection 的关闭交给非阻塞、延后执行的 executor。平台调用、native producer 调用、failure sink 和 connection.close 都不在 owner 账本锁内执行。

owner 的 stop 方法返回 `void`，不是停止成功回执；若 session 停止调用抛异常，owner 会保留错误并禁止用 native 证据推进关闭。只有可信 session 的停止调用正常返回，才记录其已关闭准入；最终完成仍以组合证据为准。

`refreshNativeDrain(key)` 从构造时绑定的 session 获取证据；任意调用者提供一组计数不能替代它。错误 key、修订冲突或终态变化不能推动关闭。`ownerEvidence(key)` 只返回本地缓存，不调用设备。仍在栈上的平台操作、仍持有的 lease 和尚未成立的 native drain 都阻止 connection.close；close 返回前 owner 不能报告关闭成功，close 抛异常则保持未知且不自动重复关闭。

开始 close 时，owner 在同一锁区确认无在途证据读取并封住新读取，使用最后接受的 native 终态作为关闭依据。这依赖可信 producer 遵守终态不再变化的契约，不能检测其在封口后非法恢复工作。未得到明确 connection 的 open 异常同样保留未知义务，不根据猜测关闭一个裸 FD。

owner 不生成应用 `BufferEvidence`。即使 native 与 connection 已关闭，应用仍持有 `OwnedRgba` 时，实际 `StopBarrier` 必须继续等待应用独立清空。错误仍作为错误保留，安全清理不改写为采集成功。

平台失效通知没有权力创造 canonical StopKey。主集成仍须将既有 failure/lifecycle 事件接到发布撤销、明确的 stop key 和停止流程；并在 native 状态推进后调用 `refreshNativeDrain`。没有这些外层接线时，保留 pending 不能被解释为已实现自动恢复或有界停止。

## 复现与合并

在仓库根目录使用已有 Python 3.10+ 和 JDK 17：

```powershell
python -B android/uvc-sim/native-experiment/usb-owner-experiment/run_checks.py --jdk 'E:\tools\jdk-17.0.19+10'
```

runner 使用 `--release 8 -Xlint:all -Werror` 编译固定公共类型、父层 adapter 和本轮源码，只运行新的 `OwnerChecks`。输出在忽略的 `build/run-*/`；无下载、安装或 Android 构建。最终回执、输入散列、命令与结果见 [VALIDATION.md](VALIDATION.md) 和 [validation.json](validation.json)。

本目录可以作为独立的接线边界和替身回归实验合并。实际 Android 桥、receiver/PendingIntent 的来源与身份约束、设备观察映射、服务生命周期、原生驱动及设备验证仍需后续实现。[SOURCE_AND_GAPS.md](SOURCE_AND_GAPS.md) 列明固定来源、许可和实际集成前提；它们不由合成 PASS 解除。
