# 公共合同到隔离 adapter 的映射

本轮基线 `e02b3fd93da49d4e1a6f76c3e3dd0f7d99c325c5`，包边界为公共 `local.sidecar.copilot.uvccontract` 与自有 `local.sidecar.copilot.uvcadapter`。交付目标是 Java adapter shell + synthetic Backend SPI；不是 JNI、真实 UVC 驱动或 Android 平台 owner。旧 `uvcsim.UvcDriver`、Format 和 StopReceipt 不承接真实驱动，也不改变 app、manifest、数据库或生产构建输入。

## 逐字公共合同基线

本地 [manifest.json](contract-baseline/manifest.json) 锁定主集成交付的 9 项快照，包含 3 个 Java 类型、README/VALIDATION 和历史收据；本轮映射已只读验证快照大小/SHA-256。主线模块采集时尚未纳入该仓库 HEAD，故来源身份以 manifest 的逐文件 SHA 为准，不能写成主线 HEAD 已包含这些类型。没有自建同名不同语义的合同。

| 合同快照 | SHA-256 | 本文采用的实际边界 |
|---|---|---|
| [CaptureSession.java](contract-baseline/src/local/sidecar/copilot/uvccontract/CaptureSession.java) | `02c53a6008af3efd41ffd4a5d2c0f8ac72cba40cb34bed6a7116e407de4b5db8` | Factory/create、open、start、requestStop、nativeEvidence 与 FailureSink |
| [UvcContract.java](contract-baseline/src/local/sidecar/copilot/uvccontract/UvcContract.java) | `2a065bb897aa9e1bb5a18516eb4501f281bebcd5817cf5b1615d63ac311a20ec` | 完整身份、wire/sink、字节预算、typed failure 及三类 evidence |
| [StopBarrier.java](contract-baseline/src/local/sidecar/copilot/uvccontract/StopBarrier.java) | `e6aa6fe91a7ac6528927511f356d82a18c865b2c68e52a77c201a381ef46ab47` | 三个可信 producer 的版本/终态组合检查；不主动释放资源 |

快照 [README](contract-baseline/README.md) 是主集成合同说明；[VALIDATION](contract-baseline/VALIDATION.md) 及 `evidence/` 是历史结果。本轮没有重跑或重计这些 Java/C++ 检查，其原相对链接仍指原模块上下文。adapter 新结果须绑定本轮实际代码和 runner，不能由历史宿主结果替代。

## 主集成保有的职责

| 公共类型/入口 | 类型实际能表达或约束 | 外层必须兑现 |
|---|---|---|
| Selection、PermissionRequest | sessionId/selectionToken/bootId、attachmentEpoch、permissionRevision 不可变；nonce 是非空值。 | permissionRequest 在 requestPermission 前登记、至多一次消费、关联当前选择与 epoch/revision，验证实际 Android 权限/PendingIntent/接收器与产品运行授权。构造该类型不登记请求也不授予权限。 |
| Binding、BorrowedFd | owner token、connection/run generation 与 Selection 组成身份；FD 为非负借用值，0 合法，没有 close 方法。 | 建立真实 connection 唯一 owner，取 FD 也在 borrow 协议内；拒绝停止后的新借用，native drain 后才归还并由 owner 关闭。字符串和 FD 整数不是存活/授权证明。 |
| open → actual WireFormat → start | open 与 start 分开；wire 使用精确 interval100ns，与 OwnedRgba sink 不混合。 | 显式确认实际 wire、当前权限和来源/开始门禁；接受 start 返回确认前必须拒收图像并释放，迟到 start 成功不能恢复已撤销 run。不能让 open/probe 成功自动发布。 |
| FrameSink.accept | 一进入就移交独占 OwnedRgba；包括拒收和抛异常，sink 仍必须最终 close。 | 验证完整 binding/boot、顺序、原单调时刻、开始确认；实现遮盖、发布/预览门禁和最终清零。不改旧时间戳，不保留数组别名，不与 close 并发访问。 |
| BufferEvidence | 本代 stop key、revision、publicationRevoked/admissionClosed 与 queue/in-flight/preview 字节数。 | 真实关闭应用准入、先撤销发布，逐个清理 queue、当前 worker、preview 和已持有自有副本后再报告。adapter/bridge 无法从“调用了 sink”反推应用这些字节已归还。 |
| OwnerEvidence | 关闭 owner 准入、borrow 为零、connectionClosed；终态必须绑定同 stop key 的 drained NativeEvidence revision。 | 真实 FD/connection 操作与计数；不能提前 close 再填一份声称 drain 的 evidence，也不能用新 owner 承接旧借用。 |
| StopBarrier | 只合并同 key 的严格版本；同版本异内容与终态后新版本锁存协议错误；owner 引用精确 native revision。 | 提供三个可信 producer。组合 drained 只是安全回收条件成立，仍须保留 Failure；不是成功采集、自动恢复或真实设备已停止输出的证明。 |

