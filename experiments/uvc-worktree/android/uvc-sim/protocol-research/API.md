# 确定性 UVC 生命周期协议模型 API

`protocol_model.py` 是原创 Python 标准库模型，用显式事件研究一种拟议的生命周期协议。它**不是** libusb/libuvc 的包装、补丁或运行复现，也没有证明已消除上游风险。没有真实 fd、系统资源、线程、墙钟、网络、USB、JNI、Android 权限弹窗或模拟器；不导入或执行上游代码。

模型从外层已经授权、已经完成显式开始门禁的场景起步。`open` 只是同步创建仿真资源，模型内启用 publication 不意味着产品“打开设备即自动开始”。它不实现产品外部状态机、真正的 USB open/start 或阻塞 open 的取消交错。产品 PRD 的状态/测试编号不在此定义。

## 导入与身份

```python
from protocol_model import ConnectionIdentity, ProtocolModel

identity = ConnectionIdentity(
    device_id="capture-card",
    attachment_generation=1,
    authorization_revision=3,
    permission_token="opaque-authorized-grant",
)
model = ProtocolModel()
sid = model.open(identity, fd_token="opaque-fd-token")
cycle = model.submit(sid, "status", "status")
```

`ConnectionIdentity` 是 frozen dataclass，四个字段构成不可变授权绑定。字符串不能为空；代次/revision 为非负整数。`sid` 是模型内部单调增加的独立 session owner 身份，不能由设备名或 fd token 替代。模型仅验证提供值之间的一致性，不验证现实权限 token。

`fd_token` 只是字符串。native 借用它，唯一关闭者为该 sid 的 owner；任何方法均不对 OS fd 做操作。前 sid 完成释放后允许新 sid 复用相同字符串，模拟 fd 数字复用；旧 sid 事件不会据同名 fd 改动新 sid。活 owner 尚未释放时不能再 open。相同 device 的较旧 attachment generation 或相同 attachment 上较旧 authorization revision 会被拒绝。它允许同一授权下完成关闭后的重试；是否须人工重新确认由外层负责。

一个模型至多一个 native active session。历史 session 和已经清零的 Java frame 记录保留为研究账本，`_frames` 仍持有对应 bytearray。释放/清零是逻辑资源事件，不能证明物理 free、RSS 回落或 native 安全擦除，不能将其内存占用类比为生产缓冲池。

`open` 仅以旧 native owner 已释放为前提，故意允许旧 Java 副本仍 pending，以测试旧代 delivery/stop 不会串入新 session。这不是产品切换准入规则：实际切换必须由主集成组合全部缓冲清理与停止门禁，不能用 `model.open` 或 `native_drain_confirmed` 代替 `full_drain`。

## 事件 API

所有方法同步返回；没有后台推进。非法生命周期、未知/旧 submission、错 owner、重复 completion/callback 由显式 `ValueError` 拒绝，错误参数类型可为 `TypeError`。代码不依赖裸 `assert` 检查。snapshot/trace 都返回深副本，修改返回值不会改变模型。

