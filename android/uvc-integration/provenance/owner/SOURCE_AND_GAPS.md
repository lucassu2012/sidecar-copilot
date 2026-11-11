# USB owner 的固定来源与实际接线缺口

本轮工作树基线 `ff46084debcfef8b29420fdb786c5ddfc3e19981`。交付范围是原创 Java 权限/连接 owner 和受控 PlatformUsbPort，不是 `UsbManager`、PendingIntent、BroadcastReceiver 或 UsbDeviceConnection 的 Android 实现。这里静态复核固定来源和实现边界，不运行旧模型或测试 suite，不操作模拟器/设备，不抓取网络或引入 runtime binary。

本轮实施说明与运行结论分别由本目录 [README.md](README.md)、[VALIDATION.md](VALIDATION.md)、[validation.json](validation.json) 绑定；合成测试结果只能用于相应 Java 代码和受控 port。本文不预报尚未生成的通过数，也不把父模块或公共合同的历史结果相加。

## 固定输入及本轮复核

来源继续使用 [Android 接口矩阵](../../interface-contract/ANDROID_CONTRACT.md) 与 [android-contract.json](../../interface-contract/android-contract.json)；该证据原创建基线 `3dbbcb1…`，不与本轮 `ff46084…` 混写。API 35/Android 15 参考源码不是目标 OEM 固件指纹：

| 固定源 | commit | 解码源码 SHA-256 |
|---|---|---|
| frameworks/base 的 UsbManager.java | `4e43ad12e1b211f15152e9c5b16b0fe88e6b93f3` | `e3e05f4b5441e640896b0d7b36b710a6d8a31142c5c667f9ed65fc0119ca7603` |
| 同提交 UsbDeviceConnection.java | 同上 | `dd42714a8708b72557d084cc4daed1e4e02bfec2526733c39d952956d1eb6808` |
| 同提交 android_hardware_UsbDeviceConnection.cpp | 同上 | `76ca328e2892d5357c908024b87065884576067e22f7066d4c1d588aff47a133` |
| system/core 的 libusbhost/usbhost.c | `fc7bc8c4bfb4c6095e34ea784509dae56f25b486` | `fd339a0f1768450a84caff86b6a3d00c858c9c62edb9d250e311c9afdf127c0d` |

本轮只读复用兄弟 `sidecar-uvc-route/android/uvc-sim/build/routes/platform` 缓存，按继承 lock 核验原始大小/SHA-256，再核验 Base64 解码后的 SHA-256、Git blob ID 与接口锚点，4 源一致。原始缓存哈希、固定 URL 和行号仍由上述 JSON/[输入锁](../../provenance/routes/platform/INPUT_LOCK.json) 保存，不重新下载或生成来源副本。Git blob 计算是内容身份，不额外证明整个 AOSP tree 成员关系。

继承 `android-contract.json` 的 SHA-256 为 `b07f3fdccb60cdb8a5852419f34a6d001c852d4656cd7e9596e20d38fe1fe58b`；`license-evidence.json` 为 `3ede09b71e69b10996687788bd842889ab1ad54508b709755948171eaa31be44`。父目录 [contract-baseline/manifest.json](../contract-baseline/manifest.json) 中 9 份公共快照也按原大小/哈希复核一致；不再复制或改写主线类型。哈希匹配不证明运行行为或设备兼容。

## PlatformUsbPort 与 Android API 的分界

已只读核对 [PlatformUsbPort.java](src/local/sidecar/copilot/usbowner/PlatformUsbPort.java) 与 [UsbConnectionOwner.java](src/local/sidecar/copilot/usbowner/UsbConnectionOwner.java)。port 的 API 为 `requestPermission(PermissionRequest, PermissionCallback)`、`isCurrentAttachment(Binding)`、`hasPermission(Binding)`、`openConnection(Binding)`，连接侧为 `getFileDescriptor()`/`close()`；owner 负责其上方身份、请求和资源账本。这些方法没有 Android import 或系统调用，不能仅按名字等同 SDK 行为。

