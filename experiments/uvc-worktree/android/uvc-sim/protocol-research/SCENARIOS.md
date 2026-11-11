# 生命周期协议的独立场景与日志判定

这是项目自有 Python 标准库替身的研究测试。基线为 `c8874f6f39dddcd39a63289787fc3ba7a42b2cad`。本文件与 `test_protocol.py` 由模型实现者之外的测试方编写；只导入同目录 `protocol_model.py`，不加载 libusb/libuvc、不打开实际 fd、不执行 JNI、设备、网络、模拟器或原生构建。内部事件和判据不是新增产品状态或 PRD 测试编号。

当前直接运行结果为 **38 个 unittest 通过**：27 个正常场景方法、4 个模型错误策略方法、7 个 oracle 日志变异方法。一个方法可能使用 `subTest` 检查多个结果；这些子情况没有重复计入 38。主集成应以冻结文件后保存的运行输出为最终证据。

## 运行

在仓库根目录运行：

```powershell
python -B android/uvc-sim/protocol-research/test_protocol.py
```

本轮使用 Python 3.14.2。命令不写结果文件；`-B` 禁止生成字节码缓存。正常结束码为 0，失败为 1；测试名称、具体负控制命中和汇总均写 stdout，可由主集成保存到独立证据目录。预期末行：

```text
PROTOCOL_TESTS=38 MODEL_NEGATIVE_CONTROLS=4 ORACLE_MUTATION_CHECKS=7 RESULT=PASS
```

也可以用标准库 unittest discovery 导入这些测试。直接脚本运行额外打印负控制的 `scenario/code/seq/session/detail`；计数由实际测试集合计算，没有将已知缺陷转成跳过或预期失败。这里的负控制成功，是安全 oracle 确实拒绝了故意错误轨迹。

## 可观察契约和独立 oracle

`TraceOracle` 仅消费 `model.trace` 的公开事件副本，自建资源账本。它不读取模型私有字段，不接受模型的 `native_drain_confirmed` 布尔值作为证据，也不检查错误策略的名称。辅助断言读取公开 `snapshot()`，只用于检查像素内容、拒绝操作无副作用和新会话未被旧操作改变。

账本规则：

- owner 按 `session_id` 绑定完整 `identity`、`generation`、`fd_token` 与 `owner_session_id`。字符串 `"37"` 只是模拟可复用的 fd 数字；新会话必须有独立 owner，旧 owner 未关闭时不得重用。
- 每次成功 `submit` 建立 `(transfer_id, submission)` 义务，保留 status、image、control 类别。相同 transfer 的重提交产生新代次。失败 submit 没有成功传输义务，但其他成功提交仍待排空。
- `completion_queued` 只表示终结事件排队；`cancel_requested`、`cancel_result`、`control_timeout` 都不能清除提交义务。只有对应 callback 进入，或真正的 `blocking_call_return`，才消除相应在途项。
- callback 进入后增加独立栈引用，直到对应 callback 退出。同步 control 栈只能由专用 return 结束，不能用异步 completion/cancel 冒充。
- `cancel_call_enter` 增加独立取消调用栈引用，`cancel_call_return` 仅归还该引用。NOT_FOUND 可以与尚在队列的 terminal 同时存在；取消调用返回并不消费 terminal。
- 所有成功提交还必须出现对应 `transfer_free`，且不能在 transfer 在途、callback 活跃或 cancel 调用栈活跃时 free。只收到 terminal/退出 callback 而漏 free，不算资源排空。
- native release 前必须撤销发布、请求 stop，并排空传输、callback、cancel 栈和 transfer 对象；pump 不能在这些仍活跃时停止；context 和 fd owner 的释放受其借用引用约束。重复关闭不能重复释放实际资源。
- Java owned frame 有独立 frame id 和长度。copy 必须发生在有效 image callback 借用期间；发布必须属于仍有效的会话。完整清零事件同时结束该替身中的 Java ownership；长度改变、跨代清零、重复释放会被拒绝。
- 日志必须序号递增、身份一致；未知事件名不会被默默忽略。已知的取消/超时/拒绝通知只保留为观察，不替代完成证据。

三个判据刻意分开：

| 判据 | 含义 |
| --- | --- |
| `native_drained(sid)` | 所有成功提交都完成并 free，callback/control/cancel 栈无借用，native/context/pump/fd owner 均已结束；不要求 Java owned 队列为空 |
| `full_drain(sid)` | native 已排空，且该会话全部 Java owned frame 已完整清零并结束 ownership |
| `operation_succeeded(sid)` | full drain 成立，且未观察到 async/open/submit/blocking-control 失败 |