| 方法 | 作用及返回 |
| --- | --- |
| `ProtocolModel(faults=())` | 默认正确协议。faults 只能为下文列举的负控制开关 |
| `open(identity, fd_token, fail_at=None) -> int` | 创建 sid。`fail_at` 可为 `open/context/native`，表示在该资源创建之前失败；自动请求停止、释放此前已创建资源。返回失败 sid 以便检查 trace/snapshot |
| `submit(sid, transfer_id, kind, fail=False, blocking_call=False) -> Optional[int]` | kind 为 `status/image/control`。成功注册 obligation，返回该 tid 的提交 cycle（从 1 起）。同一 tid 仅在旧 cycle 已 free 后可复用，新 cycle 加 1。`fail=True` 表示提交未被接受，记录失败并请求停止/尝试清理，不凭空增加已接受 obligation；若其他 obligation 仍在则保留 pending |
| `request_stop(sid, reason="user")` | 首先撤销 publication，设置 closing，然后对每个异步 in-flight obligation 请求 cancel。blocking control 仅记录无法由异步 cancel 清除的取消意图。重复 stop 不再重复 cancel |
| `cancel_result(sid, tid, result="OK", *, submission=cycle)` | 仅记录**此前原子 cancel 调用已经结束**的身份通知，通知本身不再借用 native 资源。`OK/NOT_FOUND/ERROR` 都不等于 transfer 完成，不删除 obligation；即使 completion 已排队仍可为 NOT_FOUND。通知可晚于 cycle 释放到达，但不能发起新的 native cancel。若显式 cancel 栈仍活着，必须使用下一行 return API，不能以通知代替 |
| `cancel_call_enter(sid, tid, *, submission=cycle) -> str` | 显式建立 native cancel 调用栈借用，返回绑定 sid/tid/cycle/调用次数的 token。须已请求 cancel，拒绝 retired、blocking control 或重复 active cancel call。该引用也会阻止 transfer_free |
| `cancel_call_return(sid, token, result="OK")` | 表示该 native cancel 调用真正返回，消除其栈借用并记录安全结果通知。若 callback 已退出，才可 retire；若 completion/callback 尚未完成仍保持 obligation。NOT_FOUND 不会清除 completion/callback 责任 |
| `queue_completion(sid, tid, outcome="COMPLETED", *, submission=cycle)` | 排队某次异步提交的终态：`COMPLETED/CANCELLED/NO_DEVICE/ERROR`。排队仍占有 obligation。每 cycle 只允许一次；不能用于 blocking control |
| `callback_enter(sid, tid, *, submission=cycle) -> str` | 需要 pump 正在运行；消费已排队 completion，建立绑定 sid/tid/cycle 的 callback token。in-flight 转为 active callback，仍禁止释放。ERROR/NO_DEVICE 会记录 `async_error` 并 request_stop，不能视为其他资源已排空 |
| `callback_exit(sid, token, resubmit=False) -> Optional[int]` | 确认该 active callback 退出；只有没有仍在栈上的 cancel 调用时才释放其 cycle，否则延迟到 cancel_call_return。可请求新 cycle；closing 后正常策略记录 resubmit_rejected 并返回 None。正常重提返回新 cycle，必须更新后续 completion/cancel/timer 的绑定 |
| `control_timeout(sid, tid, *, submission=cycle)` | 仅 active control 接受。表示请求者期限到达，触发 stop/cancel 意图；不是成功、失败返回或释放证明。旧 cycle 的 timer 被拒绝，不能误取消新 control |
| `return_blocking_control(sid, tid, outcome="COMPLETED", *, submission=cycle)` | 仅 `kind="control", blocking_call=True` 使用，显式表示真实同步调用栈已返回。该事件才结束 blocking obligation；ERROR/NO_DEVICE 触发 stop。不能给它 queue_completion/callback，也不能用 cancel_result 假造同步返回 |
| `try_release(sid) -> bool` | 必须已 stop。若任何 native cycle 尚未 free，返回 False 并记录 release_pending。全部 cycle/callback/cancel-call 栈已结束才 native_release → pump_stop → context_release → 唯一 owner fd_close → native_drain_confirmed。后者明确不包含 Java owned 队列 |
| `close(sid) -> bool` | request_stop + try_release 的便利调用。重复 close 不再次关闭资源；可重复记录停止/完成查询日志 |
| `snapshot() -> dict` | 状态观察深副本，见下文结构 |
| `trace -> list[dict]` | 属性，返回完整事件日志深副本 |
| `clone() -> ProtocolModel` | 深复制模型，可供确定性交错探索；不同分支的缓冲彼此独立 |
| `state_key() -> str` | snapshot 的 canonical JSON，不含 trace。含资源计数/提交 cycle/frame history，探索器可加入自己独立账本作为去重条件 |

