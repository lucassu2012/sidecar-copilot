# UVC 停止、FD 和迟到回调：纯替身协议验证

本轮在独立分支 `codex/uvc-stop-protocol`，以 `c8874f6f39dddcd39a63289787fc3ba7a42b2cad` 为起点，研究官方 libusb/libuvc 候选路线的生命周期协议。新增内容只在 `android/uvc-sim/protocol-research/`；没有修改应用、公共接口、原生构建配置或封版 PRD。

**这里验证的是原创 Python 协议模型与可控事件交错，不是 libusb/libuvc 的修复或 JNI 测试。** 模型没有操作系统 FD、USB、摄像头、录音、网络或 UI 模拟器，不加载第三方运行时。原生接入、实机和分发门禁仍关闭。结果及输入哈希见 [validation.json](validation.json)；具体 API、场景和探索边界分别见 [API.md](API.md)、[SCENARIOS.md](SCENARIOS.md)、[EXPLORATION.md](EXPLORATION.md)。

## 要解决的协议问题

上一轮固定源码显示：libuvc 的 status interrupt 与图像 stream 分属不同的传输生命周期；关闭图像回调线程不能证明 status callback 已退出。同步 probe/commit 的 timeout=0 与未传播的错误码，也不能被 Java worker 的超时包装成已取消。授权后返回的 FD 是借用值，关闭责任仍属于平台连接 owner。固定来源、原文锚点和许可证范围见 [SOURCE_MAPPING.md](SOURCE_MAPPING.md) 及 [source-baseline.json](source-baseline.json)。

模型为每个会话、每次提交和每个回调记录独立身份；不会把一个可复用的整数当作授权或资源身份。模型内部生命周期标志不是新产品状态，不增加 PRD 测试编号。它从外部已完成授权和显式开始门禁的场景起步，不模拟权限弹窗或真实 open/start 的阻塞竞争。

| 事件/边界 | 正确协议的可观察结果 | 必须避免的错误 |
| --- | --- | --- |
| stop / 权限失效 / 断连 | 先撤销本代发布权，禁止新提交和 status 重提交，向在飞请求发取消请求 | 在撤销发布之前等待底层、旧事件改动新代资源 |
| cancel 调用进入 / 返回 | 调用栈也借用 transfer；完成回调先退出时仍等待 cancel 调用返回，才可释放 transfer | 只数 USB completion，却漏掉仍访问 transfer 的取消调用栈 |
| cancel 返回 OK / NOT_FOUND / ERROR | 只记录取消请求结果；终态完成尚未到达时仍保留义务 | 把 NOT_FOUND 当作回调已退出或把 cancel 返回当作完成 |
| terminal completion 排队 | 仍有待执行的回调与 buffer 引用 | 因收到终态就提前 free |
| callback enter / exit | 从进入到退出持续占用资源；退出后才可消除本次提交义务 | 回调已进入时 close/free 或 closing 后再次提交 |
| control timeout | 停止发布、请求取消；异步完成或同步栈真实返回仍须另到达 | Future.cancel、截止时间或第二次 stop 被当作释放凭证 |
| 完成永久不来 | 保持停止未确认和资源占用；不能开始同 owner 的新代 | 为了快速返回成功而关闭正在使用的 FD 或释放 callback context |
| native release | 所有 native 提交/回调/阻塞栈义务清零后才可销毁资源并关闭唯一 FD owner | 先停事件泵、忽略 status 或仅依据 image stream 的 join |
| owned Java delivery | 仅携带安全会话身份和自有像素，不再借用 native 指针；旧代拒收并清零 | native 释放后仍访问其 buffer，或把 native 关闭等同所有 Java 像素已清理 |

停止成功分开观察：发布已失效、native 已排空、应用拥有的像素已清理。这些完成条件可以组合为未来集成合同，但本轮没有改变现有 `StopReceipt` 的含义。若 native 已关闭但 Java 队列仍有 owned 数据，模型仍明确保留后者的清理义务。

为测试旧代投递，替身允许在旧 native owner 释放、旧 Java owned 数据仍待清理时创建新 sid；这不是产品开始/模式切换许可。主集成仍须按冻结 PRD 组合停止门禁，不能以该夹具的 open 成功替代应用缓冲完成确认。