ERROR/NO_DEVICE 发生后，只要真实义务后来全部结束，安全清理仍然可以完成；错误结果保持可见。不能把“操作失败”混同于“资源永远无法排空”，也不能把“一个回调报告错误”视为其他传输已经完成。此处 `operation_succeeded` 是测试分类，未接入产品回执。

`cancel_result` 特别限定为**原子取消操作已返回后的元数据通知**，没有仍在栈中的 native 借用，所以可以延迟送达旧会话。真正的取消调用交错必须使用 `cancel_call_enter/return`；通知不能覆盖或结束显式 cancel 栈。

## 正常场景的具体交错

下表按契约分组，括号内是可在测试输出定位的方法名片段，不新增产品测试编号。

| 契约 | 实际调度与断言 |
| --- | --- |
| 三类排空 | image、status、control 同时提交；先结束 image/status，control terminal 进入后暂停 callback；每一步 try_release 均为 false，最后 callback 退出和 transfer free 后才释放（`all_families_and_callback_exit_required_before_release`） |
| NOT_FOUND 与超时 | terminal 已 queued 后取消通知 NOT_FOUND；或 control 期限到达后请求取消；都保留提交义务，直到 callback 进入并退出（`not_found_with_terminal_queued_remains_live`、`timeout_is_cancel_request_not_terminal`） |
| cancel 调用借用 | cancel enter → terminal callback 完成并退出 → release 仍失败且尚无 transfer_free → cancel return NOT_FOUND → free/release；相反顺序 cancel return NOT_FOUND → terminal 仍 queued 也不能释放（`cancel_stack_outlives_callback_and_defers_transfer_free`、`not_found_cancel_return_does_not_consume_queued_terminal`） |
| 同步 control 栈 | control blocking_call → timeout；异步 cancel/completion 入口拒绝且资源、日志不变；真正 stack return 后才排空。ERROR/NO_DEVICE return 会撤销发布但不会消除仍在途的 status（`blocking_control_timeout_requires_actual_stack_return`、`blocking_control_error_preserves_failure_and_other_obligations`） |
| 没有后续完成 | status 永不交付 terminal，重复取消通知和 release 不会停止 pump/释放 native/context/fd；callback 已退出但 cancel call 永不 return 同样保留资源并阻止新 owner（`permanently_missing_completion_retains_every_borrower`、`missing_cancel_return_retains_resources_despite_terminal_exit`、`new_owner_blocked_while_old_status_completion_is_missing`） |
| submission 隔离 | 正常 status callback 重提交产生新代次，新代次仍独立阻挡 stop；旧 control 代次的 timer/completion/callback/cancel 输入被拒绝，公开快照和日志不变。closing 时 status callback 的重提交请求被拒绝（`status_resubmission_is_a_new_obligation`、`old_submission_timer_and_completions_do_not_clear_new_attempt`、`closing_status_callback_cannot_resubmit`） |
| fd/授权隔离 | 旧代 native 排空但保留 Java owned frame；新代复用 `"37"` 并接收新像素；旧 stop/close/取消通知只触及旧 owner，旧 callback 入口拒绝，旧交付清零且新数组不变。实际 retired cancel 调用不可再借用新 owner；旧 attachment/authorization revision 不可重新 open（`fd_reuse_keeps_stale_delivery_stop_and_cancel_with_old_owner`、`retired_cancel_call_cannot_borrow_reused_fd_owner`、`stale_attachment_or_authorization_cannot_create_new_owner`） |
| 借用与复制 | active image callback 内复制；退出后立刻覆盖源 bytearray，owned 副本内容不变；退出后的 copy 被拒绝。native 可先排空，但 accepted/pending Java owned frame 未清零时 full_drain 仍为 false（`copy_is_independent_of_borrowed_source_and_requires_callback`） |
| stop 中的迟到像素 | callback 尚活跃时 stop；随后 copy/dispatch 只能拒绝并清零，native 必须等 callback exit。直接 owned-array 接口也检查拒绝清零、所有字节清零、重复 drain 无重复 release、同一活跃数组不能重复移交（`stop_during_borrowed_callback_rejects_and_zeros_late_copy`、`owned_delivery_rejection_and_repeated_drain_zero_exactly_once`） |
| 失败回收 | 在 open/context/native 三个预设边界失败，只释放已经取得的资源；submit 失败不发成功 submit，也不抹去其他 status 义务；copy 失败不创建 owned frame，callback 仍阻挡 release（`each_open_failure_cleans_only_acquired_resources`、`failed_submit_creates_no_completion_obligation`、`failed_submit_preserves_other_inflight_obligations`、`copy_failure_creates_no_owned_frame_or_lost_callback`） |
| 重复与观察 | stop/release/close 多次只关闭各 owner 一次；同步 open 已结束后立即 stop 阻止首次 submit；修改 trace/snapshot 副本不能修改模型与借用源（`repeated_stop_and_release_close_once`、`stop_immediately_after_open_prevents_first_submit`、`public_observations_are_copies_not_mutable_control_handles`） |
| 异步错误 | image terminal ERROR/NO_DEVICE → callback 进入 → stop；callback 未退出以及 status 未完成时均不释放。真正全部完成后允许资源清理，错误仍在 oracle 结果中（`async_error_visible_and_never_counts_other_obligations_as_complete`） |