提交 cycle 必须由调用者传入。`cancel_result/cancel_call_enter/queue_completion/callback_enter/control_timeout/return_blocking_control` 的 `submission` 是必填 keyword。不能省略后让模型猜“最新提交”。callback/cancel-call token 自身携带 cycle，旧 token 不能退出新 callback 或新调用栈。

`cancel_requested` 是停止时登记的取消意图，不代表一个隐形 native 调用已经在栈上执行。原子 cancel 已结束后的异步通知可用 cancel_result；研究 cancel 与完成回调交错必须用 cancel_call_enter/return。callback_exit 先到时只结束 callback 栈，transfer_free 与 native release 均等待 cancel 调用返回。该栈返回先到时仍等待 transfer 完成。已经 retired 的 transfer 不能再 cancel_call_enter；迟到通知不能解释成对已释放指针新发起操作。

模型明确拆分 completion callback、blocking control 栈和可选显式 cancel 栈；其余创建、submit 注册/接受及返回、释放及 callback_exit 的检查/重提交被当作原子步骤。它不覆盖实际 libusb_submit_transfer 调用仍在栈上时回调已经到达等交错（callback-before-submit-return），也不证明真实 native 的检查与执行受同一锁保护。真实 adapter 必须给 submit/open/close 等**所有仍借用 handle/transfer 的 native 调用**建立栈引用或等效同步，不能仅复制模型里已显式列出的 cancel/control 计数。这是未覆盖的真实竞争，必须另行验证；不能把此模型的单步当作现有 C 实现保证。

blocking_call 与异步 control 刻意分离：Future/timer 取消不能推出当前无限同步 USB 调用已经返回。若永远不注入 completion 或 blocking return，模型永久保持 pending，事件泵/native/fd 不会正常释放。这里验证安全性；不宣称有界停止或最终可达性。

最小停止过程：

```python
model.request_stop(sid)
model.queue_completion(sid, "status", "COMPLETED", submission=cycle)
model.cancel_result(sid, "status", "NOT_FOUND", submission=cycle)
# 此时 try_release(sid) 必须为 False，completion 只是排队。
cb = model.callback_enter(sid, "status", submission=cycle)
# callback 仍在栈上，try_release(sid) 仍必须为 False。
model.callback_exit(sid, cb, resubmit=True)  # closing，正常策略拒绝重提
released = model.try_release(sid)           # 现在才可为 True
```

## 像素借用与 Java ownership

| 方法 | 责任 |
| --- | --- |
| `copy_for_java(sid, cbtoken, pixels, fail=False) -> Optional[int]` | 仅 active、成功 COMPLETED 的 image callback 可调用；输入 bytes/bytearray 代表在该 callback 有效期内借用的像素。创建新的 owned bytearray，与输入不共享。返回 delivery/frame ID；fail=True 无 owned 分配，仅记录 copy_failed，不清零借用输入 |
| `dispatch_java(delivery_id) -> bool` | 仅 pending owned frame 可派发。按创建时的 sid/不可变身份及 publication 判断；新 session 即使复用同一 fd_token 也不会接纳旧 frame。拒绝时 owned pixels 全部清零并释放；接纳后仍保留 owner，等待显式 drain |
| `drain_java(sid=None) -> int` | 清零并释放该 sid 或全部仍 owned 的 pending/accepted frames，返回本次清理数量。与 native drain 独立，重复调用返回 0 |
| `delivery(sid, pixels: bytearray) -> bool` | **仅 owned-array 夹具便利入口**，转移 caller array 的独占所有权并立即 dispatch。拒绝/之后 drain 可通过 caller array 观察清零。它不能模拟从 native 借用地址安全交接，不替代 copy_for_java；活队列内同一 bytearray 不能重复转移 |

native 停止不自动清 Java 队列，Java 清零也不结束 USB transfer/callback。测试应分别确认两组所有权。copy 可发生在 closing 时仍未退出的 image callback 中，但随后 dispatch 必须拒绝/清零；它不能重新启用 publication。callback_exit 后不能再使用 token 复制。模型的 borrowed 输入只是 API 时序抽象，没有 native 地址有效性证明。

