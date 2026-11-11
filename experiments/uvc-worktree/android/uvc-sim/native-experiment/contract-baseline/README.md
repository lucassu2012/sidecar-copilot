# UVC 隔离实验公共接口

本模块将 `e02b3fd93da49d4e1a6f76c3e3dd0f7d99c325c5` 的接口审核转为可编译 Java 8 合同、组合停止检查器和独立宿主实验。它属于封版 v1.5 已有采集、停止及隐私要求的工程实现，**不接入 APK，不实现 Android USB/JNI，不开启真实采集，也不新增 PRD 状态或测试编号**。

F134E5D4 的应用、73 项构建输入和五包仍是独立冻结基线。这里的宿主结果不能沿用为 F134 的新能力或 G2/G3 验收。旧 `uvc-sim/UvcDriver` 保持 synthetic-only；其 `StopReceipt.confirmed` 不包括 FramePipeline 在处理缓冲，不能装入真实驱动后直接沿用。

## 可供原生工作线实现的边界

源码包为 `local.sidecar.copilot.uvccontract`，不依赖 Android 或 `uvcsim`。当前三个入口为 `UvcContract`、`CaptureSession`、`StopBarrier`。

| 接口 | 已确定的调用和所有权合同 |
| --- | --- |
| `Selection`、`PermissionRequest`、`Binding` | session、selection、boot、attachment epoch、permission revision、connection owner/generation、run generation 全部不可变。permission nonce 在调用前登记且至多消费一次；这些类型本身不提供权限登记器。FD、路径和 VID/PID 不作身份。新 owner、重连、重启或运行代次均不能承接旧回调。 |
| `OperationKey` | 每个 native 调用前登记 operation、submission cycle、kind 和 image/status/control channel。调用返回、transfer terminal、callback 退出分别记账，且 open/start/cancel/control/close/pump 均计入借用。BUSY 保留旧 cycle；NOT_FOUND 和外层超时不抵扣义务。 |
| `BorrowedFd` | 平台 connection 唯一持有 FD，native 只借用；0 合法，负值拒绝。取 FD 也在平台 owner 借用协议内。进入 closing 后禁止新 borrow，已接受的借用到实际 native drain 才结束。该数据类型不是活跃权限或 FD 有效性的证明。 |
| `CaptureSession.Factory.create` | 只分配自有记账，不开 USB、不启动 pump；先建立 binding 和失败接收器，才能执行 open。构造失败不能留下 native 副作用。 |
| `open` | 专用工作线程同步调用，输入匹配的 OPEN operation、borrowed FD 与 requested wire。允许 status 回调先于返回。返回实际 wire 或有类型的失败；迟到或部分失败资源仍属原 session，必须停止清理，不能交给新连接。打开设备不授权图像发布。 |
| `start` | 只在当前身份、权限、产品启动门禁和用户对实际 wire 的明确确认均成立时调用，参数必须等于 open 的 actual wire。接收器先安装再进入 native；开始确认之前的图像被上层拒收并释放。迟到成功不能恢复已撤销代次。 |
| `requestStop` | 主集成先同步撤销发布，再调用它同步禁止 native 重提交并安排异步取消/排空。该方法不做阻塞 I/O/native 调用/join。重复同一 StopKey 幂等；并发暂停/结束由主集成共享本代 canonical stop key，其他 key 不替换它。 |
| `nativeEvidence` | 返回本代、同一 stop key 的原子快照；内容变化才递增 revision。记录全部调用栈、image/status/control transfers、callbacks、native owned bytes、pump join、context release。终态快照冻结。方法不访问设备。 |

以上是待实现适配器的严格前后条件，并非接口声明已经保证 Android 权限或 native 线程安全。接口没有自动发现后启动、自动恢复、真实格式选择、JPEG 解码或音频路线。当前 `AndroidPcmSource` 只接受内置麦克风；USB/UAC 输入仍未接通。

requestStop/nativeEvidence 收到不匹配 binding 或 canonical key 时抛出 IllegalArgumentException，不改变任何 session；停止尚未请求时读取 evidence 抛出 IllegalStateException。FailureSink 只能有界投递元数据，不得阻塞 native 事件线程、等待原生工作或重入设备调用；适配器隔离接收器异常，保留原失败与清理义务，不能让异常穿越 JNI/native 边界。这些接线行为仍待 adapter 实现及运行验证。

## 图像与组合停止

WireFormat 使用编码、尺寸、精确 100 ns interval，与 `OwnedRgba` sink 分离。首轮实验 sink 固定 640×360、stride 2560，RGBA 字节数组独占。`FrameSink.accept` 一进入就转移所有权，拒收和异常也由 sink 最终 `close`；发送者不再访问或二次使用数组。`pixelsForOwner` 只给当前 owner，不能保留别名或与 close 并发。接收器仍须验证 binding/boot、顺序、单调时刻、格式和开始确认；不得改旧帧时间让其通过。

`ByteBudget` 必须显式配置，在分配前预留；native transfer、source、转换/JNI、队列和预览使用同一逻辑预算并保证唯一归还。首轮宿主实验用 8 MiB，这不是产品或硬件指标。Java 类型只实际统计向其预留的字节；尚无跨 JNI 的统一账本。metadata、运行时和 OS 开销不算在该实验像素/transfer 预算里。数组 close 会清零已拥有的数组并归还预算，不能证明 GC、副本或物理内存擦除。

StopBarrier 只合并三个可信生产者的证据，**不执行原生排空或撤销发布**：

