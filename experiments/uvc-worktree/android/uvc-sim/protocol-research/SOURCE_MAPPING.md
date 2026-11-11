# UVC 生命周期协议：固定源码映射与实现边界

工作区基线 `c8874f6f39dddcd39a63289787fc3ba7a42b2cad`，核查日期 2026-11-10。本轮只读继承的固定文本源码、证据和 JVM fixture；不联网、不打开设备、不加载 native。本文与 [source-baseline.json](source-baseline.json) 不修改真实驱动，也不宣布真实采集可用。

同目录的 `protocol_model.py` 是原创 Python 标准库事件模型：FD、授权、线程、请求和 JNI 都是显式输入的模型对象；它不是 JNI bridge，也没有执行 libusb/libuvc。模型通过只能说明所注入事件下的协议约束成立，不能说明上游已实现这些约束。

## 证据分层与固定输入

下文“源码事实”指这些固定文件的静态观察；“模型规则”指为安全协议选择的抽象及前提；“未修复”指本轮没有替上游补丁、编译或实测的条件。

| 来源 | 固定版本 | 复用证据 |
|---|---|---|
| libusb 官方仓库 | v1.0.30，`87a55632db62c9bdc58cd31d3ccfa673f1bb017f` | [输入锁](../provenance/routes/upstream-driver/inputs.lock.json)、[静态报告](../provenance/routes/upstream-driver/REPORT.md) |
| libuvc 官方仓库 | v0.0.9，`448d78b9aa4f54172c1ca5b5d1b4a6b9b93f7957` | 同上；不将旧 vendor fork 的行为混入此版本 |
| AOSP frameworks/base | API 35 参考，`4e43ad12e1b211f15152e9c5b16b0fe88e6b93f3` | [输入锁](../provenance/routes/platform/INPUT_LOCK.json)、[静态证据](../provenance/routes/platform/static-evidence.json) |
| AOSP system/core | `fc7bc8c4bfb4c6095e34ea784509dae56f25b486` | 同上；不是目标手机固件的软件指纹 |
| 现有项目接口 | 上述工作区基线 | [adapter-plan](../provenance/routes/adapter-plan/README.md) 与下文列出的 Java 文件 |

清单保存本地基线文件的字节数/SHA-256、本文哈希，以及选定官方输入的固定 URL、原始缓存哈希、解码源码哈希和 Git blob ID。本轮复核缓存来自旧 `sidecar-uvc-route` 工作区，严格按继承锁只读校验，没有复制或刷新缓存。libusb/libuvc 的源文件 blob 已由前轮非截断 tree 核对；AOSP blob 是内容身份计算，不是独立完整 tree 成员证明。哈希锁定此次依据，不证明设备兼容或完整供应链。

## 授权、FD 与唯一释放