## Adapter 的实际代码边界

实现为 [IsolatedCaptureSession.java](src/local/sidecar/copilot/uvcadapter/IsolatedCaptureSession.java)、[BackendPort.java](src/local/sidecar/copilot/uvcadapter/BackendPort.java) 与 [OwnedFrameBridge.java](src/local/sidecar/copilot/uvcadapter/OwnedFrameBridge.java)。以下是代码职责映射；可信后端必须满足 SPI 前后条件，不能仅因有对应方法就声称真实库行为已验证。

| 公共合同 | 实际实现路径 | 边界与剩余外层责任 |
|---|---|---|
| `Factory.create` / `binding()` | `IsolatedCaptureSession.factory(Supplier, Executor)` 调用构造器建立账本及 bridge；构造器不调用 Backend open 或启动线程。 | Supplier 自身必须只建记账，不能藏 native 副作用。cleanup Executor 是注入的受信执行器，须非阻塞入队、不能 inline；不是 shell 自建的真实 USB worker。 |
| `open(OPEN, BorrowedFd, requested)` | 校验 OperationKey/FD 的完整 Binding，在调用 `backend.open(..., events)` 前登记业务栈和潜在 pump 引用；允许后端在返回前通过 events 登记 status traffic。返回实际 wire 或 typed Failure，账本保留原 session。 | FD 只传给受控 Backend，不执行 OS FD 操作、不校验实际授权、不关闭它。部分失败/迟到成功仍由本 session 清理；返回 success 不授权发布。 |
| `start(START, confirmedWire, sink)` | 要求 open 返回实际模式、未停止且未重复开始；比较 exact WireFormat；先安装 sink、登记调用再执行 backend.start。 | 只能检查参数等于 actual，无法证明用户确实确认、权限仍有效或产品准入成立。先于返回的帧可能到 sink；start acknowledgment 和最终发布门禁必须在外层实现。 |
| submit / control | `SessionEvents.submit/control/reserveNative` 是私有 SPI；先占预算、登记 transfer/operation/cycle 与调用栈，再进入 backend。现有未释放 slot 返回 BUSY；新的 cycle 与旧 cycle 区分。同步 control 的返回单独结束调用引用。 | BUSY 不清旧 cycle。Backend 明确返回 REJECTED/BUSY 的前提是没有为新 attempt 获取资源；core 对抛异常的接受情况标为未知并保留可能义务，返回 failure 不等于无资源。deadline/Failure 不是 transfer terminal 或同步返回。 |
| terminal 与 callback | `Completion.enter` 将具体 transfer cycle 的 terminal 与 callback 引用登记，返回 `CallbackLease`；`frame` 走 bridge，`close` 归还 callback；frame 处理登记独立嵌套 callback 引用，到 deliver 返回才归还，因此不被并发 lease close 提前抵扣。 | SPI 不接收裸 native 指针。终态、回调栈、submit/cancel 栈与帧处理结束不能相互抵扣。真正 native 异步回调仍需实际 ABI 和异常边界适配。 |
| `requestStop(key)` | 校验 Binding 与 canonical key；本地关闭提交准入、关闭 bridge 准入并安排 cleanup。同 key 重复不替换；不同 key/旧 Binding 拒绝。 | 主集成先同步撤销发布。此路径不调用 backend close/cancel/join；错误的 inline Executor 被检测，不在调用栈上补执行清理。Executor 若自行阻塞，接口无法使其强制变为非阻塞，因此该执行器合同是前提。 |
| 异步清理 | cleanup 选择取消、releaseTransfer、closeDevice、stopPumpAndJoin、releaseContext；Backend 调用均在账本锁外，调用前登记引用，返回后再更新事实。 | cancel 返回/NOT_FOUND 不制造 terminal；缺完成保持 pending。释放失败或 outcome 不确定保留义务，不以重复 close 或强制 free 代替。Backend 返回“已 join/释放”的真实性须后续真实实现证明。 |
| `nativeEvidence(key)` | 锁内生成原子快照，计入 image/status/control、callback、业务栈/潜在 pump 与本方 native bytes；内容变化才增加 revision，drained 后冻结。 | 不是 global ByteBudget.used 快照，也不包括已移交 sink 的 queue/worker/preview。只报告本 session 被登记的 Backend 义务，不能代替实际 OS/UVC 停止证据或可信 Owner/BufferEvidence。 |
| typed Failure | 原 failure 保留在 session；FailureSink 在账本锁外收到元数据，接收器异常被隔离/计数；资源、格式、协议等采用公共 FailureKind，而非字符串分支。 | FailureSink 必须有界、不阻塞、不重入设备调用；只“捕获抛异常”不能抢占一个不返回的接收器。错误保留与安全清理结果分开，nativeCode=0 的 Java 夹具错误不是伪造的 libusb 原始码。 |

