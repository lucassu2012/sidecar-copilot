# UVC 实际验证前提与证据边界

本轮基线为 `3dbbcb17f26816689d4b51b714996d937b9f4acd`。这里只读继承的协议模型、验证记录和适配计划，定义下一层接口与验证需要交付的材料；没有复跑模拟、编译或加载 native、连接设备、修改生产接口或增加 PRD 功能、状态、验收编号。结构化对应记录见 [verification-boundary.json](verification-boundary.json)。

**当前交付是候选方案与模型证据，尚不是完成的真实 UVC 驱动。** 既有 Java 像素实验、Python 生命周期模型和固定上游源码分析可以指导实现，不能证明 JNI、libusb/libuvc、Android/OEM 或某个采集卡组合已经满足相同合同。公共接口及其对冻结 PRD 的映射由主集成统一决定；本文件的待办项是实现前提，不新增运行时状态或独立审批流程。

## 已有记录具体证明了什么

依据 [validation.json](../protocol-research/validation.json)、[EXPLORATION.md](../protocol-research/EXPLORATION.md)、[API.md](../protocol-research/API.md) 与 [SCENARIOS.md](../protocol-research/SCENARIOS.md)。记录时间是 `2026-11-10T16:04:13.411938+00:00`，历史运行基线为 `c8874f6f39dddcd39a63289787fc3ba7a42b2cad`；当前分支继承该记录。本轮没有生成新测试结果。

| 记录 | 精确范围 | 不能推出 |
| --- | --- | --- |
| status + asynchronous control BFS | 已访问 4,000 节点、检查并接受 27,644 条边、17,112 条重复边；6,533 条后继边受状态上限阻挡；保留节点中 closed=0、pending=3,991、open-quiescent=9 | 6,533 不是独立遗漏状态数；closed=0 不等于无法关闭，也不能说已完整验证异步关闭。部分未保留的后继边仍可能产生关闭事件，所以事件名出现不能改写节点计数 |
| status + blocking control BFS | 1,272 个已访问节点，其中 native-closed=168、pending=934、open-quiescent=170；5,214 条已检查边、3,943 条重复边；无 state/depth frontier | 168 是图中的组合状态节点，不是 168 次独立关闭实验，更不是实机成功数。无 frontier 仅限该动作生成器和界限，不表示真实并发穷尽 |
| 两个定向关闭 witness | 分别给异步 control 注入 terminal completion、给同步 control 注入真实 return；callback 尚在栈时拒绝 release，随后走 native→pump→context→唯一 fd owner 的模型关闭路径 | 只证明这两条人工调度路径存在；不证明 terminal/return 必然到达、调度公平、有界时延或硬件关闭。不能补写成 async BFS 已覆盖 closed 节点 |
| 38 个场景测试 | 27 正常方法、4 模型错误策略、7 oracle 日志变异；4 错误策略的 fault/unsafe 标签删除后仍由资源事件账本检出 | 不是 38 次设备测试，也不代表 38 个 PRD 验收编号。日志变异是 oracle 敏感性检查，不是上游缺陷复现 |
| 定向身份／cancel 补充 | 17 项身份检查；另 3 项 cancel 调用栈检查，其中包含提前 free 的日志变异 | 这些不属于主 BFS 的全排列范围；不能把显式 cancel 栈、多会话、image/Java copy 的定向覆盖混入主图规模 |

共同界限为 setup 后最多 12 个调度动作、每图最多 4,000 节点、最多 2 个 status submission、每 control cycle 一个 timeout、每 cycle 一个取消结果通知。主图种子仅有 status+control；没有展开 image、Java copy、多会话、open 失败及显式 cancel 调用栈的笛卡尔积。`native_closed_reached_in_each_positive_run=false` 是保留的真实结论。`stalled_pending_nodes=0` 不保证不会死锁，也不保证外部完成会发生。

`native_drained`、`full_drain` 与操作结果须分别保留：native 资源排空不等于 Java owned 队列清零；发生错误后可以安全回收，但不能抹去错误结果。旧 native 已释放、旧 Java 数据仍 pending 时 open 新 sid，是注入迟到 delivery 的对抗夹具；产品开始／切换仍须满足主集成的组合停止门禁。

## 两个尚未被原子模型覆盖的关键合同

