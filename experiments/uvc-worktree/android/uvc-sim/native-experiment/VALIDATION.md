# 本轮 adapter 验证记录

2026-11-10 UTC 在独立分支 `codex/uvc-isolated-adapter`、基线 `e02b3fd93da49d4e1a6f76c3e3dd0f7d99c325c5` 上执行。产品、公共接口、主线未提交文件和其他工作线均不在本轮修改范围。

## 执行及固定输入

```powershell
python -B android/uvc-sim/native-experiment/run_checks.py
```

使用现有 `E:\tools\jdk-17.0.19+10`。编译目标 Java 8，开启全部 javac 警告并将其作为错误，禁用注解处理、隐式编译和环境附加 classpath。明确输入为 3 份逐字公共契约、3 份 adapter 实现和 3 份测试源码；没有第三方 Java/native 编译依赖。

[validation.json](validation.json) 是成功运行 `build/run-20261110T181001-71a6c4c2/result.json` 的逐字副本，SHA-256：

```text
ff074d48cfa78c3ee4bf07c01894812d02b8dc20e22fcfa9629243450091106a
```

回执保留每个源文件、runner、基线清单和上游复核清单的字节数/散列，JDK launcher 散列、完整命令、退出码以及 stdout/stderr。编译和 Java 执行均退出 `0`；输入在运行前后相同。9 份公共交付快照和 3 份继承来源证据的固定字节检查通过。复现产生新的时间/目录及回执散列是正常现象，应比较源输入、命令与检查结果。

## 新执行结果

```text
ADAPTER_CHECKS_OK cases=22 assertions=189 negative_controls=2
```

| 范围 | 实际检查 |
|---|---|
| 绑定与 FD | 无副作用构造、完整 binding、不匹配拒绝、FD `0` 传递、实际 wire 确认、canonical StopKey、终态 revision 固定。FD 是合成整数，未调用 OS。 |
| 调用与回调交错 | 迟到 open；open/start/submit 返回前回调；BUSY 保留旧尝试；image/status/control 分账；迟到/旧 cycle 不得退休新传输。 |
| 超时与取消 | control 外层超时期间调用栈仍占引用；cancel 返回 NOT_FOUND 之前终态回调已进入的交错；缺失完成保持未排空。 |
| 停止与错误 | submit 抛异常接受状态未知；失败接收器抛异常；close/join/context 返回屏障；close 失败不伪造排空；inline 与 reject executor 精确协议失败。 |
| 帧与所有权 | 独立 owned 副本；格式/容量/序号/时间检查；预算拒绝；复制中停止/槽占用；私有复制失败清零释放；sink 抛异常仍持有 owned 帧。 |
| 跨线程借用 | sink 阻塞时 callback 引用保持；另一线程关闭外层 lease 后，尚未返回的 frame 调用仍独立保留源缓冲引用与预算。 |
| 组合屏障 | 使用实际 `StopBarrier` 与独立 fake Owner/Buffer 生产者，native 已排空、sink 仍持帧时整体不排空；按顺序关闭 owned 帧后才完成。 |
| 独立负对照 | 注入“terminal 与 callback 已结束，但 submit 尚未退栈就释放 transfer”，精确得到 `EARLY_TRANSFER_RELEASE`；注入“device 已关闭、pump 尚未 join 就释放 context”，精确得到 `EARLY_CONTEXT_RELEASE`。 |

真实 Java 线程和 CountDownLatch 固定关键交错，不靠 sleep 或速度阈值判成功。8 秒线程守卫、90 秒进程上限只用于让死锁测试失败；超时从不产生资源已清理的证据。fake backend 有独立的调用/数组/释放账本，两个负对照按精确违例码断言，不接受“抛任意异常”作为通过。

部分错误场景的正确结果就是 pending：未发生终态回调、仍有活动调用、释放动作结果未知或调度器不可用时保留义务。测试结束不代表这些场景中的模拟资源已全部释放，也不代表生产环境具有有界停止。fake `stopPumpAndJoin` 的返回屏障没有创建或 join 真实 native 事件泵线程。

## 证据限制

这是 Java adapter 对实际公共类型的实验验证，不是任意线程交错的穷举，也不是 JNI/native 指针或 Android USB 的验证。ByteBudget 不是整个 JVM/OS 内存计量器；未验证全局 OOM 恢复、真实权限与 FD 有效性、真实解码/遮盖/发布、设备兼容性或来源健康。

主线 Java 352 项与 C++ 17 场景/12 错误策略等结果仅在 [contract-baseline](contract-baseline/manifest.json) 中作为历史交付记录保存，本轮未重跑、未计入 22 场景。旧 Python 4000 状态探索也未重跑；其截断上限不升级为本轮或 native 的完备性保证。

本轮没有运行 libusb/libuvc/生成器、引入 JNI 或未核清二进制、构建 APK、调用 ADB、启动模拟器、操作实体设备、真实采集/UAC、部署或发布。仍缺的实现、许可和设备前提见 [GAPS_AND_LICENSE.md](GAPS_AND_LICENSE.md)。
