# Android 授权与 FD 接口契约

基线 `3dbbcb17f26816689d4b51b714996d937b9f4acd`；2026-11-10 静态复核。范围是 `UsbManager → UsbDeviceConnection → native FD 借用` 的接口矩阵，不实现 Android adapter，不运行模型、设备、模拟器或 native，也没有新网络抓取。

[android-contract.json](android-contract.json) 将接口、前后条件、失败语义与确切源码锚点关联。4 个输入均继承 [API 35 输入锁](../provenance/routes/platform/INPUT_LOCK.json)，复用旧 `sidecar-uvc-route` 工作区只读缓存；frameworks/base 固定 `4e43ad12e1b211f15152e9c5b16b0fe88e6b93f3`，system/core 固定 `fc7bc8c4bfb4c6095e34ea784509dae56f25b486`。这是 Android 15 参考源码，**不是目标固件指纹或实机结果**。

JSON 的 `new_or_inherited` 区分：`inherited_rechecked` 为已有结论重新定位；`new_static_mapping` 为此次新增的精确签名/失败分支/调用关系核查；`new_project_contract` 为主集成仍须实现的约定。来源均为继承输入，不把新整理当新平台能力。所有 `source_refs` 指向事实依据；项目约定由事实推导，不能反过来声称平台已有这些字段或原子保证。

## 新增接口矩阵

签名省略包名前缀，但保留公开方法、参数及返回类型。方法属于 `android.hardware.usb.UsbManager` 或 `UsbDeviceConnection`；PendingIntent 来自 `android.app`。实际引用行及失败条件完整记录在 JSON。

| 接口 | 固定源码的返回/失败语义 | 主集成前后条件与边界 |
|---|---|---|
| `public HashMap<String,UsbDevice> getDeviceList()` | UsbManager L833–857：按 device name 为 key 返回当前设备；无设备、host 不活动/不支持可空；service 为 null 也返回空。RemoteException 被重新抛为系统服务异常。 | 只能生成候选观察，不附授权、选择或 attachment epoch。空列表不能单独区分原因；本次列表与后续授权/open 不是原子快照。 |
| `public boolean hasPermission(UsbDevice device)` | L946–968：检查调用方当前权限；可能源于临时授权或默认应用关联。service 为 null 返回 false；RemoteException 重新抛出。文档要求适用的视频类、target P+ 调用方另有 CAMERA 权限。 | true 不是 Sidecar 用户当前运行确认，也不保证下次 open 成功或权限持续不变；false/异常均不得推进采集。USB 权限不等于音频 RECORD_AUDIO。 |
| `public void requestPermission(UsbDevice device, PendingIntent pi)` | L1035–1063：异步结果经 pi；可能显示对话框，返回 void 不表示批准。成功授权持续到设备断开；RemoteException 重新抛出。结果增加 `EXTRA_DEVICE` 和 `EXTRA_PERMISSION_GRANTED`。 | 调用前登记当前选择/attachment epoch/authorization revision/唯一请求 nonce。结果到达后先验证请求仍有效，再复查当前设备观察及权限；过期、重复、缺字段、拒绝或异常都不能 open/start。 |
| `public UsbDeviceConnection openDevice(UsbDevice device)` | L859–882：以 `device.getDeviceName()` 向服务打开，创建 connection，关闭中间 PFD；成功返回 connection。null PFD、native open 失败或固定实现捕获的 Exception 返回 null。 | 调用前确认当前选择和许可；在专用工作线程打开。成功返回后也须核对代次尚有效；迟到结果归本次打开的 owner 负责清理，不能挂到新选择。这里不承诺所有平台错误都被转换为 null。 |
| `public int getFileDescriptor()` | UsbDeviceConnection L153–162：返回 native FD，未打开为 -1；JNI L79–87 返回 device 持有的 FD。此 getter 不 dup、不转移所有权。 | 在活跃 connection owner 的借用保护下取得并使用；负值拒绝，0 是可用 FD 数值。`fd >= 0` 不证明仍有授权、仍连接、UVC 可用或关闭可安全并发。 |
| `public void close()` | UsbDeviceConnection L138–151：关闭后对象不可再用，应重新 open 获新实例。固定实现持 mLock，并只在 isOpen 时调用 native close；JNI L69–75 清 native context；usbhost L358–361 close 其 FD。返回 void，无驱动排空 receipt。 | connection 的唯一 owner 调用。先拒绝新 borrow，并等所有已接受的 native 借用/调用退出及相应驱动 drain 证据，才能关闭；不得由 native borrower 再 close 同一 FD。close 返回也不证明 Java 像素已清零。 |