- NativeEvidence：关闭提交准入，所有 native 调用、三类 transfer、callback 和 native-owned bytes 归零，pump 实际 join，context 实际释放。
- OwnerEvidence：关闭 FD borrow 准入，borrow 归零；必须绑定同一 StopKey 的已完成 NativeEvidence revision，之后才允许平台 owner close。native 从不 close 原始 FD。
- BufferEvidence：已撤销发布并关闭应用接收准入，queued/in-flight/preview owned bytes 均归零。悬停 worker 仍持有数组时不能生成终态。

只接受对应 binding/stop key 的严格更新版本；忽略低版本和内容相同的重复快照，同一版本不同内容锁存协议错误。终态 producer 不允许产生更高版本，违反时标记内部协议错误并拒绝组合确认。owner 引用的 native revision 必须精确匹配。Receipt 的 `drained` 只表示所报安全回收条件成立，失败列表仍保留；超时只记录 Failure，不制造清理证据。typed failure 映射既有 PERMISSION_REVOKED、SOURCE_LOST、FORMAT_CHANGED、RESOURCE_PRESSURE 或 SOURCE_FAILED，不通过字符串推断原因。

底层实现必须使终态不可逆。组合器无法证明生产者诚实、没有漏登记，或诊断产生后没有非法访问；真实接口接入前还须逐层证明。未完成期间禁止复用原 session、owner 或缓冲，不用强制 free 换取完成标志。

## 并行文件归属及合并顺序

| 工作线 | 可写范围及交付 |
| --- | --- |
| 主集成 | 本模块 `src/`、公共文档、Java runner/tests；未来 Android 平台 owner/权限 nonce、应用发布准入、单采集源仲裁及组合停止接线。本轮只新增独立模块。 |
| 本轮宿主实验 | 本模块 `host-experiment/` 内自有 C++ 后端、线程实验和 MSVC/ASan 运行证据；不修改公共类型，不当 JNI ABI 或 libuvc 实现。 |
| task-4 原生工作线 | 在现有 `sidecar-uvc-interface` 隔离工作树的 `android/uvc-sim/native-experiment/` 中实现 adapter 所有权核心、受控后端与后续固定上游补丁/来源材料。不得改主线 UvcDriver/Format/StopReceipt、app、数据库、manifest 或构建输入。公共合同差异先提交最小变更说明，由主集成统一修改。 |

先以本合同和宿主负例对齐，再交付原生 adapter 的精确源码/补丁/构建清单；随后才评估 JNI 和平台 owner 接线。公共合同可以只读引用或逐字复制到隔离工作树并登记 SHA-256，不自建名称相同、语义不同的类型。本轮不产生可分发原生库。

## 固定来源和后续实验清单

源证据来自 task-4 e02b3fd 的 `interface-contract/{NATIVE_CONTRACT,ANDROID_CONTRACT,LICENSE_BOUNDARY,DEVICE_PREREQUISITES}.md` 和精确 JSON 锚点。继承固定来源为 libusb v1.0.30 / `87a55632db62c9bdc58cd31d3ccfa673f1bb017f`，libuvc v0.0.9 / `448d78b9aa4f54172c1ca5b5d1b4a6b9b93f7957`。前者已查 Android C 集合为 LGPL-2.1-or-later，后者 BSD-3-Clause，树内 utlist 2.0.2 为 BSD-1-Clause；尚不是完整构建/分发依赖审计。JPEG 未选定，旧 AAR/Hutool 来源门禁未解除，不引入它们。

原生工作线接续以下已有停止要求的实验，不新建官方验收编号：

1. callback-before-submit-return、terminal-before-cancel-return、BUSY/NOT_FOUND、迟到 open：真实对象在最后调用栈/回调前保持存活，唯一释放。
2. image/status/control 分账，status 重提交与停止竞争；同步控制外层超时后实际调用仍存活，真正返回才能清理；缺完成通知始终未确认。
3. 所有业务调用退出后才停止/join pump，随后清 context、归还 FD 借用并让平台 owner close；interrupt 或事件等待返回不能替代 join。
4. 固定 libuvc 的 status teardown 尚缺已证取消/回调排空，必须单独复现并审核最小修补；stream join 不覆盖它。自有宿主模型通过不能消除此阻断。
5. callback 源复用、投递异常、队列满、分配失败和 in-flight stop 的实际 owned bytes；真正 JNI pending-exception 路径须等 JNI 可运行再测，不能用 C++ 或 Java 替代。

上游实际编译前仍需完整源码/头文件/配置、生成文件来源、精确补丁、ABI、链接闭包和通知/适用 LGPL 材料。现有 MSVC 宿主编译不等于 Android 构建；没有 NDK/TSan 或设备证据就明确记未运行，不安装工具或开启 WSL 来规避本轮边界。

## 验证命令及限制

在仓库根运行 `pwsh -NoProfile -File android/uvc-contract/run-checks.ps1`，使用现有 JDK 17 编译 Java 8，独立 `build/<run>/` 保存源码哈希、编译和执行输出及退出码。C++ 命令和 ASan 正负例见 `host-experiment/HANDOFF.md`。结果与精确版本见本模块 `VALIDATION.md`，缺失的结果不推定通过。

本轮不使用 ADB、模拟器或实体设备，留存线保有已分配的窗口。F134 的首页反射入口、直接删除接口测试不覆盖完整凭据/按钮链；注入中断不覆盖真实断连或拔线转线下。本模块也不补齐这些证据。手机/OEM build、采集卡/固件、Hub/供电/线缆/HDMI 组合仍待确定；真实 USB 权限、FD 生命周期、16 KiB 加载、声画链路、热耗和长时采集均未验证，真实采集及分发门禁继续关闭。