## 验证方法与反例

场景测试以 API 返回值、缓冲内容、资源身份和事件日志为可观察接口。独立 oracle 从事件中重建提交/回调和 owner 账本，而不是只调用模型自己的“安全”判断。检查正常完成，也检查永久 pending、同步调用迟到返回、取消调用尚未返回但完成回调已退出、数字 FD 复用，以及 native 关闭后的旧代 Java 投递。

故意错误策略只存在于研究模型，用于负控制。过早释放、过早停止事件泵、忽略 status 或关闭后重提交应产生可定位的协议违例；测试必须确认 oracle 检出具体错误，不能仅以程序抛出任意异常判为通过。策略开关未接入应用。

交错探索采用明确限界的事件状态空间，分别处理异步与同步 control 路径，并报告搜索规模、到界前沿、仍待外部完成和已关闭结果。到界没有反例不证明任意线程调度、任意设备或真实 C/C++ 内存模型安全；合成事件仍假设真实适配器能准确登记提交、回调入口/出口和 owner。实际锁、原子内存顺序、libusb 事件行为、JNI 引用和线程栈要在后续 native 实现中验证。

模型将 submit/open/close 的部分步骤视为原子边界，显式拆开的调用栈仅包括取消调用与阻塞 control。因此未覆盖真实 submit 尚未返回就触发完成回调、open/start 阻塞中的取消或所有 native API 栈竞争。实际 adapter 必须对所有借用资源的调用建立栈引用或等效同步，不能只照搬这两个计数。超时是注入事件，没有真实计时器或停止时延测量。

## 复跑

仓库根目录执行：

```powershell
python -B android/uvc-sim/protocol-research/run_validation.py
```

首次制作证据用 `--record`；已有记录拒绝静默覆盖。默认复跑检查完整交付文件的 SHA-256、15 个继承基线文件和映射文档，顺序运行场景测试和有界探索，检查前后文件字节未改变，不写入已记录证据。11 个官方源码缓存的另行只读复核命令在 SOURCE_MAPPING 中，汇总 runner 不重新下载或读取该缓存。`validation.json` 自身不递归纳入 manifest，受 Git 提交固定；Python 字节码缓存也不在清单中。

可分别执行：

```powershell
python -B android/uvc-sim/protocol-research/test_protocol.py
python -B android/uvc-sim/protocol-research/explore_interleavings.py
```

只有 Python 标准库和本目录原创模块参与执行，没有下载、安装或运行上游代码。结果记录中的 import 清单只是静态审阅辅助，不是动态调用证明或安全沙箱。相对路径和明确的文件边界用于主集成复查；不会因模拟通过而启用真实来源。

## 主集成交接

可独立合并本目录的模型、检查和文档，用作 native adapter 的协议设计与回归材料。公共接口仍由主集成决定，尤其是授权连接绑定、wire/sink 格式分离、错误映射、单调时钟/序号、完整缓冲预算和组合停止回执。

下一步真正的驱动工作仍需把本协议落实到固定的 libusb/libuvc 源码及小范围可审查补丁：status 禁止重提交并取消/等待、控制请求有界且传播错误、分配失败回收，以及事件泵和 callback 上下文寿命。可以先用可控 C/JNI 替身或隔离故障注入验证实际线程/内存；本轮没有执行这些步骤。模型里的合法事件并不证明上游当前能够提供相同保证。

许可证门禁不因原创替身通过而改变：固定 libusb 的 LGPL-2.1-or-later、libuvc 及 utlist 的不同条款、可选 JPEG/SIMD/其他传递依赖，以及具体链接和对应源码材料都仍须按实际构建审核。未核清的旧 UAC 二进制继续禁入；本视频协议不启动音频、不做默认麦克风回退，也不声称满足会议 A/B/C/D 或实机 G1/G2/G3。

冻结需求继续引用 Library 文件 `PRIVATE_ARTIFACT_REFERENCE_OMITTED` 的 PRD v1.5 version 0（1021 行，前轮已全文读取）。本轮没有复制或重建 PRD，也没有扩充功能、状态分支或验收编号。