旧 native 已释放、旧 Java 副本仍 pending 时 open 新 sid，仅是验证跨代隔离的对抗夹具，用来注入旧代迟到 delivery；它不授权产品绕过 `full_drain` 或组合停止门禁来重新开始采集。

测试中宣称“永久缺完成”仅表示调度器始终不安排该事件，在指定有限前缀内多次 stop/close/release 仍不能成功。它不是无限时间或所有调度的数学证明，也不承诺未来必然恢复。

## 同一 oracle 的负控制

四种模型错误策略都先运行相同调度的正确策略作为对照，再启用错误策略。判定前从每个事件删除 `fault` 和 `unsafe` 字段；只凭真实释放/提交的事件顺序抓住缺陷，并断言违规 code、资源 detail、session 和事件 seq。没有仅用 `assertRaises` 代替负控制。

| 错误策略 | 故意错误 | 同 oracle 必须命中 |
| --- | --- | --- |
| `timeout_release` | 同步 control 栈尚未返回，timeout 强制 free/释放 native 和 fd | `NATIVE_RELEASE_LIVE`、`FD_CLOSE_BORROWED`，detail 包含 `probe#1:control` |
| `stop_pump` | status 仍在途，stop 立即停止 event pump | `PUMP_STOP_LIVE`，detail 包含 `status#1:status` |
| `ignore_status` | image 已完成，遗漏仍在途的 status 就释放并确认 native drain | `NATIVE_RELEASE_LIVE`、`FALSE_NATIVE_CONFIRMATION`，detail 包含 `status#1:status` |
| `late_resubmit` | status callback 在 stop 之后重新 submit 第二代 | `SUBMIT_AFTER_STOP`，detail 包含对应 transfer/submission |

另外七个日志变异方法检查 oracle 的敏感性：向正确日志添加 callback 内提前 native/fd release；添加 cancel 栈活跃时的 transfer/native/pump/context/fd 释放；删除正常 `transfer_free`；Java owned 队列未空却添加 stop_confirmed；错误已发生却添加 operation_succeeded；将 fd 事件改为旧 owner/授权身份；插入未知事件名。这些是**oracle 校验**，不计入四种模型错误策略，也不是证明原生库会发出这些事件。

## 精确排除与后续集成边界

- 模型 `open` 是同步原子步骤，没有 pending-open 或独立 start API。覆盖的是预设 open 失败点和 **open 已返回、首个 submit 之前** 的 stop，不覆盖真实阻塞 open/start 的取消、完成反转、uvc_wrap 内部分配失败或 Android 权限弹窗回调。文档不得把它概括为“open/start 竞态已验证”。
- identity 和 permission token 是调用者提供的模拟值。测试证明模型按代次隔离这些值，不证明 Android 实际权限校验、系统 fd 生命周期、USB attach/reattach 识别或物理设备关闭。
- 没有线程、真实时钟、线程异常、OS 调度、C 内存模型或 JNI 指针。ERROR/NO_DEVICE 是显式调度的 transfer 结果；callback/cancel 栈通过事件保留。未建模的 worker 崩溃、挂死和线程 join 必须在真实 adapter/harness 中单独验证。
- `frame_zeroed` 在模型中结束 ownership，但历史快照仍保留已清零数组作证据；这里不证明 GC、native allocator 回收、峰值内存上限、吞吐、拷贝耗时或真实指针擦除。样例字节没有像素格式、stride、遮盖或颜色含义；既有 Java 像素实验与生产 FramePipeline 的验证结论不由这些协议测试替代。
- 不验证 libusb/libuvc 的状态中断修复、真实 cancel 语义或设备兼容性。日志协议本身由项目自写替身生成；独立 oracle 能揭露与已列契约冲突的顺序，不能证明替身与 upstream 行为等价，也不宣称穷尽全部可达交错。
- 未改生产文件、既有证据或 PRD。下一层需在主集成明确的 Java/JNI 合同及原生 harness 中，把实际 transfer、callback、control/cancel 栈、event pump、FD owner 和 Java queue 对应到这些可观察义务后再验证。
