# USB 权限与 FD owner 验证记录

在独立工作区 `sidecar-usb-owner`、分支 `codex/uvc-usb-owner` 执行；基线为 `ff46084debcfef8b29420fdb786c5ddfc3e19981`。全部新增文件在本目录，未修改父层 adapter、公共类型、Android 应用或其他工作线。

## 最终执行

```powershell
python -B android/uvc-sim/native-experiment/usb-owner-experiment/run_checks.py
```

2026-11-10 UTC，使用现有 JDK `E:\tools\jdk-17.0.19+10`。明确编译 12 份 Java：3 份公共类型、3 份父层 adapter、2 份本轮实现和 4 份本轮测试。`javac --release 8 -Xlint:all -Werror` 与 `OwnerChecks` 均退出 `0`；禁用注解处理、隐式编译及注入 classpath。没有 Android/native 编译或运行依赖。

```text
OWNER_CHECKS_OK cases=22 assertions=283 negative_controls=2
```

[validation.json](validation.json) 是 `build/run-20261110T182229-0f45193b/result.json` 的逐字副本，SHA-256：

```text
86db865c786a05e7f59f5120b2955eee6f3b7bc2bdc9c0de48c19e3d251cb718
```

回执保留 14 个实际输入的字节数/散列、JDK launcher 散列、完整命令、退出码及 stdout/stderr。18 份继承输入锁验证通过，编译/执行前后源文件相同。重新运行会产生新的时间、目录与回执散列；比较代码输入、命令和结果即可。

## 本轮覆盖

| 边界 | 实际检查 |
|---|---|
| 权限请求 | 拒权永久关闭本 owner 准入；错 nonce/selection/attachment epoch/permission revision 不消费请求；重复/迟到结果不能复活；grant 只是候选结果，重新核对系统权限。 |
| 请求阶段竞态 | 回调可早于 request 返回；尚未消费 grant 或 request 尚未成功退栈时，即使系统 hasPermission 为 true 也不能 open；early grant 后 request 抛异常仍保持关闭。 |
| 打开/取 FD | hasPermission、open、getFD 暂停时停止；迟到 connection 留在旧 owner 清理；open 返回后与 getFD 后再次核对；FD `0` 有效、负数拒绝。 |
| 借用身份 | 构造入口不公开，实际 lease 按 owner/对象身份验证；外来 lease 拒绝、重复归还幂等；同一 FD 数值用于新 owner 时，旧 owner 不能影响新连接。 |
| 失效与调用栈 | 明确的 detach/revoke 关本地准入，错误 binding 失效事件无副作用；owner stop 同步调用实际 adapter 的停止准入；borrow 已归还但 native control 尚未退出时仍不 close。 |
| 三方组合停止 | 使用实际 `IsolatedCaptureSession`、`OwnedRgba` 和 `StopBarrier`；native 已排空时，活跃 lease 仍挡住 connection.close；native 与 owner 已完成后，应用持有帧仍挡住总停止。 |
| close 期间 | close 入场不等于返回；在途 close 期间不再读取 native producer；nativeDrainRevision 精确绑定最后接受的实际终态；错误 stop key/重复 stop 不重复关闭；最终 evidence 固定。 |
| 保守失败 | open 抛异常导致取得资源状态未知、close 抛异常导致关闭结果未知、native stop 抛异常及 owner executor inline/reject 均保持失败/pending；failure sink 自身抛异常被隔离。 |
| 错误证据 | 只在独立故障输入场景注入 wrong-key、revision 回退、同 revision 不同内容、terminal revision 改变。即使随后归还借用，仍不得 close，必须保留 PROTOCOL 与证据无效。 |
| 独立负对照 | 在其他条件已满足时分别故意于 getFD 尚在栈上、borrow 尚未归还时提前 close；平台独立账本必须只报告 `EARLY_CLOSE_PLATFORM_CALL` 或 `EARLY_CLOSE_BORROW`。 |

主组合场景的 CountingSession 只计数并转发到实际父层 adapter，不伪造 native 成功证据。应用缓冲夹具按自己持有的 OwnedRgba 引用产生 BufferEvidence；共享预算仅用作辅助断言。停止后迟到 owned 帧由 sink 入口接管后立即关闭，缓冲终态 revision 不变。

使用真实 Java 线程与 latch 固定交错，没有 sleep 成功条件。线程和进程期限只把卡死判为测试失败，不证明驱动有界停止，也不产生排空回执。平台 fake 的方法返回和调用次数不等于实际 Binder、内核 FD 或事件泵状态。

## 首轮失败及修正

[evidence/first-run.json](evidence/first-run.json) 保存首轮原始失败回执（SHA-256 `f5935fff5bf544d6ff0f1cf2a198802153eeb10b7c3c05795c065e4d3e748037`）。当次编译成功，测试在检查所有 `getDeclaredConstructors()` 均为 private 时失败。

对该次生成的 class 使用 `javap -p -v` 只读检查，确认 class major version 52：源码两参数构造器为 `ACC_PRIVATE`，Java 8 编译器另生成一个 `ACC_SYNTHETIC` 的包内访问桥。测试已改为拒绝所有 public/protected 构造器，并要求非 synthetic 的源码构造器为 private；实际 foreign lease/重复归还/owner 身份回归保留。实现没有为通过该断言而开放入口。此检查不声称能防御同进程强反射、Unsafe 或篡改运行时。

修正只涉及这个测试断言；随后完整重编译并运行本轮 22 场景通过。首轮计数和父层旧检查不累计进最终数字。

## 结论边界

这是权限与 FD owner 接线边界的 Java 替身验证。已有系统权限、构造公共 Binding 或注入一个诚实的 port 都不能替代真实 Android 授权。close 提交后依赖 native producer 已接受终态不可变化的契约；本类不能独立检测隐藏的 native 使用或非法复活。

未知 open/close、未退出调用或缺失排空证据的场景会故意保留 pending，不能声称所有测试结束时所有模拟资源均已安全关闭。显式 lifecycle 失效入口不定义 Activity/service 的产品策略，也未接入真实广播/receiver、发布与开始确认链。没有全局 JVM OOM 容错或所有线程交错穷举的证明。

本轮没有运行父层旧 adapter suite、352 项公共合同 suite、C++/ASan/Python 探索，未引入原生二进制、执行 Android SDK/ADB/APK 构建、启动模拟器、连接实体设备、采集/UAC、部署或发布。来源、许可及实机缺口见 [SOURCE_AND_GAPS.md](SOURCE_AND_GAPS.md)。
