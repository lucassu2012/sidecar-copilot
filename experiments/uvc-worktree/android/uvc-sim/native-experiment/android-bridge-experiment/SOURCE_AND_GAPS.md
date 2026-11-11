# Android USB bridge：固定来源与剩余缺口

本轮把前轮 `PlatformUsbPort` 接到真实 Android SDK API：权限 PendingIntent/receiver、USB 设备复核与 connection 包装、受保护断连广播及显式 lifecycle hook。源码位于隔离实验目录；[manifest](AndroidManifest.xml) 尚未合并进主 App。Java SDK 编译与 host doubles 的结果见 [README](README.md)、[VALIDATION](VALIDATION.md) 和 [validation.json](validation.json)，其通过不代表 Binder、系统授权对话框、OEM 固件或真实设备已验证。本报告不运行这些检查。

## 固定输入与复核

参考 AOSP `platform/frameworks/base@4e43ad12e1b211f15152e9c5b16b0fe88e6b93f3`，工作树基线 `3e5abb1b880040ceff775c5cd43d5ad61115af9b`。这是 Android 15 / API 35 参考实现，不是目标手机固件证明。

[inputs.json](source-evidence/inputs.json) 锁定 15 份官方文本的 URL、commit、Gitiles wire SHA-256、解码后大小/SHA-256、计算得到的 Git blob SHA-1 和源文件许可头；[anchors.json](source-evidence/anchors.json) 将 18 项结论绑定到 41 个精确行范围及子串。blob 值核对内容身份，本轮未遍历完整 Git tree 证明其成员关系。2,741,342 字节全文仅留在 ignored `source-evidence/*.txt` 缓存，不进入版本化差异或 Java 编译输入。缓存保留原始版权/许可头，没有复制进 App 源码。

从仓库根目录复核：

```powershell
python android/uvc-sim/native-experiment/android-bridge-experiment/source-evidence/verify_sources.py
# 仅在需要补齐本地缺失文本时显式联网；不会更新任何输入锁：
python android/uvc-sim/native-experiment/android-bridge-experiment/source-evidence/verify_sources.py --fetch
```

默认离线，只核对缓存字节与锚点，不生成或改写报告。缺项、篡改、冲突锁均失败；`--fetch` 只取锁内固定官方 URL，并在落盘前核对 wire、source、blob。已有错误缓存不覆盖，输入锁不自动刷新。工具仅用 Python 标准库，不执行 AOSP 文本、Android API、native 或旧模型。线上固定 URL 的可达性仍是全新缓存重建的前提。

## 会直接改变实现的源码结论

表中 claim ID 对应 `anchors.json`；URL 与完整哈希位于 `inputs.json`。

