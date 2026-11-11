# Android 官方平台路线：能力覆盖与 UVC 边界

核查日期 2026-11-10，工作区基线 `bbd053664bebb06ce83539a3f95e6fa5758745b7`。本轮仅获取固定文本源码与提交元数据，无二进制下载、native 构建、设备操作或应用改动。

**纯 SDK App 不能据 UsbManager 的发现/授权能力宣称已获得通用 UVC 驱动。** 固定 API 35 的 UsbRequest 只支持 bulk/interrupt，底层拒绝 isochronous。Camera2 可作为不自带 USB 驱动的 App 路线，但前提是目标手机固件已提供可用的外接相机 provider。独立 native UVC 路线与此不同；Java API 不支持 iso，不能扩大成 Android 内核或所有 native 驱动都不支持 iso。

## 固定依据与复现

采用 Android 15/API 35 的三个固定 AOSP 提交；这是参考实现选择，不是目标手机的软件指纹：

| 仓库 | commit | tree |
|---|---|---|
| platform/frameworks/base | `4e43ad12e1b211f15152e9c5b16b0fe88e6b93f3` | `9583c6d9586b84169697623a44af09e8ac3d79b9` |
| platform/hardware/interfaces | `488942f82bd1bc9ad1cb65a02c71421dc3a6a3d6` | `484d45fd7355830b7b66d1cffdb679f8bf484a6a` |
| platform/system/core | `fc7bc8c4bfb4c6095e34ea784509dae56f25b486` | `5cf82bdcf8760455bc4ef94b2d5a75e0bbfa3fd6` |

[INPUT_LOCK.json](INPUT_LOCK.json) 锁定 20 个输入（3 个 commit JSON、17 个文本源文件）的固定 URL、大小和 SHA-256；[static-evidence.json](static-evidence.json) 记录解码源码哈希、计算出的 Git blob ID、版权头和精确定位行。源码通过固定 commit URL 取得；计算 blob ID 不是独立遍历整个 Git tree 的成员证明。前轮 Camera2 缓存按其旧锁核验后只读复用，所有本轮缓存位于 ignored `android/uvc-sim/build/routes/platform/`。

```powershell
# 默认离线；重算结果必须与已记录结果相同：
python -B android/uvc-sim/provenance/routes/platform/audit_platform_route.py
# 运行完整性检查，不改写已有 integrity-results.json：
python -B android/uvc-sim/provenance/routes/platform/check_integrity.py
# 仅首次建记录或明确决定更新记录时使用：
python -B android/uvc-sim/provenance/routes/platform/check_integrity.py --write-report
# 新缓存只取缺失输入，仍严格匹配现有锁：
python -B android/uvc-sim/provenance/routes/platform/audit_platform_route.py --online
```

工具仅用 Python 标准库（本轮 3.14.2；脚本语法需 3.9+），没有安装依赖。两个脚本默认均不改写已记录证据；各自的 `--write-report` 才显式生成/更新其结果文件，完整性检查默认即使未保存 JSON 也会执行所有检查。`--record-inputs` 拒绝覆盖已有输入锁。独占缓存锁禁止并发采集，已有锁不自动移除。缺缓存、篡改、缺结果或结果不一致均失败；离线没有网络回退，在线也不接受被改动缓存。保留的实际检查记录见 [integrity-results.json](integrity-results.json)，不属于产品或设备测试。

本次查阅的 Android 开发者/集成网页仅作为带日期的说明链接，不加入可变 HTML 输入锁；核心结论绑定以下固定源码。

## 精确覆盖表