**control timeout 不保证调用退栈。** 期限到达只允许撤销发布和发出停止／取消意图。同步调用必须实际返回、异步请求必须收到对应 terminal 并完成 callback，才能归还其借用；NOT_FOUND 也不能替代 terminal。若完成永久缺失，当前模型保持资源占用和停止未确认。这是避免提前释放的安全结果，不是满足有界停止要求的证明。未来接口必须能报告“等待尚未结束”的事实，不能把 Future.cancel、超时异常、worker 已退出或重复 close 翻译成成功回执。若真实路径无法满足冻结需求的停止边界，保持实现缺口，不凭布尔结果启用下一次采集。

**submit 在模型中注册、接受并返回是一个原子动作。** 没有覆盖 callback-before-submit-return，也没有覆盖真实 check/submit 的竞争、open/start 阻塞中的取消，以及 close/分配过程内的指令交错。适配器必须在调用前登记独立的 operation-state 引用，保护调用返回处理或 callback 仍会访问的 operation/session/user_data 及相关借用对象；不能仅照搬模型显式拆出的 cancel/control 两种栈。返回时依据该独立控制块协调结果，不能假定原始 transfer 仍存在。失败返回与既有 submission／callback 的归属也须按实际返回码验证，不能由替身替实现猜测。

按本轮 [NATIVE_CONTRACT.md](NATIVE_CONTRACT.md) 的固定源码结论，libusb 成功 submit 解锁后、公开返回前，另一事件线程可以运行 terminal callback 并释放原始 transfer；成功返回尾部不再解引用它。因此不能笼统要求“submit 尚未返回时原始 transfer 必须存活”。若项目选择把原始 transfer 保留到全部适配器调用与 callback 引用退出，这是项目的保守所有权策略，须显式禁用 `LIBUSB_TRANSFER_FREE_TRANSFER` 自动释放并协调 buffer 标志和唯一 owner；它不是 libusb 强制要求。libuvc 私有 transfer 的释放行为也不能仅凭外部计数器改变。

## 先由主集成冻结公共接口

| 主集成需产出的合同 | 可行动的最小内容 | 当前等待条件 |
| --- | --- | --- |
| 授权连接与唯一 owner | 平台连接、选中 attachment 代次、permission revision、Java/native handle token 的不可混用绑定；fd 是借用还是独立副本、谁唯一关闭、借用结束如何证明 | 合同及失败路径确定后，平台与 native 实现才能共同接线；设备路径、VID/PID 和 fd 整数不能替代本次连接身份 |
| 打开／确认／开始 | 分开描述 open/probe、源与模式确认、显式 start；取消后迟到 open 返回的 handle 只清理；定义 start 返回前帧的拒绝和回收，不能靠改写时间把旧帧变成首帧 | 无 pending-open/start 实现证据；先交隔离替身复现与 owner 规则，不把已有同步 API 测试冒充完成 |
| wire 与 sink 格式 | wire 编码、实际尺寸、精确 interval；解码后布局、通道、stride/plane、alpha、颜色矩阵／范围、方向；哪些变化使已有确认失效 | 不把当前 synthetic `Format` 的含义静默改变，不因 fixture 拒绝 1080p 就私自缩图 |
| 帧身份、时间与错误 | 同一单调时钟域及 boot identity、每代序号与原始设备序号；提交代次、gap/drop 原因；类型化内部失败映射到既有产品出口 | 主集成决定公共字段与映射；adapter 不自造 PRD 状态，不伪造 pipeline 错误字符串 |
| native 与应用组合停止 | 同步撤销发布；status/image/control 所有 transfer 和全部 native 调用栈结束；event pump、JNI 引用及 fd owner 结束；队列／在处理帧／预览的 owned 数据清理；各完成与失败结果的组合 | 现有 `StopReceipt.confirmed` 与 `awaitWorkerIdle` 不能自行升级成全栈排空。须有每代组合完成证据后才讨论产品恢复 |
| 总缓冲与 ownership | USB transfers、库内组帧、借用源、自有 raw copy、decoder scratch、JNI/Java、queue/preview 的总账；分配前额度与失败回收；交付正常／抛异常时唯一 owner | 固定640×360、7 MiB fixture 约束不是生产预算。主集成确定实际模式预算，native/像素实现分别兑现 |

