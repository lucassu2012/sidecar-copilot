# 最小 UVC adapter 实施边界与调用时序

基线：`bbd053664bebb06ce83539a3f95e6fa5758745b7`。这是纯文档实施草案，假设平台已提供用户授权的 `UsbDeviceConnection`／fd；本轮没有打开设备、编译或执行 native、引入依赖、修改生产接口。沿用封版 PRD 和既有状态，不新增功能、产品状态或验收编号。

最小交付仍需要自己的 Android 连接绑定、Java 生命周期门面、JNI 资源管理、帧所有权桥接和停止证明。官方库可承担其中的传输与 UVC 处理，但当前模拟接口不能直接作为真实 adapter 的接入口。第一步可实施的是这些责任的自有接口与模拟后端；实际 native 接入以同轮上游审查和主集成明确合同为前提。

## 已读的基线与可复用范围

| 现有文件／证据 | 可沿用的约束 | 不能直接当作已完成的真实能力 |
| --- | --- | --- |
| [AndroidUvcDiscovery.java](../../../android/local/sidecar/copilot/uvc/AndroidUvcDiscovery.java) | 枚举含 VideoControl／VideoStreaming 接口的候选，显示当时权限快照 | 不建立连接；没有 fd、连接代次、权限回调或断连接线；设备路径和 VID/PID 不足以标识一次连接 |
| [UvcDriver.java](../../../src/local/sidecar/copilot/uvcsim/UvcDriver.java) | 单个新 handle；失败 open 不遗留未返回资源；同步 start 完成；成功 close 必须停止获取；回调交出独占像素数组 | 文件明确限定 synthetic，不能在此目录添加真实设备实现；`open(attachmentId, Format)` 没有授权连接绑定；`Format` 混合了采集与像素含义 |
| [UvcCoordinator.java](../../../src/local/sidecar/copilot/uvcsim/UvcCoordinator.java) | 设备工作串行；显式选择／确认／开始；先撤销发布权限，再异步停止；旧 handle／旧租约不能作用于新选择 | `StopReceipt.confirmed` 只依赖设备任务清空和 driver close 成功；不等待 `sinkControls`、像素 worker 或 JNI 队列；`awaitWorkerIdle` 也不是成功关闭证明 |
| [CaptureGate.java](../../../src/local/sidecar/copilot/uvcsim/CaptureGate.java) | 租约按对象身份隔离；失效后最终发布被拒绝；锁内仅有界指针／元数据操作 | 不是 native callback drain、USB 传输停止或设备关闭证明；不能在此锁内等待 JNI、复制像素或 join |
| [FramePipeline.java](../../../src/local/sidecar/copilot/uvcsim/FramePipeline.java) | 先遮盖后哈希／发布；同租约序号和接收时间不倒退；旧帧说明为 gap；被拥有的数组最终清零 | 只接收 640×360 tight RGBA；两个排队帧、7 MiB owned 上限是 fixture 约束；没有生产分辨率或总 native/JVM 预算合同 |
| [桥接研究](../../feasibility/bridge/README.md)／[记录](../../feasibility/bridge/results.json) | 4 个描述性场景、42 项检查及异步异常负控制支持：已知 RGBX 字节语义下的复制、去 padding、opaque alpha、遮盖与停止排空 | 未调用官方转换器／JNI／USB；不能证明 native 输入是 RGBX，也不能证明真实回调借用期或 stop 行为 |

现有 `FramePipeline` 的 1080p 拒绝走“不支持 fixture 格式”路径；一帧 8,294,400 B 大于 7 MiB 是单独的算术事实。不能用这个实验批准缩图，也不能据此直接制定生产内存上限。

## 候选文件边界

以下是**后续实现的候选路径，尚未创建或接入任何 source set**。主集成可调整命名，但应保留责任边界；真实代码不得放进承诺无设备访问的 `android/uvc-sim/src/`。