`open` 时的 FD 复制与 `getFileDescriptor` 不同：JNI L49–61 以 `F_DUPFD_CLOEXEC` 复制中间 PFD 一次，成功副本由 connection 持有，随后 UsbManager 关闭 PFD 自己的副本。正常 JNI 路径的 `usb_device_new` L381–382 将 writeable 设为 1；通用 `usb_device_get_fd` L409–413 还会调用 `reopen_writeable`，其另一分支可替换 FD（L393–406）。因此不把所有 usbhost 路径概括为“永远纯 getter”，也不依赖跨调用的整数稳定性。

## PendingIntent 结果与项目身份

固定 API 只承诺结果携带“传入该次请求的 device”和授权布尔值（UsbManager L1042–1046）；公开 extra 常量值为 `device` 与 `permission`（L386、448）。attach/detach 事件同样含设备对象（L142–166）。这些内容没有 Sidecar 会话、attachment epoch、请求 nonce、authorization revision，也没有承诺到达时设备仍在、没有发生重连或与别的回调按指定顺序送达。

以下均是**待实现的内部契约字段，不是新增 PRD 状态**：

| 自有绑定 | 主集成约定 |
|---|---|
| `selection_token` + `attachment_epoch` | 显式选择与当前附件生命周期绑定；确认断开/替换时失效旧 epoch，重连创建新 epoch。VID/PID、deviceName、USB 对象引用或 FD 数字只能辅助匹配/诊断，不能单独充当持久会话身份。平台事件若不足以排除身份歧义，保留未知并禁止用旧授权继续。 |
| `authorization_revision` | 对接受的授权变更、撤销或失效使用单调修订；旧 revision 的结果不能恢复新选择的准入。系统 hasPermission 与产品运行授权分别校验。 |
| `permission_request_nonce` | 在 requestPermission 调用前登记到本地 outstanding 记录，绑定 selection/epoch/请求时 revision；验证后最多消费一次。重复、迟到、已经撤销或属于旧 epoch 的结果没有副作用。不能只匹配 grant=true 或 EXTRA_DEVICE 的名称。 |
| `connection_owner_token` + `connection_generation` | 成功返回的 connection 取得唯一 owner 身份；本次 open 即使后来过期仍保留明确清理责任。数值相同的 FD 属于新 owner 时，旧事件不能归还它的 borrow 或关闭它。 |
| `borrow_token` | 取得 FD/进入 native 前，在 owner 的同步协议内登记借用；进入 closing 后拒绝新借用，待既有借用和驱动义务退出后只由 owner close。不能把 Java close 的 mLock 当成 native 调用的引用计数。 |

结果接收需要核对 nonce 的可回传绑定、目标 receiver、PendingIntent 的身份隔离、适用 target SDK 的 flags/mutability 及接收器暴露/来源约束；**这 4 个固定源没有覆盖这些构造与安全实现**，主集成仍需单独设计并核查。这里只规定应达到的关联性质，不提供未经核对的 flags 配方，也不声称任意 extras 已实现唯一 PendingIntent 身份。即使回调与 hasPermission 均为真，open 前后的绑定校验也不能省；这些观察并不形成跨进程原子事务。

detach 或主动停止先不可逆地失效当前发布/授权准入，撤销未消费请求关联并禁止新 borrow，然后按真实驱动生命周期排空。旧 permission/open/callback 结果只完成自己的拒收或清理，不改变新 owner。重连重新发现、主动选择、核对当前系统权限与新运行授权，不能从上次 grant 自动继续。

## 关闭责任与新增静态限制

本契约默认 native **借用 connection 的 FD**；借用者不关闭它。若后续选用主动 dup，额外副本必须有单独 owner、创建失败处理及关闭账本；不能因此延长授权、允许停止后采集或声称 detach 后仍可用。libusb/libuvc 的 wrap、cancel/drain、event pump 与晚回调限制继续引用 [源码映射](../protocol-research/SOURCE_MAPPING.md)，不在这里重复或假定已修复。