这些合同的草案可先审阅，接口 owner 始终是主集成；实现分支只能提出明确缺口与证据，不能各自修改公共 `Format`、receipt 或错误出口。

## 在设备之前应由隔离 native 替身解决

这里要求的是后续可控 C/JNI 实验或等价的隔离实现证据，本轮没有运行。其目标是把协议义务落实到真实线程、内存和 JNI 边界；继续增加同一个原子 Python 模型的次数不能替代这些证据。

| 缺口 | 最小可观察实验 | 通过所需材料与责任 |
| --- | --- | --- |
| submit/open/close 与 callback 栈借用 | 可控 backend 在 submit 返回前进入并退出 callback；在 open/start 返回前插入 stop；在 cancel 仍在栈时结束 terminal；按实际返回码核对既有 submission 与迟到 callback | native adapter owner 提供独立 operation-state／锁与资源账本；适配器仍可能访问的对象不得销毁，原始 transfer 是否延迟释放按显式 owner 策略验证；无锁内自 join、跨代改变或丢失错误；主集成确认与公共合同一致 |
| status 与 stream 分离、禁止重提交 | 同时保留 status/image/control；分别先完成其中一类；关闭与 status resubmit 检查交错；NOT_FOUND+queued terminal、cancel ERROR、永不完成 | status/image/control 全部独立清账；保持事件泵处理取消直到相应借用结束。固定 libuvc 的 status teardown 缺口需实际补丁与隔离复现，不能用 stream join 代替 |
| control 与有限等待 | 真正的阻塞调用可由 harness 控制返回／不返回，核对 timeout 后 owner 保留、错误传播和最终 return 的路径 | 证明期限到达不会提前 free；另给真实请求是否有界的实现依据。不能把安全保留资源当作已满足停止时延 |
| 复制、JNI 与总内存 | 源 buffer 在 callback 返回后立即复用；逐层分配失败、队列满、JNI pending exception；交付调用抛异常；停止时保留一个在处理帧 | 比较所有字节和 ownership；只有安全自有副本可延期使用；无未遮盖输出旁路；各层归还与总峰值统计可关联到同一代。日志清零标志不能代替实际 buffer 验证 |
| 原生构建与可追溯修补 | 按固定源码、补丁、工具链、ABI和选项生成隔离产物，核对实际链接／装载闭包与错误输出 | build owner 提供 artifact hash、真实依赖清单和可重现命令；原生测试结果必须来自这份产物，不能把 source-match 当二进制可复现构建 |

隔离替身能够检查代码可控的生命周期和内存错误，不能提供实际 OEM 内核、USB 控制器或采集卡行为保证。若真实 API 无法提供所需完成信号，交给主集成修订实现路径；不能由替身自动生成“完成”来掩盖。

## 实际组合与 OEM 才能确认的内容

设备验证必须绑定一次明确组合，结论不能外推为全部 Android 或全部 UVC 采集卡兼容。只有已有准入条件满足后才开展实际操作；本轮不操作实体手机。

| 前提 | 进入实际验证前应冻结的内容 | 实际观察边界 |
| --- | --- | --- |
| 组合与构建 | app commit／产物 hash；Android/OEM build、API、ABI、kernel；手机和采集卡型号／固件；线缆、hub/供电、USB 链路；已选择的 HDMI 源、wire mode 与精确帧间隔；native commits/patches/toolchain/build flags/链接方式 | 权限、fd wrap/claim、SELinux/kernel、USB 带宽、供电、热量和真实时延只能在该组合确认；模式变化须记录，不能混成同一组结果 |
| 权限、来源和开始 | 当前选择与授权绑定一致；沿用已有来源／画面确认和显式开始流程；授权失效与重新连接使旧绑定失效 | 模型 permission token 不是系统授权证明。重新申请权限是否需要弹窗取决于当前平台授权，不能默认每次恢复弹窗，也不能默认自动恢复采集 |
| 实际图像合同 | 实际描述符／协商结果、输入编码和帧布局；可辨识测试内容上的颜色／方向／字形与遮盖结果；采样与源显示一致的记录 | 官方格式名称不证明实际 byte layout、色彩范围或文字可读性；真实编码器、padding、stride、部分帧和格式切换由组合证据确认 |
| 实际停止与恢复失败 | stop/断连/授权失效时先撤销发布；真实 terminal/callback/native API 栈、event pump、fd owner、应用缓冲的完成记录 | 日志中的 close 调用或 image callback join 不能单独证明没有继续获取。无法观测的层明确标为未证实；结果不确定时保留停止未确认，不重用旧资源或自动开始新选择 |
| 失败后的可恢复性 | 已冻结 PRD 中的失败和恢复路径；分配／格式／JNI／断连等失败关联原 owner；清理后的新选择、新确认与显式开始 | 某一代错误不能清理新代；一次超时或重试不能把未知关闭结果改成成功。实际 OEM 权限及重连行为需记录，不能由模型推断 |
| 许可与实际闭包 | 依据已完成来源审查固定官方 libusb/libuvc 及必要补丁；按实际构建补全可选 JPEG／转换库、传递依赖、NOTICE、对应源码和链接／分发材料 | 模型通过不解决许可证或实际打包来源；未核清旧 UAC 二进制不进入该视频路径。build owner 与许可审查者确认的是本次 artifact 的闭包，不是只有 README/POM 的推断 |

