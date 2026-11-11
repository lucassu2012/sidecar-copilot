# Native 接口契约增量审核

适配器必须兼容 **submit 尚未返回，另一事件线程已进入完成回调并可能释放 transfer** 的时序。固定源码支持这条合法调度推断：成功路径释放 transfer 锁后才返回，完成路径取得该锁后调用用户 callback，并明确允许 callback 释放 transfer。它不是实机复现或 libusb 违规结论。操作登记、会话身份与调用栈引用必须在调用之前建立。

本轮只读接口审核，基线 `3dbbcb17f26816689d4b51b714996d937b9f4acd`。复用官方 libusb 1.0.30 / `87a55632db62c9bdc58cd31d3ccfa673f1bb017f` 和 libuvc 0.0.9 / `448d78b9aa4f54172c1ca5b5d1b4a6b9b93f7957` 的继承缓存，没有新下载、复制上游源、重跑协议模型、编译或执行 native/JNI、设备/模拟器操作。

[native-contract.json](native-contract.json) 保存 **9 个固定源输入、16 条函数契约、134 个精确行内锚点**。每条含签名、owner、阻塞性质、返回码、callback 最后引用、实现前置条件及限制。`new_or_inherited=new` 表示本轮新增的契约判断/精化，不表示来源是新下载；所有源输入均为 inherited。已有 status teardown、probe 等阻断只保留接口所需的引用，不重述上一轮完整路线报告。

## 本轮新增的关键判断

### submit 返回不是 callback 起点