| 层次/能力 | 固定实现能提供 | 仍不能据此证明 | 归属 |
|---|---|---|---|
| USB host 发现 | UsbManager 获取已连接设备及其接口/端点，App 可选择候选设备。 | 设备是可用的视频源、可协商指定格式、已授权、能维持供电/充电。 | App 可调用 API；手机必须具备 host 硬件/系统能力。 |
| USB 授权与连接 | `hasPermission`、`requestPermission`、`openDevice`；临时授权限本次附件连接。target P/API 28+ 的 USB 视频类设备还要求 CAMERA 权限。 | 用户已同意一次永久来源、断开重连自动获准采集，或 USB 授权等于 AudioRecord 权限。 | App 与系统授权服务；必须重新核对附件和许可。 |
| control/bulk/interrupt | controlTransfer 对控制端点，bulkTransfer 同步 bulk，UsbRequest 异步 bulk/interrupt。 | UVC 类协议、视频协商、payload 组帧、解码、等时传输或端到端延迟已实现。 | App 可用传输基元，但须另有类驱动逻辑。 |
| isochronous | 端点描述符可表示 ISO 类型。 | 不能因此使用 UsbRequest 发送/接收 ISO；其 native backend 明确拒绝该类型。 | 此 SDK 路径缺失；另行 native/OEM 路线需独立评估。 |
| 取得 native FD | 返回 connection 持有的描述符整数。 | 不复制、不转移所有权，也不授予驱动能力或保证断连后可用。 | Java connection 与 native adapter 必须约定生命周期。 |
| Camera2 外接源 | 已安装 provider 宣告可用设备时，App 可枚举 characteristics/stream configurations、建立 session 并接收 Image。 | 所有零售手机都有 provider、所有 UVC 卡被接受、原 HDMI 输出保持或桌面文字可读。 | OEM kernel/provider/HAL/配置负责暴露；App 负责选择和适配。 |
| 停止/关闭 | API 提供 cancel、close、requestWait 或 onClosed 等局部动作/观察。 | 取消成功或一次回调不等于所有请求、App worker、借用 buffer 和待提交数据都排空。 | App 必须实现失效屏障、所有权与排空。 |
| USB 音频 | AudioManager 可枚举系统识别的输入；AudioRecord 可表达首选设备并观察实际录音路由。 | UVC 视频成立不证明 UAC 可用；首选成功/read>0 不证明实际来源和 A/B/C/D 四路闭环。 | 独立音频系统/权限/路由，不随视频自动启用。 |