实际硬件阶段应区分主机侧资源结束与设备侧停止输出的可观察事实。若某个层面没有可用观测，不把“后续没有 Java 帧”扩大成该层已经停止的证明。性能、热量与长期稳定性记录也只适用于被冻结组合和实际观察时长。

## 为后续证据预留的字段

这些是验证材料字段，尚未新增应用遥测或公共 API。主集成确定保留方式；默认只收集解释结果所需的元数据，实际画面只按既有确认／遮盖规则留证，不把原始权限凭据或 native 地址写入默认日志。

| 证据组 | 必需关联信息 |
| --- | --- |
| 构建与组合 | run id、仓库 commit、artifact SHA-256、native 源与补丁 hash、工具链／ABI／选项、实际依赖与许可证清单引用；OEM build、设备／采集卡／固件、线缆和供电组合、输入模式与观察时长 |
| 身份与控制 | 非敏感会话关联 id、attachment generation、authorization revision、选择／确认记录引用、handle／lease／FD owner 的安全 token；open/start/stop 请求与返回及其真实结果 |
| 传输与调用栈 | transfer id+submission、status/image/control 类别、实际 submit enter/return、callback enter/exit、cancel enter/return/result、blocking control enter/return、terminal queued/交付顺序、event pump 状态；每个事件的单调时间与时钟域 |
| 像素与内存 | source/wire 与 sink 描述、实际长度/stride/planes、复制与 ownership 移交、frame id/代次/序号、队列／在处理／预览计数、逐层字节数与峰值、拒绝原因、实际清零与归还证据；任何哈希遵循先遮盖后计算的既有约束 |
| 关闭与错误 | publication revoked、各类 obligation 清零、所有 native 栈退出、JNI 引用/handle/context/pump/fd owner 结束、应用 owned 数据清理、组合停止结果；原始错误码及既有出口映射、仍未解决的义务、负控制命中、stderr/断言结果 |
| 结果归档 | 输入与日志 hash、执行命令、观察者／owner、执行边界、起止时间、预期与实际、失败或未执行原因；把模型／隔离 native／实际设备结果分别标明，不用 PASS 覆盖未执行项 |

## 当前可行动的等待条件

1. 主集成交付上述公共合同及冻结 PRD 的映射，尤其是组合停止、授权 owner、wire/sink、错误翻译和总内存；接口未定时保持自有门面／替身隔离。
2. native adapter owner 在固定上游路径上落实 status teardown、所有 native API 栈借用、控制错误与真实返回、失败回收；提供隔离 C/JNI 故障注入结果，而非重跑当前 Python 图。
3. build／许可 owner 给出将用于验证的具体 artifact 和实际依赖闭包；未核清依赖不进入构建，来源文本匹配与二进制复现分别陈述。
4. 主集成把冻结组合、源确认和前述证据字段交给受控设备验证负责人；按既有准入条件执行。未完成 native/组合停止证据前，当前方案不作为已完成真实驱动，也不据模拟 PASS 开启真实采集。

本轮只交付前提与边界，未改变任何准入授权，未生成新运行证据。