| 候选文件 | 最小责任与输入／输出 | 明确不承担 |
| --- | --- | --- |
| `android/uvc-adapter-research/java/.../GrantedUsbBinding.java` | 把一次授权连接、attachment epoch、permission revision、底层 fd 所有者和有效期绑定起来；验证选中设备与当前连接；交出一个不可混用到下一代的绑定对象 | 不把 discovery 的权限快照当作永久许可，不自动重新申请／重连／开始 |
| `.../NativeUvcDriver.java` | 适配主集成批准后的 driver 合同；每次 open 创建新 Java handle 身份；在既有串行设备 worker 上调用 JNI；向已有控制层转交断连、格式变化与失败 | 不复制状态机，不直接写文件、发布预览或绕过共享开始门禁 |
| `.../NativeBridge.java` | 很薄的私有 native 方法声明与回调入口；关联 handle token／代次；JNI 入参校验、异常与所有权交接；不在类加载时隐式启动采集 | 不让 `jlong` 原始地址成为可重用的授权身份，不跨回调保存借用指针 |
| `.../OwnedPixelBridge.java` | 对已确认的格式、有效长度与行／平面布局做有界复制或调用批准的转换后端；生成独占 `byte[]` 及匹配的帧元数据；显式拒绝未约定的像素语义 | 不默默缩放、换色域／范围或把 RGB565／YUYV 当无损 RGBA；不拿转换后的标签掩盖 wire format 变化 |
| `android/uvc-adapter-research/native/uvc_adapter.cpp`（必要时拆一个内部头） | 拥有 native context／device／stream、事件泵、回调上下文、有限缓冲和工作队列；在所有失败路径释放已取得资源；向 Java 提供可核对的完成或失败结果 | 不运行上游例程的自动选设备流程，不带入 UAC、音频 loader 或整个 vendor SDK |
| 同研究目录下的自有 fake backend／检查代码 | 可替换 JNI 操作，注入开关阻塞、迟到帧、复制失败、异步异常与关闭结果；检查资源 owner 和发布屏障 | 不把 mock 关闭成功声明成真实库／设备保证，也不新增 PRD 测试编号 |

`NativeBridge` 与 `OwnedPixelBridge` 可以由同一实现单元承载；分开列出是为了明确 JNI 资源与像素所有权分别由谁负责，不要求增加一个框架或新的公共抽象层。

## 候选调用时序

### 选择、打开与开始

1. 平台把已授权连接封装成 `GrantedUsbBinding`，由控制层绑定当前 `Selection` 的连接代次与权限 revision。断连或权限撤销使该绑定失效；同一路径再次出现必须是新绑定。`AndroidUvcDiscovery` 保持只读，不在枚举时打开设备。
2. 用户明确打开后，由串行设备 worker 执行 adapter open。JNI 导入该 fd、建立唯一 native handle、读取实际描述符并完成候选格式协商；失败时清理所有已取得但尚未返回的资源。若取消与 open 交错，迟到返回的 handle 只进入清理，不能重新授权。
3. 将**wire 协商结果**与**交给 sink 的像素结果**分别呈现给主集成控制层。用户确认实际来源／显示及格式，开始门禁满足后才能 start；open／probe 本身不表示正在记录。
4. start 接收仅属于该 handle 的回调上下文。现有 coordinator 在 `driver.start` 返回后才写 `startedAt`／`started=true`，所以 start 返回前以及时间早于 `startedAt` 的帧会被拒绝。最小适配允许这些帧被丢弃并回收；不能为通过检查而重新打时间或回放旧帧。是否需要明确的“可交付首帧”握手及这些丢帧的审计，由主集成定义；不得让 start 等一个又被 start 本身阻塞的回调。

同轮 [官方驱动源码核对](../upstream-driver/REPORT.md) 是 fd／格式入口的依据。本草案不把 vendor 的 `libusb_init2`、`libusb_get_device_with_fd` 等扩展假设为官方接口。已读的固定 libuvc `448d78b9aa4f54172c1ca5b5d1b4a6b9b93f7957` 存在官方 `uvc_wrap` 路径；是否满足当前 Android 配置、错误回收与所有回调排空，仍按该审查的限定结论处理，不能由函数名直接认定可用。

### 回调到 owned 像素

5. native 回调先检查 handle 代次和有界缓冲额度，记录真实接收时刻／原始序号证据，校验 `data_bytes`、尺寸及布局。回调中的原始 `uvc_frame_t` 和 `data` 按借用对象处理：需要延期处理时，在回调返回前复制到自己拥有的有界槽位；复制失败就释放本次资源并报告，不能继续排无界队列。不能把 raw pointer／DirectByteBuffer 放进 Java worker 后让上游复用或释放。
6. 所有复杂解码／转换在自己的处理 worker 上消费已拥有的数据。libuvc 头文件明确警告回调期间不要调用 `uvc_*`；回调本身只做自己的有界复制／入队，不在其中调用 libuvc stop、close 或转换 API。若选择独立解码器或固定官方转换器，仍需另行核对格式、字节序、色彩矩阵／范围、方向、奇数尺寸及 scratch 预算。当前官方头中的 RGB／BGR 不能据名称等同研究中的四字节 RGBX；RGB24 到 RGBA 的补 alpha／布局复制也需自己的实现和验证。
7. Java 回调边界只接受独占、有效长度明确的数组；`Frame.format` 必须与主集成定义的 sink 合同一致。调用 `onFrame` 后，数组按合同移交，JNI 不再写入、清零或复用它；sink 拒绝时由已约定的接收方清零。尚未交出的中间缓冲由 adapter 清理。JNI pending exception／C++ 异常不能被忽略；交付调用抛异常时到底哪一方还拥有数组，须先写入合同，避免两边同时释放或双方都以为对方接管。
8. coordinator 仍负责 handle／lease／时间检查，最终 `CaptureGate` 与 `FramePipeline` 仍负责遮盖和发布。native 原始 Surface 预览、调试截图或未遮盖的哈希旁路不得成为另一条输出通道。并发转换可能改变完成顺序；序号在入口分配，不能按转换完成顺序重新编号以掩盖旧帧。