此次新核查两处限制：

- Java `close` 持 mLock，但 `getFileDescriptor` L160–162 直接调用 native getter，没有同锁保护；公开 API 没有为自有 native borrow 提供计数或退出握手。必须由 owner 协议协调取 FD、使用、停止与关闭。
- 固定 JNI L56–61 在 `usb_device_new` 返回 null 后执行 `close(fd)`，而固定 usbhost 的失败分支 L385–390 也执行 `close(fd)`。这两个可见清理站点说明**不能宣称平台全部 open 失败路径已证只关闭一次**。这里没有运行、复现漏洞、判断某目标手机必现或修补系统；正常成功路径的 connection-owned/borrowed 结论不变。项目应避免自行补一次裸 FD close，更不能凭平台错误猜测某整数现在仍属自己。

发现/传输能力、Camera2/OEM 和音频边界见 [既有平台报告](../provenance/routes/platform/REPORT.md)。本次没有提供 UVC 类驱动、iso 传输、音频路由或硬件兼容证据。所查文件保留 AOSP Apache-2.0 来源头；调用已有 SDK 与复制分发 JNI/libusbhost 是不同范围，后者仍需实际来源/构建/许可核查；此契约未复制实现代码、未解除已有 native 与 LGPL/BSD/JPEG/UAC 门禁。

## 离线只读复核

JSON `sources` 锁定继承 lock 路径/id、原缓存 SHA-256、解码源码 SHA-256/Git blob ID；`contracts[].source_refs` 锁定行区间及精确子串。每个方法的前后条件区分平台事实与项目所需约定；校验锚点只验证引用可复现，不自动证明工程推论。Git blob ID 为内容身份，不是独立完整 AOSP tree 成员证明。

在仓库根目录执行下列标准库命令；仅读 JSON、继承锁及旧缓存，不运行模型或上游代码。缺缓存/不符直接失败，无联网回退、锁刷新或报告写入。

```powershell
@'
from pathlib import Path
import base64, hashlib, json
root = Path.cwd().resolve()
cache = root.parent / "sidecar-uvc-route/android/uvc-sim/build/routes"
spec = json.loads((root / "android/uvc-sim/interface-contract/android-contract.json").read_text(encoding="utf-8"))
decoded = {}
for source in spec["sources"]:
    lock = json.loads((root / source["inherited_lock"]).read_text(encoding="utf-8"))
    matches = [x for x in lock["inputs"] if x["id"] == source["input_id"]]
    if len(matches) != 1:
        raise RuntimeError("inherited input missing or ambiguous")
    entry = matches[0]
    raw = (cache / source["cache_path"]).read_bytes()
    if len(raw) != source["wire_bytes"] or len(raw) != entry["size"]:
        raise RuntimeError("input size mismatch")
    if hashlib.sha256(raw).hexdigest() != source["wire_sha256"] or source["wire_sha256"] != entry["sha256"]:
        raise RuntimeError("input hash mismatch")
    if source["url"] != entry["url"]:
        raise RuntimeError("source URL differs from inherited lock")
    if source["encoding"] != "gitiles-base64":
        raise RuntimeError("unexpected source encoding")
    data = base64.b64decode(raw, validate=True)
    blob = hashlib.sha1(b"blob " + str(len(data)).encode() + b"\0" + data).hexdigest()
    if hashlib.sha256(data).hexdigest() != source["source_sha256"] or blob != source["git_blob_sha1"]:
        raise RuntimeError("decoded source mismatch")
    decoded[source["id"]] = data.decode("utf-8").splitlines()
for contract in spec["contracts"]:
    for ref in contract["source_refs"]:
        lines = decoded[ref["source_id"]]
        if not 1 <= ref["start_line"] <= ref["end_line"] <= len(lines):
            raise RuntimeError("invalid anchor span")
        excerpt = "\n".join(lines[ref["start_line"] - 1:ref["end_line"]])
        if not all(token in excerpt for token in ref["contains"]):
            raise RuntimeError("anchor mismatch: " + contract["id"])
print("PASS: inherited input bytes and Android contract anchors; read-only")
'@ | python -B -X utf8 -
```