| 固定事实 / 推导 | 本轮实现与边界 |
| --- | --- |
| `usb-result-fill-in`、`immutable-drops-fill-in`：[USB 服务](https://android.googlesource.com/platform/frameworks/base/+/4e43ad12e1b211f15152e9c5b16b0fe88e6b93f3/services/usb/java/com/android/server/usb/UsbUserPermissionManager.java#716)及 SystemUI 把 device/grant 放进新的 Intent 后调用 `send`；PendingIntentRecord 只在非 immutable 时 `fillIn`。 | `AndroidPlatformUsbPort` 使用 API 30 `ONE_SHOT`，API 31+ `ONE_SHOT | MUTABLE`。此用途不能换成 immutable，否则系统新加的 USB 结果 extras 不进入最终 Intent。未设置不安全 implicit 豁免。 |
| `mutable-api-levels`、`intent-identity`、`base-routing-preserved`：target U+ 限制 mutable implicit PI；`Intent.filterEquals` 不比较 extras；无 FILL_IN 覆盖位时已设置的 action/data/component 保持；原始 extras 在冲突时优先。 | 显式自家 `PermissionReceiver`；随机 token 加进程递增值构成唯一 action/data；不预填 DEVICE/GRANTED，不用 extra-only nonce 或 `UPDATE_CURRENT` 复用身份。`PermissionRegistry` 校验完整 action/data/component/device，原子移除后才调用 owner，owner 再检查未撤销和未消费。Android 请求 token 与公共 `PermissionRequest`/nonce 通过 entry 绑定；系统不提供 App attachment epoch。 |
| `pi-creator-identity`、`pi-broadcast-identity`、`private-manifest-receiver`：[PendingIntentRecord](https://android.googlesource.com/platform/frameworks/base/+/4e43ad12e1b211f15152e9c5b16b0fe88e6b93f3/services/core/java/com/android/server/am/PendingIntentRecord.java#657)区分 creator UID 与实际 sender UID；manifest receiver 检查接受 same-app UID。 | manifest receiver 为 `exported=false`，可供系统发送 App 创建的显式 PI；无需为 USB 结果而导出 receiver，也不能简单要求回调线程 Binder UID 必须等于 SYSTEM。PI 是能力令牌，不应泄漏；同 UID 代码不构成安全隔离。本轮只追踪相关权限检查，未穷尽其他广播投递策略。 |
| `usb-detach-protected`、`usb-detach-send`、`usb-system-server-chain`：框架将 USB detach 声明为 protected；本固定实现由 system_server 的 UsbPermissionManager 清临时 grant 并广播。禁止普通非系统 App 伪造，不等于所有获准发 protected 广播者都仅 UID 1000。 | 只注册 `ACTION_USB_DEVICE_DETACHED`；API 33+ 用 `RECEIVER_NOT_EXPORTED`，30–32 用旧重载。`private-dynamic-system-broadcast` 的 system/root 例外允许此参考路径进入私有动态 receiver；`protected-filter-registration` 显示只含 protected action 的 filter 本身豁免显式 export flag 要求。不能把这套选择泛化到混有自定义/其他 privileged-app action 的 filter。 |
| `typed-parcelable`：API 33 的 typed getter 与旧 getter 不同。 | API 33+ 用 `getParcelableExtra(name, UsbDevice.class)`；30–32 取出后检查类型；grant 必须为实际 Boolean。缺失、错型、反序列化异常不默认当成功；错误 permission 事件不消费有效 entry。detach 缺身份按不确定处理，关闭本次准入。 |
| `privacy-and-camera`、`false-not-user-denial`：[UsbUserPermissionManager](https://android.googlesource.com/platform/frameworks/base/+/4e43ad12e1b211f15152e9c5b16b0fe88e6b93f3/services/usb/java/com/android/server/usb/UsbUserPermissionManager.java#199)检查视频 CAMERA 权限和相应 sensor privacy；缺 CAMERA 可直接回 false。音频能力路径在此检查麦克风 privacy，但明确不在此要求 RECORD_AUDIO。 | 拒绝不能统一解释成“用户拒绝 USB 对话框”；真实 App 仍须处理运行时 CAMERA/必要的 RECORD_AUDIO、privacy 与权限变化。获得 USB 权限不证明 UAC 或系统音频路由已可用。 |

## 本轮接线达到的边界

`AndroidPlatformUsbPort` 确实调用 `UsbManager.getDeviceList/hasPermission/requestPermission/openDevice`、`UsbDeviceConnection.getFileDescriptor/close`，不是 SDK 名称相同的纯 SPI 实现；host doubles 仅替换运行时 Android 对象。`DeviceKey` 用名称和描述符筛选候选，`AttachmentGuard` 仍须由 App 提供可信的新旧附件观察。设备名、VID/PID、Java 对象或 fd 数字都不证明附件代次。旧设备回调不能替代当前 epoch/authorization revision；跨实例唯一选择与公共 nonce 管理继续属于外层。

open 前后验证由 port 与前轮 `UsbConnectionOwner` 分担：port 开前查当前附件及权限；明确 false 且尚未调用 openDevice 时返回 SPI null，表示已知未取得 connection，避免误记 acquisition-unknown。父 owner 对该窄路径记通用 IO，不能声称精确归因断连/权限；真正 openDevice 抛异常及预检 SDK 调用自身抛异常仍保守处理。已获得的迟到 connection 交 owner，供其完成开后复核与唯一 close。getFD 在 owner 的 open-operation 保护内，随后借用 cached-fd capability；该整数没有 `dup` 或所有权转移。本轮 wrapper 不把 fd 交给真实 native。正常 Android connection-owned FD 源证据及失败分支限制继承 [Android FD contract](../../interface-contract/ANDROID_CONTRACT.md) 与 [owner 来源说明](../usb-owner-experiment/SOURCE_AND_GAPS.md)，不将 App 唯一 close 契约扩大为“框架全部失败路径已证明只关闭一次”。

`AndroidUsbLifecycleBridge` 由宿主显式调用 attach、用户请求/打开、stop、权限撤销与 lifecycle-stop。grant 不自动 open/start；初次 stop 认领后依次调用 publication gate、实际 owner stop、port revoke，外部回调在本地锁外。重复/重入 stop 不再发同一组外部调用。`closed` 表示本地停止准入已认领，`stopDispatchCompleted` 只表示该调用序列已退栈；两者都不证明 native drain、像素清零或完整 StopBarrier 成功。publication gate 的真实性、有限时长、不可复活性质仍由主 App 实现。

`revoke()` 使本地 entry/准入失效；平台 PI cancel、receiver unregister 和 connection close 分别有其异步/排空责任。它**不撤销已经授予的 Android 系统 USB 权限，也不保证撤销已经在途的授权对话框**。请求登记与 `UsbManager.requestPermission` 之间可能遇到 stop；已准入请求可在本地 stop 后稍晚进入系统，旧回调仍不能复活旧 owner。不得为消除该窗口而持 owner 锁调用 Binder。取消/反注册失败只是保留诊断，不伪造系统撤销或硬件关闭。

## 下一步实际 App 桥前提与许可

1. 审核并合并隔离 receiver manifest 到实际 applicationId；确认组件 enabled、min 30/target 35、USB host feature、实际权限声明和运行时权限流程。此次没有 App manifest 合并、APK 安装或真实授权对话框。
2. App 维护可信 attachment epoch/authorization revision、用户选择和前台/生命周期策略；把 `AttachmentGuard`、显式工作线程操作与真正 publication gate 接入现有 root 流程。不存在本实验替宿主选定的 Activity/Service 停止策略或前台服务完成证明。
3. 在真实 API 30/31/33/35 与目标 OEM 上核 PI fill-in、显式私有 receiver 的 Binder 投递、拒绝/撤销/重启/拔插、授权时拔插与旧回调。host doubles 可验证 App 分支与交错，不能证明系统权限认证、SystemUI、广播时序、硬件兼容或厂商行为。
4. 真实 native FD 借用/退栈、libusb/libuvc status teardown、格式协商、音视频路由与设备闭环仍未接。前轮 LGPL/BSD/JPEG、UAC 四 ABI 来源授权与 native 构建闭包门禁全部保留；本轮不关闭任何这些门禁，也不新增 PRD 状态或测试编号。

本轮桥与核对脚本为原创实现，无新增第三方 runtime 依赖。AOSP 文本只作固定参考，15 文件均观察到 Apache-2.0 许可头；调用 Android API 与分发 AOSP/HAL/native 源码是不同路径，若后续复制或构建上游源码需重新核对其实际分发闭包。本轮无设备/模拟器/native 操作，无发布、部署或远端推送；最终本地提交由根统一完成。