| 责任 | 固定源码事实 | 模型规则与现有缺口 |
|---|---|---|
| 授权的连接身份 | [UsbManager L1035–1051][usb-manager] 的 USB 授权持续到本次断开；视频类还受适用的 CAMERA 权限约束。`openDevice` L867–882 可能返回 null。 | 将 device identity、attachment generation、authorization revision、permission token 绑定为不可变身份。模型的 `ConnectionIdentity` 只验证输入形状/相对代次，不会询问系统权限；调用方提供真实且当前授权是前提。[AndroidUvcDiscovery.java](../android/local/sidecar/copilot/uvc/AndroidUvcDiscovery.java) L10–35 只有只读候选与权限快照，无授权回调、open 或连接代次管理。 |
| connection 自有 FD | [UsbDeviceConnection JNI L49–61][usb-jni] 在打开时复制 PFD 一次；L79–87 的 get fd 返回已有 FD。[usbhost.c L358–361][usbhost] 由 connection 关闭其持有 FD。 | `getFileDescriptor()` 的返回值是借用，不是一次新的 dup 或所有权转移。不能靠整数 FD 判定附件身份；它可被 OS 复用。当前 `UvcDriver.open(String attachmentId, Format)` 没有真实 connection、授权绑定或 FD owner。 |
| libuvc 借用现有 FD | [libuvc device.c L260–298][uvc-wrap] 的 `uvc_wrap` 内部调用 `libusb_wrap_sys_device`。[libusb core.c L1376–1388][usb-wrap] 要求 system FD 在 close 前保持打开，而且不会替调用方关闭；[linux_usbfs.c L1448–1449、1488–1492][linux-fd] 保留原 FD，`fd_keep=1`。 | 一个 owner + 一条借用链 `connection → uvc_wrap → libusb`；不能再独立 wrap 同一 FD 制造第二条无协调的释放路径。模型当前只模拟这一“单 owner、native 借用 opaque token”路径。正常释放 native 借用、事件处理及 context 后，owner 才 close；任何未排空借用均阻止提前 close。 |
| 主动 dup 的候选方案 | 固定 `uvc_wrap` 路径本身没有为 adapter dup。 | 若未来选择主动 dup，adapter 必须单独记录原 connection FD 与额外副本，各由其唯一 owner 释放，分别处理 dup/wrap 失败。当前模型**没有证明双 FD 路径**。dup 不能延续断开后的许可，也不能绕过用户停止/撤销；额外副本存活不等于继续采集有授权。 |
| 部分打开失败 | `uvc_wrap` L291–293 的分配后访问未检查；后续内部打开还有接口、status 请求等资源。 | 模型的 open/context/native 失败点只验证抽象资源账本与反向释放，未覆盖每个真实 malloc、claim、thread、submit 失败位置。真实 bridge 必须为每个成功获取的资源建立释放责任，不能把 Java open 抛异常解释为上游一定无泄漏。 |

## status、image、control 的完成不能互相替代

三类请求分别记账；每次成功提交有自己的身份和完成义务。提交失败不产生“等待该次完成”的义务，但仍需释放此前已取得资源。`transfer_id` 重用后必须带独立 submission 代次，旧完成不能结清新请求。

| 类别 | 固定源码事实 | 模型要求及未修复条件 |
|---|---|---|
| status interrupt | [device.c L358–373][status-open] 在 open 中建立并提交 `status_xfer`；[L2116–2144][status-callback] 在 COMPLETED/TIMED_OUT/STALL/OVERFLOW 后重提。 | 关闭锁存必须先禁止重提，随后对已提交 status 请求取消并等待终态/回调退出。关闭时恰逢成功回调，也不能开启新提交。模型的 `kind="status"` 与关闭后拒绝 resubmit 是所需协议，**不是原源码现状**。 |
| status close 缺口 | [device.c L1933–1958][uvc-close] 关闭 stream、释放接口、关闭 libusb handle，然后 `uvc_free_devh`；L1917–1918 直接 free status transfer。该检查路径没有明确 status cancel + completion drain。[io.c L1325–1326][transfer-free] 禁止释放活动 transfer。 | **未修复的上游阻塞条件。** 不能用 `uvc_stream_stop`、清空 Java/status 回调函数或强行 libusb_close 代替 status 排空。公共 libuvc API 没有开放 status_xfer 所有权；未来需可追溯源码补丁与回归证据，不能靠访问私有布局绕过审查。这里是静态生命周期缺口，不是已复现漏洞结论。 |
| image stream | [stream.c L1554–1594][stream-stop] 清 running、逐个 cancel，再条件等待 transfer 表清空并 join 用户回调线程；没有有界等待。L1586 仍有 camera-side stop TODO。 | 模型只在 image 完成事件及回调退出都记账后释放。保留不返回/无完成场景；不能把等待超时伪装成上游 stop 成功，不能据 stop 返回证明实体卡、HDMI 或所有 camera-side 状态。native callback 内不调用 stop/close。 |
| control/probe | [stream.c L249–255][control-call] 使用同步 control transfer，timeout=0；[L636–647][control-probe] 的 probe SET/GET 返回值未处理。[sync.c L90–92、103–171][control-sync] 定义 0 为无限等待，内部提交后等待结果才 free。 | 未修复：控制请求截止时间/失败传播以及探测返回码。现有同步调用没有暴露内部 transfer 给 adapter 取消；模型明确区分异步 control 完成与 `blocking_call` 的真实返回，不能凭外层 timer 合成返回、释放栈上数据或开始新 run。未来有限超时/异步可取消方案须通过实际源码补丁建立。 |
| 所有 cancel 返回 | [io.c L1573–1579][cancel] 明确 cancel 立即返回不表示完成；L1603–1623 的 NOT_FOUND 也可表示已完成或已在取消。 | `cancel_result(OK/NOT_FOUND/ERROR)` 仅是取消调用结果，均不自行结清已登记提交。完成可能已排队但回调尚未运行。`NO_DEVICE` 的终态完成与 cancel 返回的错误也不能混为一谈。只有对应已登记操作的真实完成/同步返回及必要回调退出能推进排空。 |