接收时间、`bootId` 和 64 位 adapter 序号必须使用主集成约定的同一时钟域／身份。libuvc 的 `uint32_t sequence` 可作为单独证据，不能未经回绕／重启策略就直接承担长期 `long sequence`；其 native 时间字段也不能自动等同现有 `Clock.now()` 或曝光时间。两个时间不一致时拒绝或报告，不能以 `max` 或当前时间悄悄“修好”。

### 停止、迟到回调与恢复

9. 用户停止、断连或权限失效先由控制层撤销当前 `CaptureGate`；此步不等待 native。随后设备 worker 调用 adapter stop／close，同时 sink 控制队列执行 cancel。回调只发出失败／停止请求，不能在 libuvc callback 线程上同步 join 自己，也不能拿着发布锁等 native 返回。
10. adapter 的 close 成功必须表示：不再获取新数据，**control/status 与 image stream 的相关传输和回调都已排空**，内部解码／复制任务不再引用 native handle／fd／JNI global ref。最小候选由 adapter 拥有独立 context 与事件泵：保持泵运行完成取消／排空，按已核实路径关闭 stream/device handles，再停止并 join 自有事件线程，最后销毁 context、释放 JNI 引用并按唯一 owner 规则关闭 fd／平台连接；若选择共享 context，关闭责任须先另定，不能停止其他 owner 的事件泵。已交给 Java 的独占数组可由旧代迟到回调到达并被拒绝；这些回调只能持有仍安全的 Java 身份，不能继续解引用已释放的 native pointer。不得以超时直接 free 正在使用的对象来制造“close 成功”。
11. 旧 handle 关闭确认与像素清理完成分别留证。现有 `StopReceipt` 不能被描述为全栈清零：`sinkControls` 不在 `awaitWorkerIdle` 中，`FramePipeline.cancel` 也不等待正在处理的帧。主集成需定义每租约的应用缓冲完成信号或等价组合屏障；不能为等待某次停机而关闭整个可复用 pipeline，再假定它还能接新租约。进程研究夹具终止时可以 `close + awaitWorkerExit`；这不是生产每次暂停的唯一实现。
12. 关闭阻塞／结果不确定时，沿用既有 pending／`SOURCE_INTERRUPTED` 行为，不确认 receipt、不自动重试未知结果的 native close、不开始新选择。恢复必须重新选择、核对当前有效授权并重新确认／显式开始；是否再次请求系统权限取决于当前平台授权状态。新 handle／租约从新代次开始，旧 cancel 和旧错误不能撤销它。

## 固定上游源码能支持的停止结论