USB 发现与连接锚点：固定 [UsbManager.java L832–891](https://android.googlesource.com/platform/frameworks/base/+/4e43ad12e1b211f15152e9c5b16b0fe88e6b93f3/core/java/android/hardware/usb/UsbManager.java#832)；CAMERA 补充权限在 L951–953、L1049–1051，临时许可与 PendingIntent 结果在 L1035–1059。[官方 host 概览](https://developer.android.com/develop/connectivity/usb/host)同样将发现、授权和端点通信分开。识别 VID/PID 或 USB 类只是候选筛选，不能作为硬件兼容结论。

## Bulk 与 ISO：App 能否单独补足 UVC

固定 [UsbRequest.java L36–40](https://android.googlesource.com/platform/frameworks/base/+/4e43ad12e1b211f15152e9c5b16b0fe88e6b93f3/core/java/android/hardware/usb/UsbRequest.java#36)只声明 bulk/interrupt，endpoint 0 应使用 controlTransfer。JNI 将端点属性传给 `usb_request_new`；固定 [usbhost.c L686–700](https://android.googlesource.com/platform/system/core/+/fc7bc8c4bfb4c6095e34ea784509dae56f25b486/libusbhost/usbhost.c#686)仅将 BULK/INT 转为 URB，其余返回 NULL。`bulkTransfer` 经 JNI 转为 `USBDEVFS_BULK`（usbhost.c L665–678），不是绕开的 ISO 接口。[当前公开 API 说明](https://developer.android.com/reference/android/hardware/usb/UsbRequest)与此一致。

据这些基元作出的工程判断：**特定 bulk 设备可能有纯 SDK 类驱动实现空间，但这里没有这样的实现或验证。** 仍需选中正确接口/alternate setting，解析与协商 UVC 数据，组帧、验证格式与帧边界、解码、处理丢包及生命周期。`setInterface` 只选择接口 alternate setting，不是通用的 UVC 视频模式协商。`getRawDescriptors` 也只给原始描述符，不生成视频帧。不能把“能 bulkTransfer”升级为通用 UVC 可用。

若目标设备要求 ISO，当前 Java SDK 路径不成立。带 JNI/独立 USB transport 的 App 是另一种设计，可能不依赖 Camera2 external provider，但需重新验证内核/设备传输条件、native 源码/许可/ABI 与驱动行为；它不属于“只调用已有公开 SDK、没有自带 native”的路线。本轮不实现或启用该路线。

另外，UsbManager L700–706 的 `FUNCTION_UVC` 是带 `@hide`/`@SystemApi` 的 USB gadget 功能常量，引用 GadgetFunction；不是给普通 App 使用的 host UVC 采集驱动。不能根据源码里出现 UVC 常量，推导 App 可开启缺失的外接 provider。

## Camera2 的 OEM 前提与格式边界

[官方 external USB camera 集成说明](https://source.android.com/docs/core/camera/external-usb-cameras)要求设备构建包含 USB host/UVC kernel 支持、外接 provider、设备配置及 SELinux/HAL 声明。普通 App 调用 Camera2 不能补装这些 OEM 组件；本项目也不把改 ROM、增加电脑采集代理或降低原显示体验作为隐含修复。

固定 [ExternalCameraProvider.cpp](https://android.googlesource.com/platform/hardware/interfaces/+/488942f82bd1bc9ad1cb65a02c71421dc3a6a3d6/camera/provider/default/ExternalCameraProvider.cpp)检查 V4L2 capture 能力并初始化设备后才报告存在。底层依赖 kernel/provider，走的不是上述 UsbRequest 请求路径；因此 SDK ISO 限制不能直接套到 Camera2 的底层传输能力上。provider 自身通过状态回调报告外接设备，不要将其内部空 `getCameraIdList` 与 App 的 CameraManager 列表混为一谈。

固定 [ExternalCameraDevice.cpp L42–43](https://android.googlesource.com/platform/hardware/interfaces/+/488942f82bd1bc9ad1cb65a02c71421dc3a6a3d6/camera/device/default/ExternalCameraDevice.cpp#42)的输入 FourCC 表仅为 **MJPEG/Z16**；Z16 属深度，不是桌面彩色视频。L637–675 为 MJPEG 映射 BLOB/YUV420/IMPLEMENTATION_DEFINED 输出。该参考实现未建立 YUYV-only 卡的支持；vendor 可有不同实现。App 应查询实际输出尺寸/格式与处理 stride，不能假定收到原始 MJPEG、固定 RGBA 或 HDMI 原生帧率。

CameraManager 可枚举连接设备和可用性，但枚举/`LENS_FACING_EXTERNAL` 不证明它就是所选电脑输出，也不证明清晰度、EDID/loop-through、供电或占用状态。当前没有目标手机/卡/Hub 的 G1 设备证据，格式支持矩阵保持待验证。

## FD、请求和 Image 的关闭责任

| 对象/动作 | 精确事实 | 未来 adapter 的必要边界（工程推论） |
|---|---|---|
| `openDevice` 创建 connection | [UsbDeviceConnection JNI L49–56](https://android.googlesource.com/platform/frameworks/base/+/4e43ad12e1b211f15152e9c5b16b0fe88e6b93f3/core/jni/android_hardware_UsbDeviceConnection.cpp#49)以 `F_DUPFD_CLOEXEC` 复制传入 PFD，避免 PFD 关闭其自身副本影响 connection。 | 这一次 dup 的结果归 connection/native device；不要误以为此后每次取 FD 又复制一次。 |
| `getFileDescriptor` | 同 JNI L79–87 和 usbhost.c L409–413 直接返回持有 FD；未打开时返回 -1。 | 按借用处理，调用方不能擅自 close。native 若主动 dup，须分别记录谁释放各副本；dup 不是断连、许可/同意撤销后继续使用的授权。 |
| `connection.close` | JNI L69–75 调用 usb_device_close；usbhost.c L358–361 关闭 FD 并释放 device。 | 在所有借用 FD 的 native 调用和请求退出前不能错误释放；不能仅凭整数 FD 判断设备身份，旧整数可能失效或被复用。 |
| `UsbRequest.queue` | [UsbRequest.java L251–267](https://android.googlesource.com/platform/frameworks/base/+/4e43ad12e1b211f15152e9c5b16b0fe88e6b93f3/core/java/android/hardware/usb/UsbRequest.java#251)：true 是入队成功，buffer 在 requestWait 返回该请求前状态未定义。 | 建立每请求的 buffer 所有权，不在仍排队时复用/释放，不把入队当已读到帧。 |
| `cancel/requestWait/close` | usbhost.c L735 为 SUBMITURB，L758–759 为 REAPURB，L775–778 cancel 仅 DISCARDURB；L719–723 free 只释放请求内存。 | cancel 成功不证明已回收。跟踪同连接多端点返回的请求及 worker；超时/NULL 不谎报关闭或排空，close 不替代生命周期协议。 |
| CameraDevice/ImageReader | [CameraDevice.java L1561–1584](https://android.googlesource.com/platform/frameworks/base/+/4e43ad12e1b211f15152e9c5b16b0fe88e6b93f3/core/java/android/hardware/camera2/CameraDevice.java#1561)允许断开后仍有旧回调/输出；onClosed 指示 device 关闭。[ImageReader.java L773–783](https://android.googlesource.com/platform/frameworks/base/+/4e43ad12e1b211f15152e9c5b16b0fe88e6b93f3/media/java/android/media/ImageReader.java#773)说明 reader 关闭会使已获取 Image/旧 plane buffer 失效。 | 先失效采集代与发布准入，再协调 in-flight 工作与 buffer 释放；应用队列的排空仍由自己证明。若异步需要数据，应在借用期内复制到受控内存；不得延长原生/Image 借用寿命。 |

这些责任不是可直接照抄的完整关闭算法；特别是挂起请求、断连、关闭失败以及多线程竞争还需单独实现和模拟验证。它们延续既有失效、一次释放、迟到拒收与主动恢复约束，不新增产品状态。

## 音频是独立路线，不能静默回退

固定 [AudioManager.java](https://android.googlesource.com/platform/frameworks/base/+/4e43ad12e1b211f15152e9c5b16b0fe88e6b93f3/media/java/android/media/AudioManager.java)的 `getDevices(GET_DEVICES_INPUTS)` 与 [AudioDeviceInfo.java](https://android.googlesource.com/platform/frameworks/base/+/4e43ad12e1b211f15152e9c5b16b0fe88e6b93f3/media/java/android/media/AudioDeviceInfo.java)的 USB 类别/isSource 描述的是**系统识别的音频输入**，不是 UsbManager 任意 UAC 描述符的保证。Camera2 session 也不创建这一音频输入。

固定 [AudioRecord.java L2144–2177](https://android.googlesource.com/platform/frameworks/base/+/4e43ad12e1b211f15152e9c5b16b0fe88e6b93f3/media/java/android/media/AudioRecord.java#2144)把首选设备与实际路由分开；`getRoutedDevice` 仅在录音中有效，传 null 给首选设备会恢复默认选择。固定 [UsbAlsaManager.java L421–428](https://android.googlesource.com/platform/frameworks/base/+/4e43ad12e1b211f15152e9c5b16b0fe88e6b93f3/services/usb/java/com/android/server/usb/UsbAlsaManager.java#421)在拔除后可能选择默认/其他设备。App 必须确认实际 USB 来源并处理权限、格式、配置/策略静音和通知；禁止以默认或手机麦克风作为静默恢复。

路由变化通知与 PCM 帧没有公开的原子归属保证；即使 read>0、文件增长或未静音，也不能证明有效远端/本地音频及零切换窗口误收。AudioRecord 仍需 RECORD_AUDIO 和适用的前台/权限约束。只有 C 时不能宣称双向纪要，G2 四路实听不能由系统 API 或静态源码替代。详见既有 [音频证据](../../followup/uac/REPORT.md)与 [A/B/C/D 缺口方案](../../feasibility/audio-gap/REPORT.md)。

## 来源、许可证及最终选择边界

本轮 17 个被查源文件均保留 Apache 2.0 头；具体 AOSP/作者版权行与原文链接在 `static-evidence.json`，没有据一个根许可证替代完整 Android 或 third-party 许可审计。

**调用手机已经提供的 SDK API**，与**把 AOSP HAL/JNI/USB host 实现复制进 App 或自己发布系统镜像**是不同的分发路径。前者不要求 App 打包本次查看的 HAL/libyuv/UAC 实现；这不对手机固件本身的许可状态作判断。后者新增源码、修改、构建及传递依赖义务；固定 [HAL Android.bp](https://android.googlesource.com/platform/hardware/interfaces/+/488942f82bd1bc9ad1cb65a02c71421dc3a6a3d6/camera/device/default/Android.bp)就链接 libyuv 和 libjpeg，不能因 HAL 文件有 Apache 头而忽略它们。缓存源码供审阅，不是应用依赖或可分发实现批准。

当前可得决策只有：已提供且适配目标卡的 Camera2 provider 是条件性纯 SDK 视频候选；Java USB host 本身是较低层基元，bulk 子集仍需类驱动工作，ISO 路径需另选 transport；系统 AudioRecord 独立处理音频。三者都没有以本报告关闭真实采集、native/分发或 G1/G2 门禁。没有更改设备、系统、PRD 或生产配置，也没有提交代码。