`libusb_close` 不是缺失完成的替身。[core.c L1557–1593][usb-close] 对仍在 flight 的 transfer 发出诊断并从列表摘除/置空 handle。同步内部等待还包含关闭后检测（[sync.c L49–69][sync-wait]）；这不证明调用方可以与活动调用并发 close，尤其不能修复 libuvc status_xfer 的生命周期。

**取消 API 调用本身也借用 transfer。** [io.c L1609–1639][cancel-stack] 在进入锁、检查状态、调用 backend 及返回路径仍会访问 transfer；另一线程的完成回调已退出，不足以允许抢先 free。模型新增 `cancel_call_enter/cancel_call_return` 单独保留这段调用栈责任，必须既结清完成/回调，又等取消调用返回才释放。`cancel_result` 便捷通知只抽象“原子调用已经返回”的情况，不能用它声称真实 cancel 在栈竞态已被表示。cancel intent、cancel API 返回与 transfer terminal completion 是三个不同观察。

## event pump、超时与有限性

外部 libusb_context 交给 libuvc 时，[init.c L94–118、159–161][uvc-init] 明确由调用方处理事件，`own_usb_ctx=0` 不启动 libuvc 自管事件线程。模型以显式 `pump_start/pump_stop` 事件表示这一责任，**没有真实线程、锁、poll、JNI 附着或调度保证**。真实实现须持续处理各类完成，不能先停 pump 再等待那些依赖 pump 的回调；也不能阻塞 event callback 去 join 自己或等待控制工作完成。[libusb io.c L905–920][event-lock] 的 completed API 处理事件锁与完成观察竞态，不能只用一个无同步的布尔值代替。

模型把异步 callback 的进入和退出分开，采取保守的整段回调借用屏障；这不是对 libusb 每个内部 free 时点的逐指令模拟。同步 libusb control 自身也能处理 events（sync.c L49–69），所以“专有 pump”表示 adapter 的生命周期责任，不表示上游永远只有一个线程可进入事件处理。

请求方 deadline 与操作实际终态是不同事件。`control_timeout` 只触发停止意图/关闭发布准入；无 completion、callback 不退出或同步调用不返回时，`try_release` 允许一直返回 pending，并保留仍被借用的 context/FD/请求。模型不能证明 OS 最终响应、线程可杀死或 bounded native close。pending 是内部协议结果，不增加 PRD 产品状态；也不能转成“已暂停/已关闭”、自动重连或静默更换来源。

未来真实关闭顺序需要先阻止新提交/重提，再使 status/image/control、native 回调栈与 native 拷贝工作全部排空；之后释放 stream/device/UVC 引用，停止并 join 由 adapter 拥有的 event worker，释放其 libusb context，最后由明确 owner 关闭 FD。若多设备共享 context/pump，须另建共享所有权方案；当前单连接模型未覆盖。任何一步不确定就保留对应资源和 pending，不能按上述文字直接调用尚有缺口的原版 `uvc_close`。