独立只读查看了固定 [libuvc stream.c](https://github.com/libuvc/libuvc/blob/448d78b9aa4f54172c1ca5b5d1b4a6b9b93f7957/src/stream.c) 和 [libuvc.h](https://github.com/libuvc/libuvc/blob/448d78b9aa4f54172c1ca5b5d1b4a6b9b93f7957/include/libuvc/libuvc.h)：

- `stream.c:1356` 的用户回调线程重复使用 `strmh->frame`；`:1434` 的填充逻辑会 realloc／覆写其数据；`:1604` 的 stream close 释放 frame、hold／out buffers。这支持“回调借用、延期前自己复制”的边界，不支持跨回调保留裸指针。
- `stream.c:1554` 的 stop 设置 `running=0`、取消 transfers，等待数组项被完成回调清空，再 `pthread_join` 用户回调线程（`:1591`）。它没有有界超时；依赖 libusb 事件继续被处理以及用户回调返回。不能先停事件泵再等待取消完成，也不能把读取到的 join 当成 Android 实机停机时延证明。
- `libuvc.h:481` 警告回调期间不得调用 `uvc_*`。`stream.c:1586` 仍有 camera-side stop TODO；这里的源码结论仅覆盖所见的 stream 路径。device status interrupt 等非图像 transfer 的关闭／排空另由同轮上游报告核对，**不得把 stream callback join 外推成所有 native 回调均已安全排空**。

上游库的函数可能返回 void 或仅报告部分操作；JNI adapter 仍须建立自己的资源归属和失败结果。若上游审查不能确认设备 status transfer 等资源的 teardown，native close 的成功合同尚不能实现，需要先解决该缺口，本草案不会用 Java 层布尔值补足它。

## 主集成须先定下的合同与错误出口

| 合同缺口 | 不明确时会产生的具体问题 | 最小待决事项 |
| --- | --- | --- |
| synthetic 接口与真实 source 的边界 | 真实实现破坏 `uvc-sim` 的无设备执行保证 | 明确生产接口／隔离研究 source set；当前模拟目录继续无 native 加载 |
| fd／连接授权绑定及 owner | fd 数字或 deviceName 被复用；重复 wrap／重复 close；旧权限结果作用于新设备 | 平台连接保持期限、借用还是独立 fd、副本的唯一关闭者；不要为同一 fd 同时创建两套 libusb／libuvc owner |
| wire format 与 sink format 分离 | 协商 MJPEG 后交 RGBA 会触发现有 `actual.equals(frame.format)`；伪报 RGBA 又隐藏真实格式变化 | 保存协商编码、尺寸、间隔，以及解码后通道／stride／alpha；明确哪个变化撤销已有确认；不偷偷改现有 `Format` 字段语义 |
| 输出尺寸、色彩和完整内存账 | fixture 的 7 MiB 不计算 USB transfers、库内多帧、raw copy、解码 scratch、JNI 和借用源 | 批准可接受格式／原始尺寸、颜色合同和跨层额度；复制前预约额度；以有界队列承接，不能仅靠 sink 拒绝后才限制已分配内存 |
| 首帧／时钟／序号 | start 前帧被拒绝；32 位回绕；跨时钟域数据落入 `receive` 的拒绝分支 | 明确何时可交付、同一单调时钟域、boot identity、每代序号与丢帧元数据；不改写真实观测时间 |
| 停止成功的范围 | driver receipt 成功时仍有 sink 或 JNI 工作；native 库 void close 不等同可靠回执 | 区分同步撤销发布、native 获取停止、应用像素清零；定义组合完成和不确定结果处理，不增加产品状态 |
| 分配／格式等 adapter 错误 | `Callback.onFailure(String)` 当前都映射 `SOURCE_FAILED`；只有 pipeline 特定字符串被识别为 `RESOURCE_PRESSURE` | 类型化内部原因到**既有** End 的翻译由主集成负责，不让 adapter 伪造 pipeline 字符串来切状态 |

既有出口的具体使用边界：平台确认断连交给 `detached`（`SOURCE_LOST`）；当前权限 revision 被撤销交给 `permissionResult(..., false)`；设备／JNI 未分类故障走当前 handle 的失败回调（`SOURCE_FAILED`）。wire 格式变化、adapter 自身额度耗尽需分别接到既有 `FORMAT_CHANGED`／`RESOURCE_PRESSURE`，但现有字符串 callback 尚不能完整表达，应由控制层补齐翻译接口后实现。所有错误必须携带原 handle／代次；关闭中的重复错误只参与该代清理，不重开、不影响新代。

## 最小集成顺序与本轮验证边界

1. **先固定接口语义。** 主集成决定上表的格式分离、资源 owner、错误翻译及停止完成范围；本目录的文档可以先合并，不能因此启用 native。不得在各并行分支各自改一套公共 `Format` 或 receipt。
2. **实现自己的门面与 fake 后端。** 在隔离候选目录里复现授权 fd 绑定、取消中的 open、start 前帧、复制失败、断连、旧代回调和不确定 close；直接沿用现有发布屏障与已有研究案例，不改 PRD 编号。异步异常必须回传检查，不能把 worker 异常死亡当排空成功。
3. **实现像素边界的自有部分。** 在批准格式上验证行／平面容量、借用复用、通道／alpha、微小颜色标记、遮盖前后、乱序及全缓冲释放；用确定交错和完整结果比较。RGBX 的既有 42 检查仅覆盖其明确合同，RGB24／MJPEG 等新增路径不能沿用其通过结论。
4. **上游路径确认后才准备 native 构建。** 依据固定官方源、最小 build graph 和许可材料，独立记录事件泵、fd wrapper、协商／解码及 status/image callback teardown。先做可控制回调源的 JNI 实验；本轮未执行，后续依既有准入条件开展。
5. **最后接应用与受控设备验证。** 只有真实获取、native 与应用排空、原显示／格式和所有既有门禁分别有证据后，才由主集成把 adapter 接入应用 source set。实际 ABI／链接／APK 内容、设备兼容、持续性能及音频合同仍需各自验证；本视频 adapter 计划不实现音频，也不允许自动回退到系统麦克风。

本轮实际做了：阅读上述本地合同、已完成的桥接报告和固定 libuvc 回调／停止源码，形成责任分配和集成先后关系。未新增实现、运行实验、操作设备或声明上游 API 已在 Android 上通过。底层 fd、格式和 teardown 的最终证据以 [同轮上游报告](../upstream-driver/REPORT.md) 为准。