`BackendPort` 是受控 Java 接口：`open/start/submit/cancel/control/releaseTransfer/closeDevice/stopPumpAndJoin/releaseContext`。其中 SessionEvents 仅暴露受本会话监管的动作；Completion/CallbackLease 约束一个具体提交的结束与借用。它没有 JNI 声明、动态库加载、USB 枚举/授权、真实 fd close、解码器或原版 libuvc status 私有结构访问。为 future native bridge 设计更细的调用边界，不能据此让现有 SPI 的原子方法冒充 native 指令级同步。

## OwnedFrameBridge 与外层 sink 的分界

`BorrowedFrame` 明确 byte[] 的 offset/length、width/height/stride/encoding、sequence 与原 hostReceiveMonoNs，仅在外部 callback 借用期间有效。bridge 只接受固定 tight RGBA；它不做 JPEG、色彩/方向转换、缩放或遮盖。后端负责在整个 deliver 期间保持源稳定，并把源/transfer 字节预留到同一个 ByteBudget；bridge 仅通过公共 `OwnedRgba.allocate` 真实预留并分配目标数组。

`deliver` 校验 IMAGE/SUBMIT Binding、布局和序号/时间（不重写时间），用单个非阻塞 slot 限制复制/投递。停止、格式、容量、预算或复制前后失败时，尚未交给 sink 的私有数组由 bridge close；分配、复制、admission 回调和 sink 调用不持 bridge 锁。非法调用 key 属于 SPI 使用错误，不转换成另一会话的 failure。

从 `sink.accept(frame)` 入口起，sink 独占该数组，抛异常也不把所有权退回发送者；bridge 不在异常后擅自 close 或重用它。copy/admission 与 sink 入口间仍可能发生 stop，因此被准入的调用可能稍后才进入 sink；外层 gate 必须拒绝旧 run，并由 sink 关闭已接收帧。bridge 的 `Snapshot.privateOwnedBytes/activeDeliveries` 是局部诊断，不是 BufferEvidence，归零也不证明 sink 队列、进行中帧和 preview 已清零。

## 验证材料如何归属

本轮测试后端 [ControlledBackend.java](test/ControlledBackend.java) 与 [TestSupport.java](test/TestSupport.java) 提供实际 Java latch 交错、单独记录的 Backend 栈与释放顺序；它们使用受控 FD 数值、heap 数组与假 owner/应用 evidence，不是 OS/USB/JNI。独立 fixture 可以检查 shell 在这些交错中的引用/预算/释放行为；不能用自己产生的成功事件证明原生上游会产生同样事件。

本轮运行入口为 [run_checks.py](run_checks.py)，每次结果写到 ignored `build/run-*/result.json`；主集成最终回执另存 `validation.json`，其来源及 SHA 由 `VALIDATION.md` 记录。本文只做静态合同/源码核对，不另报尚未完成的测试计数；编译和执行结论以该 runner 绑定的实际源、命令与退出码为准。公共快照的历史 Java/C++ 收据不与本轮 adapter 场景相加；既有 Python 协议模型没有在本文工作中复跑。

## 公共类型是否需要修改

对于本轮受控 Java shell，当前合同已能表达上述隔离边界，没有因缺权限执行或真实 JNI 而必须修改公共类型的结论；这些是实现范围缺口。内部 Backend SPI 与测试 hook 应保持自有名称，不把 SPI 变成另一套同名 CaptureSession/Binding。

固定 640×360、stride 2560 的 OwnedRgba 是实验布局，不是任意真实 wire 的完整转换合同。未来需要其他布局/尺寸或真实 decoder 时，应先给主集成提交实际 wire→像素布局、预算和遮盖次序证据；如现类型确实不能表达，再提出最小的显式像素布局/owner 扩展，不能静默修改 OwnedRgba 常量或旧 UvcDriver.Format。ByteBudget 只统计向其真实预留的字节，不能用 global used()==0 代替每代 BufferEvidence。

主集成发布门禁、OwnerEvidence 与 BufferEvidence 未实现时，可以验证 adapter 局部 native evidence，却不能宣称 full stop 已接通。真实 libuvc status teardown、native/JNI、构建和许可前置条件见 [GAPS_AND_LICENSE.md](GAPS_AND_LICENSE.md)；本轮没有修改公共快照或放宽原合同。