## 迟到回调、像素与三道独立屏障

1. **发布准入失效**：旧 lease/generation 不能再发布或读取 preview；不依赖 I/O、clock 或 worker 返回。[CaptureGate.java](../src/local/sidecar/copilot/uvcsim/CaptureGate.java) L21–30 只提供内存准入屏障。
2. **native 已静止并释放**：各类已提交操作、执行中的同步 control/取消调用、native callback/拷贝及借用引用均已结清，才释放它们可访问的 native 对象、context 与 FD。Python 的 `native_drain_confirmed` 是模型账本结论，不能当作真实 JNI 证书。
3. **应用自有像素清零/释放**：已转交的 Java 队列、worker 当前帧、preview 与迟到待投递副本分别清零并结清所有权。此屏障可以晚于 native 排空；前两道都不能代替它。

固定 [stream.c L1373–1378、1388–1439][borrowed-frame] 会重复使用 `strmh->frame`、重分配并覆盖其 data；[libuvc.h L480–486][callback-contract] 警告回调中不可调用 uvc_*。真实 bridge 应在有效借用期内做受预算约束的独占拷贝，借用指针/DirectByteBuffer 不能排进 Java 异步队列后越过回调或 stream close 生存。解码、stride、wire encoding 与交付 pixel layout 还需单独设计；不能把 MJPEG 当现有紧凑 RGBA。

模型的 `copy_for_java` 只是 Python bytearray 拷贝与身份标记；不证明 JNI 分配、global reference、native scratch、解码器或内存预算。`dispatch_java` 对停止后或旧 session 的**已自有副本**拒收并清零；只带旧身份的 Java 副本可以在 native release 后到达，仍不得重新取得 native 指针。真正迟到且还需读取原 native 内存的回调，必须计入第二道屏障而阻止释放。拷贝失败不能泄漏借用或虚构已转交所有权。

[UvcDriver.java](../src/local/sidecar/copilot/uvcsim/UvcDriver.java) L9–10 声明像素独占转交，L47–48 允许已排队的旧回调在 close 后抵达；没有三类请求、FD、event pump、JNI 引用或像素排空 receipt。[UvcCoordinator.java](../src/local/sidecar/copilot/uvcsim/UvcCoordinator.java) L237–245 先失效 lease 再异步通知 sink cancel；L294–299 的 StopReceipt.confirmed 仅依据该 operation 的 device worker tasks 与 driver.close 成功，**不包含 sinkControls、像素 worker 或未来 JNI 队列**。`awaitWorkerIdle` L307–315 甚至只表示任务数归零。

[FramePipeline.java](../src/local/sidecar/copilot/uvcsim/FramePipeline.java) L121–129 的 cancel 清除排队帧和 preview，但不等待 L130–162 已取走的当前帧；该帧到 finally 才清零释放。driver stop/close 确认、pipeline.cancel 返回、preview 不可见都不能宣称全像素已清零。未来如需全排空凭证，必须显式观测每个实际 owner；不得把一次关闭确认改名来充当这一证明。现有 640×360 RGBA、队列 2、7 MiB owned + 受控单生产者 ≤1 MiB 只是合成 fixture 预算。

## 模型能证明的边界与后续门禁

同目录模型负责 `open/submit/request_stop/cancel_call_enter/cancel_call_return/cancel_result/queue_completion/callback_enter/callback_exit/return_blocking_control/try_release` 的账本；像素另用 `copy_for_java/dispatch_java/drain_java`。调用序列和返回值均由合成调度者提供。这里引用职责名称，不把正在实现的模型文件哈希冒充既有工作区基线；该模型的版本/测试记录由其交付清单负责。