| 本轮可检查的协议边界 | 固定 Android 源码实际承诺 | 仍须真实 bridge 兑现 |
|---|---|---|
| request nonce 与 Selection 关联、至多一次消费 | UsbManager L1035–1063 仅把 caller 提供的 PendingIntent 交给服务，结果添加“传入该次调用的 device”和批准布尔值。 | 构造不可串用的 PendingIntent/receiver 关联，验证结果来源、载体、目标和适用 flags/mutability；公开 extras 没有 Sidecar nonce、epoch、revision。合成 token 匹配不能证明真实广播抗伪造/抗串用。 |
| 当前 attachment epoch / authorization revision | attach/detach 事件携带 EXTRA_DEVICE；临时 USB 授权在断开时结束。 | 可靠地维护观察与自有代次，处理迟到/重复/丢失/乱序、进程重建及身份不明。VID/PID、deviceName、Java 对象引用、FD 数值均不能单独当会话身份。 |
| hasPermission 与 open 前后复核 | hasPermission 是当前调用方权限观察，可来自默认应用关联；openDevice 按 deviceName 打开，可能返回 null。 | 真实平台调用与前后附件/权限观察；它们是独立调用，不能宣称原子地消除所有断连/权限变化窗口。grant=true 不自动 open/start，也不是产品运行确认。 |
| owner 签发借用、取 FD 与关闭 | Java getter 返回借用的 native FD，未打开为 -1；正常 JNI open 复制 PFD 一次，随后 connection 持有副本。 | 将实际 connection 对象纳入 owner，保护 getter 和 native 使用期间的借用；FD 0 合法，非负也不保证仍授权/连接/可采集。native 不能 close 原 connection 的 FD。 |
| stop 后拒绝新借用、native drain 后唯一关闭 | Java close 有 mLock/重复关闭守卫，getter 没有同锁保护；公开 SDK 不提供自有 native borrowers 的计数或排空握手。 | 真实 owner 必须先关闭借用准入，等实际调用/回调及 native producer 完成后再 close；不把一次 Java close 调用记录当 native 或应用像素全部排空。 |

具体函数锚点和失败条件沿用继承矩阵，不复述整章。尤其保留：API 不保证权限结果到达时设备仍连接或与其他请求按指定顺序到达；通用 usbhost getter 还可经 reopen_writeable 替换 FD，不能把正常 JNI 路径概括为整数永不变化；固定平台失败清理中的两个 close 站点也不支持“框架全部失败路径已证只关一次”的扩大结论。

## 必须在本轮实现中区分的证明对象

| 实际 owner 入口 | 已落实的 Java 行为 | 未被该行为证明的部分 |
|---|---|---|
| 构造与 `requestPermission` | 每个 owner 固定一个 Binding、一个绑定相符的 CaptureSession、至多一次权限请求/打开尝试。调用 port 前登记 PermissionRequest；允许同步、重复或迟到结果，按完整 Selection+nonce 至多消费一次。 | Selection 的 epoch/revision 由外层提供，owner 不生成系统附件真相或全局代次。跨 owner 的同 Binding/同 nonce 唯一性及请求命名空间仍由外层保证；本类不是 PendingIntent/receiver 认证器。 |
| `open()` 的请求门禁 | 若本 owner 已发起 request，须 request 调用成功退栈且匹配 grant 已消费，才能显式 open；若没有发起 request，可显式利用已有系统许可并重新核对。grant 回调不自动调用 open/start。 | 成功退栈与合成 grant 不是 Android 授权证明；拒绝、请求抛异常或随后失效仍须走主集成停止链。已有系统许可不等于无需产品确认。 |
| open/FD 观察 | 在 openConnection 前、返回后及 getFileDescriptor 后分别检查当前附件和 hasPermission。整个 `openOperations` 与逐次 `platformCalls` 保护在栈阶段；迟到 connection 先登记到原 owner，再判断能否使用。 | 这些复核不构成平台原子事务；最后一次观察之后仍可变化，必须依赖外部失效接线。getter 只在 open 阶段调用，成功后缓存 FD；不是每次 borrow 都再调用 SDK getter。 |
| `borrow` / `returnBorrow` | 仅对已就绪且准入开放的 connection 签发缓存 FD 的私有构造 BorrowLease，以对象身份登记；外来 capability 拒绝，重复归还幂等。 | `BorrowedFd` 只是公开数据，不能自行构造一份等值描述符就取得或归还借用。真实使用者必须覆盖其所有 native 使用时段；整数相同不代表同一个 owner。 |
| `requestStop(key)` | owner 先关本地准入，在 owner 锁外同步调用受信且按合同非阻塞的 session.requestStop；该 session 调用正常返回并记录 nativeStopCompleted 时，两方准入都关闭。不同 canonical key 拒绝；仅 connection.close 延后调度。 | owner 方法返回 void，且会捕获 session 异常，故“方法已返回”不能单独当双门关闭凭证；异常锁存证据无效并保留失败。root 须先撤销 publication；注入方法阻塞/谎报是信任契约破坏，本类不能强制抢占。 |
| `refreshNativeDrain` / `ownerEvidence` | refresh 只向构造时绑定的 session 拉取证据，检查同 Binding/key、版本及终态一致性；ownerEvidence 只读本地快照。native pending 后须再次显式 refresh，无自建轮询线程。 | 不接受调用者任意 drained DTO，但仍依赖 producer 诚实且终态冻结；单靠 DTO 校验不能证明未漏登记的真实访问。 |
| connection close | native terminal 已被接受、平台/open/permission/producer 调用退出且所有 lease 归还后，才按唯一尝试调用 close。close 成功返回前不报关闭；open 抛 acquisition-unknown 或 close 抛 outcome-unknown 都保持 pending，不凭超时或重复 close 解除。 | close 返回是受控 port 的承诺，不是本类独立观察到 OS 已关闭。真实 bridge 必须建立不会丢失已取得 handle 的 acquisition/exception 所有权合同；安全 pending 本身不证明有界停止。 |