`libusb_submit_transfer` 成功路径在 `io.c:1558` 调用 backend，在 `1560` 设置 IN_FLIGHT，`1562` 解 transfer 锁，`1570` 才返回。`usbi_handle_transfer_completion` 在 `1702–1704` 取得并释放同一锁、清 IN_FLIGHT，`1725` 调用户 callback。Linux bulk/interrupt 完成路径 `linux_usbfs.c:2530–2536` 先释放 transfer 锁再进入 completion，和上述调度相容。[submit 尾部](https://github.com/libusb/libusb/blob/87a55632db62c9bdc58cd31d3ccfa673f1bb017f/libusb/io.c#L1552)，[completion](https://github.com/libusb/libusb/blob/87a55632db62c9bdc58cd31d3ccfa673f1bb017f/libusb/io.c#L1679)，[Linux 完成入口](https://github.com/libusb/libusb/blob/87a55632db62c9bdc58cd31d3ccfa673f1bb017f/libusb/os/linux_usbfs.c#L2530)

因此，在“成功 submit、另有事件处理线程、完成已到达”的前提下，可调度为：submit 解锁 → 事件线程 callback/free → submit 返回。成功尾部只检查局部 `r`，不再读取 transfer，所以不能把这一时序报作库内 use-after-free。未知的是具体设备上的发生时刻、频率以及其他 backend 的完整行为；本轮没有证明所有 backend 必然采取此序。

实现约束：先登记 `session/operation/submission cycle`、callback/user_data 及独立调用栈引用，再进入 submit。返回时对自己的 operation 控制块协调结果，不假定 raw transfer 仍活着或 callback 尚未发生。`BUSY` 明确表示此前已有提交；不能像“一个全新请求提交失败”那样清除已有 cycle。真实 adapter 必须为 **open/submit/cancel/control/close 等所有仍借用 handle/transfer 的调用**建立栈引用或等效同步，不仅复制模型中的 cancel/control 计数。[submit 返回码与实现](https://github.com/libusb/libusb/blob/87a55632db62c9bdc58cd31d3ccfa673f1bb017f/libusb/io.c#L1475)

### transfer 的物理释放与会话最后引用不同

libusb 允许在 terminal callback 内 free transfer；设置 `LIBUSB_TRANSFER_FREE_TRANSFER` 时由库在 callback 返回后 free，此时 callback 再手工 free 会重复释放。`LIBUSB_TRANSFER_FREE_BUFFER` 只规定 transfer 被 free 时的 buffer 处理。session、user_data、JNI 复制品与 Java 队列没有因此自动排空。[标志原文](https://github.com/libusb/libusb/blob/87a55632db62c9bdc58cd31d3ccfa673f1bb017f/libusb/libusb.h#L1405)，[free 约束](https://github.com/libusb/libusb/blob/87a55632db62c9bdc58cd31d3ccfa673f1bb017f/libusb/io.c#L1317)

此前模型采用“callback 退出、所有借用调用返回后再 free”是可选择的保守 adapter 协议，不能误称 libusb 公共 API 强制所有用户如此。选这一路线必须协调自动释放标志和单一 owner；libuvc 私有 transfer 不能仅凭一个外部计数器就改变其内部 free 行为。实际实现仍需验证引用、锁顺序及释放位置。

### cancel 返回和 NOT_FOUND 均不证明 callback 已退出

`libusb_cancel_transfer` 在 transfer 没有 IN_FLIGHT 或已标 CANCELLING 时返回 NOT_FOUND。completion 在调用 callback 前已清 IN_FLIGHT，所以 NOT_FOUND 与“callback 尚未进入/尚未退出”可同时成立。cancel 自身在解锁后才返回，另一个线程也可能在这个间隙运行 callback。取消请求完成、transfer 终态、callback 退出和 cancel 调用栈返回须分别归账。[cancel](https://github.com/libusb/libusb/blob/87a55632db62c9bdc58cd31d3ccfa673f1bb017f/libusb/io.c#L1573)，[完成回调前清位](https://github.com/libusb/libusb/blob/87a55632db62c9bdc58cd31d3ccfa673f1bb017f/libusb/io.c#L1702)

迟到的 cancel 结果可以仅带安全身份通知，前提是 native 调用确已返回且不再借用指针；它不能成为对已 retired transfer 新发 cancel 的理由。独立调用引用方案应保持 transfer/session 至本方最后借用消失，或提供等效同步。

### 同步控制请求也是事件处理参与者

`libusb_control_transfer` 自己分配内部 transfer，调用异步 submit，随后通过 `libusb_handle_events_completed` 循环等待，读取结果、free 后才返回。它的内部 callback 设置栈上 completed 标记后停止访问 transfer；源码说明另一个线程可能随后释放 transfer。同步 API 线程也会参与 event handling，不能假定所有库 callback 都只发生在专门 pump 线程。[同步实现](https://github.com/libusb/libusb/blob/87a55632db62c9bdc58cd31d3ccfa673f1bb017f/libusb/sync.c#L36)，[多线程说明](https://github.com/libusb/libusb/blob/87a55632db62c9bdc58cd31d3ccfa673f1bb017f/libusb/io.c#L1142)

timeout=0 是无限等待；外层 Future/timer 到期不能结束真正的同步调用栈。公共 API 没有把内部 transfer 返回给调用者用于直接 cancel。event callback 内调用同步控制 API 会得到 BUSY；不能持有 callback 所需的应用锁再等待这类调用退出。`sync.c` 中处理 `dev_handle==NULL` 的防御分支不构成“并发 close 可安全中止任意同步请求”的公共保证。[控制请求合同](https://github.com/libusb/libusb/blob/87a55632db62c9bdc58cd31d3ccfa673f1bb017f/libusb/sync.c#L72)

### pump 的 timeval、completed 与 interrupt 各自有限

`libusb_handle_events_timeout_completed(ctx, tv, completed)` 的 timeval 控制等待事件的时长，0 表示处理已待办事件；返回 0 可以只是正常等待/处理结束，不是 drain。callback 执行时间和锁等待没有由 tv 自动约束。completed 在取得 event lock 后检查，官方要求其修改遵守 event lock，通常在 callback 中完成；不能随意把另一个线程无同步写的退出布尔变量当 `int *completed` 传入。[等待 API](https://github.com/libusb/libusb/blob/87a55632db62c9bdc58cd31d3ccfa673f1bb017f/libusb/io.c#L2324)，[completed 同步要求](https://github.com/libusb/libusb/blob/87a55632db62c9bdc58cd31d3ccfa673f1bb017f/libusb/io.c#L924)

`libusb_interrupt_event_handler` 只设置 USER_INTERRUPT 并唤醒事件源，不 cancel transfer、不 join 线程，也不抢占正在执行的 callback。传输/回调及非泵业务 native 调用尚未排空时，仍须泵取消完成；这些义务排空后才请求 pump 退出并 interrupt 唤醒。泵自身的 event-handler 调用栈和 context 借用要到实际返回/join 后才归还；整个停止过程保持 context 有效，join 后才 libusb_exit。同步业务调用线程若也在 event handling，必须另计其栈引用。[中断实现](https://github.com/libusb/libusb/blob/87a55632db62c9bdc58cd31d3ccfa673f1bb017f/libusb/io.c#L1941)

## 初始化、open 和关闭的契约边界

| 接口 | 对实现的具体要求 | 相对上轮 |
| --- | --- | --- |
| `libusb_init_context` + NO_DEVICE_DISCOVERY | 给独立 context 在初始化时设置；默认 context 已存在时可复用并忽略本次 options。该选项也禁用 hotplug，所以设备事件仍由外层负责 | 新精化 |
| `uvc_init(&uvc, usb)` | supplied USB context 为借用；adapter 管事件处理和最终 libusb_exit | 继承确认 |
| `libusb_wrap_sys_device` / `uvc_wrap` | 原 fd 有唯一外层 owner；Linux wrapper 不 dup、不在 close 时关闭它。uvc_wrap 已内部 wrap，不双包装 | 继承确认 |
| `uvc_wrap` / internal open | 返回 `*devh` 前会 claim 接口并提交 VC status_xfer；外部 pump 已运行时可有异步副作用。open 不能被当作“无 callback 的原子创建” | 新精化 |
| `libusb_close` | void，NULL 可忽略，但已 free 指针不能重复 close；内部取得 event lock，故“文档 non-blocking”不是硬实时保证。其防御性移除 in-flight 记录不等于取消完成 | 新精化 |
| `uvc_stream_stop/close` | stream cancel 等待及 user callback thread join 不包括 VC status、其他调用栈和 Java owned 副本；存在无界等待 | 继承限制 |
| `uvc_close` | status_xfer 显式 cancel/wait 缺口仍未修；不能单凭 void 函数已被调用生成全局完成回执 | 继承阻断 |
| `uvc_exit` / `libusb_exit` | 先关闭已跟踪设备、停止全部使用者；uvc_exit 不销毁 borrowed USB context。libusb_exit 最终释放假定已经无人访问 context，并不 join caller 创建的 pump | 新精化 |

对应固定证据：[init option](https://github.com/libusb/libusb/blob/87a55632db62c9bdc58cd31d3ccfa673f1bb017f/libusb/libusb.h#L1632)，[default context 复用](https://github.com/libusb/libusb/blob/87a55632db62c9bdc58cd31d3ccfa673f1bb017f/libusb/core.c#L2565)，[uvc borrowed context](https://github.com/libuvc/libuvc/blob/448d78b9aa4f54172c1ca5b5d1b4a6b9b93f7957/src/init.c#L94)，[open 的 status 提交和返回 handle](https://github.com/libuvc/libuvc/blob/448d78b9aa4f54172c1ca5b5d1b4a6b9b93f7957/src/device.c#L358)，[close](https://github.com/libusb/libusb/blob/87a55632db62c9bdc58cd31d3ccfa673f1bb017f/libusb/core.c#L1607)，[exit 的静止前提](https://github.com/libusb/libusb/blob/87a55632db62c9bdc58cd31d3ccfa673f1bb017f/libusb/core.c#L2782)。完整签名、返回码和逐条锚点见 JSON。

open 失败、请求取消或授权代次失效后，迟到的 handle/异步副作用仍需 owner 负责清理。适配器不能发布旧代结果，也不能在 open 栈仍用 context/fd 时强制 free。公开 uvc_wrap 没有提供有界取消 API；具体失败/迟到清理路径在本轮仍未得到运行证据。原协议模型的原子 open 不覆盖它。

## 来源核对及未完成范围

继承锁为 `android/uvc-sim/provenance/routes/upstream-driver/inputs.lock.json`，原文件 SHA-256：`8ff898d5acce58c697f0b3326555cfdfb65c466572f5e9c1ec234a9b72e56ae4`。本轮只读核对父 route cache 的 49 项字节数/SHA-256，并核对有源码 blob 记录者的 Git blob SHA-1；选定 9 源的每个 JSON 锚点均在给定行范围内存在。source `cache_path` 相对兄弟 worktree 的 `android/uvc-sim/build/routes`，统一带 `upstream-driver/` 前缀；`input_id` 对应继承锁的 `name` 字段。没有复制源文件到本 repo。

这些检查验证固定文本身份和精确引用，不证明 C 编译调用图、C 内存模型、runtime 线程安全、JNI 接线、故障恢复时限、硬件兼容或许可全覆盖。submit-before-return 的推断已经另一位只读审查者独立核对，但仍是源码层面允许的时序。真实实现须分别收齐 status/image/control、所有 callback 与所有 native 调用栈引用；随后 native drain 还须与 Java/像素 owned 缓冲清理组合，才能由主集成判断 full drain 和产品切换门禁。