可模拟提交失败、取消与完成乱序、NOT_FOUND 但完成待投递、关闭时 status 重提、no-response、安全 pending、重复停止、旧 submission/FD token 复用、native 完成后迟到 Java 自有副本，以及设备静止但像素仍未排空。不能由这些合成结果推出真实权限回调可靠、USB URB 已回收、native 线程已终止、callback/JNI 异常处理正确、真实格式兼容或设备能够恢复。

后续真实接入仍须完成可追溯的 status 关闭修补、控制超时/返回码与分配失败处理、实际 native/JNI 所有权和排空协议、隔离 Android 构建/ABI/打包核验、全部实际分发来源及许可核对，之后才可进入相应授权实机验证。本轮没有关闭任何这些门禁，没有增加 PRD 状态或测试编号。

许可分层：本轮原创模拟源码不复制或运行上游实现，引用源码位置也不是接入批准。继承核查的 libusb 运行时头为 LGPL-2.1-or-later；libuvc 基础源码主要是 BSD-3-Clause，内含 utlist 的独立许可，生成 ctrl-gen 来源等仍需完整核对。JPEG 可选解码依赖有独立来源/构建/许可门禁；既有 UAC 四 ABI 授权链未决且此视频路线不提供 UAC。AOSP 所查文件有 Apache-2.0 头，调用系统 SDK 与复制/分发其 JNI/HAL 实现不同。原生源码构建、补丁和实际分发审查均属后续；LGPL/BSD/JPEG/UAC 门禁不会因 Python 模型通过而解除。

## 只读复核

在本工作区根目录执行下列 PowerShell 命令。它只校验本地文件和既有缓存，不执行上游脚本，不导入 native，不下载、不写报告。缓存位置可以显式改为等内容的只读缓存；缺失或哈希不符就失败，不自动刷新锁。清单本身应通过 Git/review 固定，不能用它自己的哈希声称自证真实性。

```powershell
@'
from pathlib import Path
import base64, hashlib, json
root = Path.cwd().resolve()
cache = root.parent / "sidecar-uvc-route/android/uvc-sim/build/routes"
manifest = json.loads((root / "android/uvc-sim/protocol-research/source-baseline.json").read_text(encoding="utf-8"))
def checked(base, rel, size, sha):
    target = (base / rel).resolve()
    if not target.is_relative_to(base.resolve()):
        raise RuntimeError("path escapes declared root: " + rel)
    data = target.read_bytes()
    if len(data) != size or hashlib.sha256(data).hexdigest() != sha:
        raise RuntimeError("content mismatch: " + rel)
    return data
for entry in manifest["local_inputs"] + [manifest["mapping_document"]]:
    checked(root, entry["path"], entry["bytes"], entry["sha256"])
for entry in manifest["official_inputs"]:
    raw = checked(cache, entry["cache_path"], entry["wire_bytes"], entry["wire_sha256"])
    data = base64.b64decode(raw, validate=True) if entry["encoding"] == "base64" else raw
    blob = hashlib.sha1(b"blob " + str(len(data)).encode() + b"\0" + data).hexdigest()
    if hashlib.sha256(data).hexdigest() != entry["source_sha256"] or blob != entry["git_blob_sha1"]:
        raise RuntimeError("decoded source mismatch: " + entry["cache_path"])
    lines = data.decode("utf-8").splitlines()
    for anchor in entry["anchors"]:
        if not (1 <= anchor["start"] <= anchor["end"] <= len(lines)):
            raise RuntimeError("invalid source line span")
        excerpt = "\n".join(lines[anchor["start"] - 1:anchor["end"]])
        if anchor["contains"] not in excerpt:
            raise RuntimeError("missing source anchor: " + anchor["label"])
print("PASS: local baseline, mapping and fixed source bytes/anchors; read-only")
'@ | python -B -X utf8 -
```