## 快照结构

```text
{
  active_session_id: int | None,
  faults: [str], next_session_id: int, next_frame_id: int,
  sessions: {
    sid: {
      identity: {device_id, attachment_generation, authorization_revision, permission_token},
      fd_token: str, owner_session_id: sid, fd_owner_closed: bool,
      context_created: bool, context_released: bool,
      native_open: bool, native_released: bool, pump_running: bool,
      closing: bool, publishing: bool, failed: str | None,
      native_obligations: [tid], native_drain_confirmed: bool,
      transfers: {tid: {
        kind: str, submission: int, blocking_call: bool,
        in_flight: bool, completion_queued: outcome | None,
        callback_token: str | None, cancel_requested: bool, cancel_intent: bool,
        cancel_results: [str], cancel_call_token: str | None, cancel_call_count: int,
        callback_finished: bool, blocking_call_active: bool, freed: bool
      }},
      callbacks: {token: {transfer_id: tid, submission: cycle, outcome: str}},
      cancel_calls: {token: {transfer_id: tid, submission: cycle}}
    }
  },
  java_frames: {frame_id: {session_id: sid, state: pending | accepted | released,
                         pixels: [0..255], zeroed: bool}}
}
```

snapshot 中 `native_drain_confirmed` 只是模型自身状态投影；独立 oracle 必须根据观察到的注册、回调和释放历史重建条件，不能把此 bool 当证明。负控制会故意造出提前 free 后自认为“完成”的模型状态。

## Trace schema

每条含 `seq`（从 1 起）、`event`、`session_id`、`generation`、完整 `identity`、`fd_token`、`owner_session_id`。transfer 相关事件另含 `transfer_id/submission/kind/blocking_call`；callback 含 `token/outcome`。frame 事件含唯一 `frame_id`、涉及像素时含 length；frame_zeroed 含实际全零检查 `zeroed`、reason。release 事件可含 fault，native_drain_confirmed 含 `java_owned_remaining`。

完整事件名（不是 PRD 状态）：

- 资源：`open_requested, fd_open, context_create, pump_start, native_open, publication_enabled, open_failed`。
- 提交及控制：`submit, submit_failed, publication_revoked, stop_requested, stop_repeated, cancel_requested, blocking_cancel_unavailable, cancel_result, cancel_call_enter, cancel_call_return, control_timeout`。
- 完成及回调：`completion_queued, callback_enter, async_error, callback_exit, transfer_free, resubmit_rejected, blocking_call_return`。
- 释放：`release_pending, native_release, pump_stop, context_release, fd_close, native_drain_confirmed`。
- 像素：`frame_copied, copy_failed, frame_owned, delivery_accepted, delivery_rejected, frame_zeroed, java_drain`。

`transfer_free` 是本协议的 cycle 释放事件，不是直接调用实际 libusb_free_transfer 的日志。释放顺序、隐式 flush、内核 URB 与真正回调栈仍需后续实现和验证。

## 故意错误策略：仅独立 oracle 负控制

| fault | 故意破坏 |
| --- | --- |
| `timeout_release` | control_timeout 后强行 free 未完成 cycle、关闭 native/pump/fd，模拟把期限当完成 |
| `stop_pump` | request_stop 后立刻停止 pump，模拟取消完成尚未收齐就停事件循环 |
| `ignore_status` | try_release 不将 status 计为 blocker，甚至提前 free active status，模拟只看 image drain |
| `late_resubmit` | closing callback_exit(resubmit=True) 仍注册新 cycle，模拟检查与重提交不受同一生命周期保护 |

错误策略允许同一 oracle 产生反例；它们不能进入生产代码。模型不会自动寻找所有交错、所有错误或证明无 deadlock。测试/探索由同目录独立作者实现，验证结果与范围以其报告为准。本文件和模型不宣称 native 上游已修复、已编译、性能合格、实机兼容或许可证审计已完成。