close 的线性化依据是先前已接受、按公共合同不可逆冻结的 NativeEvidence。开始 close 后不再向 producer 拉取新证据，以免跨越这次关闭决定；因此不能宣称 owner 能检测 producer 在终态之后非法复活。终态前的版本/内容检查与终态后的信任前提必须分开。OwnerEvidence 的公共 borrows 字段只表示已签发 lease；平台调用栈等另有本地诊断计数并作为关闭条件，不伪装成额外产品状态。

`invalidate(binding, kind)` 是外部事件输入，能检查错误/过期 Binding 不影响当前 owner，但不自行提供系统广播、权限撤销、Activity/service lifecycle 或 root gate 的事件源。主集成须先同步撤销相应发布/启动准入，再推进停止和 owner 排空；没有这条实际接线时，owner 的内部失效不证明 UI、preview、队列或录制发布已停止。

平台/producer/failure sink 调用都在 owner 锁外；清理 Executor 必须只非阻塞入队，不 inline 执行。inline/reject 会保留协议失败，不在停止调用栈上补做 close；FailureSink 的抛异常被隔离，但它仍必须遵守有界元数据接收合同。

fake connection 的 open/getFD/close 次数与受控返回可以验证 owner 账本，但不是 OS 句柄生命周期、Binder 调用或 USB kernel 状态。应用 BufferEvidence、开始确认、遮盖/预览/队列和真实 lifecycle 策略未由这两个类实现。

## 最小下一步：实际 Android 桥的前提

1. **绑定现有 App，而不是继续扩大 SPI 的假设。** 明确实际 target SDK、manifest、Activity/service/前台执行策略及 receiver 注册/撤销位置；审查进程重建、前后台切换、取消与销毁如何使旧 request/selection/run 失效。当前 4 个固定 USB 源不覆盖这些 App 前台/服务资格或生命周期实现，不凭它们宣布合规或可在后台运行。
2. **实现并审核权限关联。** 将 request nonce、Selection/epoch/revision 与真实 PendingIntent 绑定，明确 Intent/PendingIntent 匹配身份、flags、可变性、receiver 暴露和结果来源检查；验证 callback 前后的关联且至多消费一次。只把 nonce 放进 extras 不是已经证明的唯一请求身份方案；这里不指定尚未核对的 flags 配方。
3. **实现实际 connection owner。** 使用真实 UsbManager/UsbDeviceConnection，处理 null/异常/迟到 open，绑定自己的 owner/generation；getFD 纳入借用保护，停止后拒绝新 borrow。缺真实 native 完成或 close 结果不确定时保留 pending/隔离，不用猜测裸 FD、重试未知 close 或假回执完成停止。
4. **接通主集成开始与发布链。** explicit source/wire confirmation、start acknowledgment、前台/lifecycle 失效与 publication gate 必须使用同一 Binding/run；应用 queue/in-flight/preview 的 BufferEvidence 独立产生。OwnerEvidence、NativeEvidence、BufferEvidence 齐全后，StopBarrier 才能检查组合回收，不能从 owner close 推导像素清零或自动重连。
5. **在既有准入条件满足后验证指定实机组合。** 固定 App/artifact、OEM build/API/ABI、手机/卡/固件/Hub/供电/线缆与输入模式，观察真正权限流程、断连/重连、FD/线程/回调退出、后台/前台变化和晚到结果。Java suite 不能替代这些设备事实；本轮不执行这些步骤，也不增加 PRD 状态或测试编号。

## 依赖与许可范围

本轮新增的是原创 Java owner/SPI/fixture，没有新增第三方运行依赖或上游二进制；读取 AOSP Apache-2.0 参考源码不等于复制其 JNI/libusbhost 到 App。实际 SDK 调用桥与复制/分发平台实现应分别审查。

父模块 [GAPS_AND_LICENSE.md](../GAPS_AND_LICENSE.md) 与 [许可证据](../../interface-contract/license-evidence.json) 的门禁原样继承：libusb/libuvc 的真实 status teardown、native/JNI 和构建/分发闭包未由 owner 解决；JPEG 仍需绑定实际实现，UAC 四 ABI 对应源码/授权链及旧封装来源未决。已确认的生成器/模板/YAML 进展不会退回“生成器未知”，也不会因 Java 测试通过自动升级为重生成、许可闭包或硬件可用证据。无发布、部署或远端推送；最终本地提交由根统一完成。