[usb-manager]: https://android.googlesource.com/platform/frameworks/base/+/4e43ad12e1b211f15152e9c5b16b0fe88e6b93f3/core/java/android/hardware/usb/UsbManager.java#1035
[usb-jni]: https://android.googlesource.com/platform/frameworks/base/+/4e43ad12e1b211f15152e9c5b16b0fe88e6b93f3/core/jni/android_hardware_UsbDeviceConnection.cpp#49
[usbhost]: https://android.googlesource.com/platform/system/core/+/fc7bc8c4bfb4c6095e34ea784509dae56f25b486/libusbhost/usbhost.c#358
[uvc-wrap]: https://github.com/libuvc/libuvc/blob/448d78b9aa4f54172c1ca5b5d1b4a6b9b93f7957/src/device.c#L260-L298
[usb-wrap]: https://github.com/libusb/libusb/blob/87a55632db62c9bdc58cd31d3ccfa673f1bb017f/libusb/core.c#L1371-L1407
[linux-fd]: https://github.com/libusb/libusb/blob/87a55632db62c9bdc58cd31d3ccfa673f1bb017f/libusb/os/linux_usbfs.c#L1448-L1492
[status-open]: https://github.com/libuvc/libuvc/blob/448d78b9aa4f54172c1ca5b5d1b4a6b9b93f7957/src/device.c#L358-L379
[status-callback]: https://github.com/libuvc/libuvc/blob/448d78b9aa4f54172c1ca5b5d1b4a6b9b93f7957/src/device.c#L2116-L2144
[uvc-close]: https://github.com/libuvc/libuvc/blob/448d78b9aa4f54172c1ca5b5d1b4a6b9b93f7957/src/device.c#L1907-L1958
[transfer-free]: https://github.com/libusb/libusb/blob/87a55632db62c9bdc58cd31d3ccfa673f1bb017f/libusb/io.c#L1313-L1326
[stream-stop]: https://github.com/libuvc/libuvc/blob/448d78b9aa4f54172c1ca5b5d1b4a6b9b93f7957/src/stream.c#L1554-L1594
[control-call]: https://github.com/libuvc/libuvc/blob/448d78b9aa4f54172c1ca5b5d1b4a6b9b93f7957/src/stream.c#L249-L255
[control-probe]: https://github.com/libuvc/libuvc/blob/448d78b9aa4f54172c1ca5b5d1b4a6b9b93f7957/src/stream.c#L636-L647
[control-sync]: https://github.com/libusb/libusb/blob/87a55632db62c9bdc58cd31d3ccfa673f1bb017f/libusb/sync.c#L90-L171
[cancel]: https://github.com/libusb/libusb/blob/87a55632db62c9bdc58cd31d3ccfa673f1bb017f/libusb/io.c#L1573-L1623
[cancel-stack]: https://github.com/libusb/libusb/blob/87a55632db62c9bdc58cd31d3ccfa673f1bb017f/libusb/io.c#L1609-L1639
[usb-close]: https://github.com/libusb/libusb/blob/87a55632db62c9bdc58cd31d3ccfa673f1bb017f/libusb/core.c#L1557-L1593
[sync-wait]: https://github.com/libusb/libusb/blob/87a55632db62c9bdc58cd31d3ccfa673f1bb017f/libusb/sync.c#L49-L69
[uvc-init]: https://github.com/libuvc/libuvc/blob/448d78b9aa4f54172c1ca5b5d1b4a6b9b93f7957/src/init.c#L94-L161
[event-lock]: https://github.com/libusb/libusb/blob/87a55632db62c9bdc58cd31d3ccfa673f1bb017f/libusb/io.c#L905-L920
[borrowed-frame]: https://github.com/libuvc/libuvc/blob/448d78b9aa4f54172c1ca5b5d1b4a6b9b93f7957/src/stream.c#L1373-L1439
[callback-contract]: https://github.com/libuvc/libuvc/blob/448d78b9aa4f54172c1ca5b5d1b4a6b9b93f7957/include/libuvc/libuvc.h#L480-L486
