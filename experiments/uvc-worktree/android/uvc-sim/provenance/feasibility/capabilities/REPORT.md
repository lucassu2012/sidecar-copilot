# 官方 libyuv 最小视频路线：能力、依赖和集成条件

**建议先选择“最小 UVCAndroid transport/JNI/解码路径 + owned RGBA”作为下一条候选，仅在确有保留格式转换需求时接入完整的官方固定 libyuv；仅 libyuv 不能完成采集。** 最小路径仍需要 Android USB 权限及事件、Java/JNI桥、libusb、libuvc、JPEG解码和应用自己的会话/发布控制。现有 `UvcDriver` / `FramePipeline` 是合成实验边界，尚不能接收实机1080p流；这个缺口与替换libyuv是两个独立问题。

本轮基线为 `c1afb3f61fa309bde0437fa2019c352ddc1792ba`，只做静态可行性研究。没有修改生产源码、公共接口、构建脚本、许可历史证据或PRD；没有安装依赖、执行上游构建/原生库、操作设备、提交或发布。以下“继续条件”是主集成需要取得的技术证据，不新增审批流程、产品状态或测试编号。

## 能力对应

| 冻结要求 / 实际工作 | 官方 libyuv 能提供的部分 | 仍需承担的组件与缺口 |
| --- | --- | --- |
| 发现和选择采集卡 | 无。像素API不枚举USB设备、接口或采集卡。 | Android `UsbManager`、现有只读发现器或经裁剪审查的 `USBMonitor`。VC/VS描述符只能形成候选，不能证明卡能工作、它连接哪个屏幕或当前HDMI画面。 |
| USB授权、拔插和连接身份 | 无。不能弹出USB授权、保存权限revision、生成连接代次。 | Android权限结果、receiver、插拔事件与所选attachment绑定；应用必须抑制旧广播/旧回调。`deviceName`、VID/PID不是完整授权或连接身份。 |
| 打开所选设备、probe/commit | 无。不能协商UVC分辨率、帧间隔、alternate setting或payload。 | Java打开USB连接，JNI把fd交给libuvc/libusb；固定 `libuvc/src/stream.c` 有probe/commit及模式查找代码，仍需实际协商结果证据，不能将“请求值”当作“生效值”。 |
| USB传输及错误恢复 | 无。不能完成bulk/isochronous传输、取消transfer、处理USB断连。 | libusb事件/传输、libuvc帧装配、UVCAndroid线程与原生资源回收；真正的停止时限、丢帧、断连/重连尚未验证。 |
| 视频解码与像素转换 | 可在已取得内存图像后做格式转换；固定版本的MJPEG入口需要JPEG构建支持，且支持的JPEG颜色空间/采样因子有限。 | 最小候选保留当前MJPEG→TurboJPEG与YUYV→libuvc解码入口。不能由libyuv推断支持H.264/HEVC或所有UVC描述符格式；不支持的格式须按已有中断/失败语义处理。 |
| 保持主屏选定时序、文字可读 | 可按指定尺寸转换，亦有缩放API；API存在不意味着允许缩图。 | EDID、HDMI复制/扩展、采集卡loop-through、色度采样及协商不由libyuv修复。不得静默降低主屏/采集模式来迁就fixture；模式/拓扑变化仍按既有暂停和人工核屏要求处理。 |
| 变化采样与1–5fps候选 | 可转换候选像素，本身不负责Sidecar采样决策。 | USB输入fps、解码fps、预览fps与PRD候选fps必须区分。现有UVCPreview在回调前解码；丢弃Java回调不自动降低USB带宽和解码成本。 |
| 授权、遮盖、停止屏障 | 无。不会判断Sidecar会话、遮盖原始数据或拒绝旧generation发布。 | 保留现有Coordinator/CaptureGate/FramePipeline概念边界；原生Surface不可直接绕过遮盖和发布门控。 |
| UAC音频、双向音源和禁换麦 | 无。视频像素库不能枚举/接收UAC、选择实际AudioRecord路由或保证不换麦。 | 另一路音频实现和健康/路由证据；视频授权、打开或恢复不能自动授权音频、切到UAC或恢复默认麦克风。最小视频路线不引入UAC库或LAME。 |
| durable采样、留存、OCR/上传 | 无。 | 继续使用主应用相应链路；当前模拟像素hash/preview不等于已持久化媒体。 |

官方libyuv的范围由固定 [格式说明](https://chromium.googlesource.com/libyuv/libyuv/+/3e38ce50589d9319badc0501f96d6c5b2b177472/docs/formats.md)及转换源码支持。固定UVCAndroid的fd包装见 [UVCCamera.cpp](https://github.com/shiyinghan/UVCAndroid/blob/fcdae5f9a194e2111d57019176b53470a120415b/libuvccamera/src/main/jni/UVCCamera/UVCCamera.cpp#L90)、[libuvc/device.c](https://github.com/shiyinghan/UVCAndroid/blob/fcdae5f9a194e2111d57019176b53470a120415b/libuvccamera/src/main/jni/libuvc/src/device.c#L282)，probe/commit、submit/cancel见 [stream.c](https://github.com/shiyinghan/UVCAndroid/blob/fcdae5f9a194e2111d57019176b53470a120415b/libuvccamera/src/main/jni/libuvc/src/stream.c)。源码中存在这些路径，不是设备兼容或关闭成功的实验证据。

## 与当前合成合同之间的缺口

| 当前固定实现事实 | 集成判定条件；本轮不改字段或实现 |
| --- | --- |
| [UvcDriver.Format](../../../src/local/sidecar/copilot/uvcsim/UvcDriver.java)只有width/height、encoding、fps分子/分母。`Frame`只有format、bootId、sequence、hostReceiveMonoNs和独占byte数组。 | 主集成需区分USB协商格式MJPEG/YUYV与解码后RGBA布局，明确stride/有效长度、颜色矩阵和范围、alpha、方向/rotation、是否连续。不能在一个encoding里暗换含义，也不能偷偷扩公共接口。 |
| [FramePipeline](../../../src/local/sidecar/copilot/uvcsim/FramePipeline.java)只接受640×360、`RGBA`、恰好921,600字节。 | 1080p会因格式被拒绝；不能自动resize成640×360来宣称符合PRD。fixture常量不是生产分辨率要求。主集成必须另定生产布局、遮盖坐标及尺寸变化处理。 |
| 当前owned数组限额7MiB，另预留单个≤1MiB合成生成器缓冲；队列容量2。 | 一个1080p RGBA数组8,294,400 B已超过7MiB限额。该预算不含USB、JPEG、JNI、Surface、转换scratch，也不覆盖任意native预分配；不能当作整体实机预算。 |
| `onFrame`即使拒绝也转移独占像素所有权，拒绝/释放会清零。 | UVCAndroid的DirectByteBuffer在同步回调后回收frame；必须在原回调有效期内进行计入预算的复制或可靠接管。不能交出复用byte数组/借用native地址后继续写它。 |
| start成功返回前的回调可能被Coordinator丢弃；允许乱线程及迟到回调。 | 原生bridge须容忍首帧拒绝，不把“发生回调”当作“已经开始发布”；旧handle/generation的晚回调必须丢弃和释放。 |
| `close`成功被合同假定已停止采集并释放原生资源，但已排队回调仍可迟到。 | 需要证明实际native关闭和资源所有权、阻塞open/start、异常close的结果；调用stop不等于完成。失败/不确定不能伪造已关闭或自动重新打开。 |
| StopReceipt确认设备任务/close；sink取消另行执行。`FramePipeline.close()`不等待worker退出。 | 不能将receipt或close方法返回解释为所有应用处理及内存已经排空。集成需沿现有边界区别停止发布、设备停止与缓冲最终清理；不在本轮增加新的回执字段/状态。 |
| 需要同bootId、递增sequence及合法的hostReceiveMonoNs；FramePipeline发布另防止过时帧覆盖。 | 规定同一单调时钟域及重连序列含义。主机接收时间不是设备曝光时间；需要设备时间戳时不能凭函数名填值。 |
| [AndroidUvcDiscovery](../../../android/local/sidecar/copilot/uvc/AndroidUvcDiscovery.java)只枚举描述符并读当前权限。 | 尚未拥有权限请求/广播、attachment epoch或授权revision生命周期。现有API35编译证据不等于Android权限和热插拔运行证据。 |

原生转换或库重建不能替代这些合同决策。独立审查同时确认：接收时间/sequence并不证明完整源连续性，人工核屏也不能自动识别相同尺寸的HDMI拓扑变化。

## 最小保留的原生组件

所有UVCAndroid vendored组件精确绑定 `fcdae5f9a194e2111d57019176b53470a120415b` 的源码归档，**不是用版本字符串推断一个未修改的上游release**。归档及各文件SHA在`inputs.lock.json`/`capabilities.json`；[NATIVE_SOURCES.md](NATIVE_SOURCES.md)提供索引和完整build文件位置。组件清单包含源树内所有普通文件，含测试/工具，并非最终编译闭包。

| 组件 | 保留原因 / 当前源边界 | 固定来源、版本与许可证据 | 未核清部分 |
| --- | --- | --- | --- |
| UVCCamera JNI桥与线程 | `UVCCamera/Android.mk`列11个C++编译单元：`_onload`、`utilbase`、`ConvertHelper`、`UVCCamera`、`UVCControl`、`UVCPreview`、`UVCButtonCallback`、`UVCStatusCallback`、`Parameters`、`registerUVCCamera`、`registerUVCControl`，另需相应头。初版保留JNI注册兼容边界。 | [固定Android.mk](https://github.com/shiyinghan/UVCAndroid/blob/fcdae5f9a194e2111d57019176b53470a120415b/libuvccamera/src/main/jni/UVCCamera/Android.mk)及项目Apache-2.0/文件头；Java声明版本1.0.13但此commit晚于发布tag。 | fd所有权、线程取消/关闭、Surface旁路、保留源码中内存安全、ABI及构建映射未通过实机/原生验证。不能只删Java UVCControl：JNI_OnLoad还注册它。 |
| libusb | Android fd包装、USB控制/异步传输和事件。`android/jni/libusb.mk`列core、descriptor、hotplug、io、sync、strerror及linux_usbfs/events_posix/threads_posix/linux_netlink共10个C单元。 | 固定[libusb.mk](https://github.com/shiyinghan/UVCAndroid/blob/fcdae5f9a194e2111d57019176b53470a120415b/libuvccamera/src/main/jni/libusb/android/jni/libusb.mk)；[version.h](https://github.com/shiyinghan/UVCAndroid/blob/fcdae5f9a194e2111d57019176b53470a120415b/libuvccamera/src/main/jni/libusb/libusb/version.h)声明1.0.25；COPYING和源码头为LGPL-2.1-or-later。 | 未将整个vendored树映射到唯一官方upstream commit；本地修改、最终链接/对应源码及适用替换材料仍需处理。动态链接不自动完成义务。 |
| libuvc | UVC描述符、probe/commit、传输帧装配及YUYV/JPEG辅助解码。当前Android.mk列10个C单元，包括`example.c`、ctrl、ctrl-gen、device、diag、frame、init、stream、misc、frame-mjpeg。 | 固定[Android.mk](https://github.com/shiyinghan/UVCAndroid/blob/fcdae5f9a194e2111d57019176b53470a120415b/libuvccamera/src/main/jni/libuvc/android/jni/Android.mk)；CMake声明0.0.6；[LICENSE.txt](https://github.com/shiyinghan/UVCAndroid/blob/fcdae5f9a194e2111d57019176b53470a120415b/libuvccamera/src/main/jni/libuvc/LICENSE.txt)及文件头BSD三条款。 | fixed fork的`uvc_wrap`、RGBX辅助路径不能等同未修改官方libuvc发布；移除example/diag或改通知前要查符号依赖。文字/stride/异常帧及关闭语义未验证。 |
| libjpeg-turbo | 当前MJPEG路径直接调用TurboJPEG；libuvc及可选libyuv JPEG构建亦依赖它。 | 固定vendored CMake声明2.1.2。前轮核对官方tag2.1.2指向 `ecf021bc0d6f435daacff7c35ccaeef0145df1b9`，该commit的`turbojpeg.h`、`LICENSE.md`与vendored对应文件逐字节相同。许可分IJG、BSD三条款及zlib部分。[前轮证据](../../followup/video-alternatives/README.md)。 | 仅两文件相同，不是整个JPEG源树对应证明。SIMD配置、架构选择、decoder内部内存和NOTICE/README.ijg等仍要核清；不要仅凭“只解码”删除同编译单元/静态库还需的编码对象。 |
| RapidJSON头文件 | `Parameters.cpp`用Writer/StringBuffer产生格式/能力JSON，Java UVCCamera解析它。 | 固定vendored源码、所用头的声明及[license.txt](https://github.com/shiyinghan/UVCAndroid/blob/fcdae5f9a194e2111d57019176b53470a120415b/libuvccamera/src/main/jni/rapidjson/license.txt)为MIT；未给整个thirdparty测试子树统一贴MIT标签。 | 尚未确定独立上游commit/准确release；保留现有格式JSON边界时不能直接删。测试/thirdparty目录不因此都成为运行时依赖，具体编译include闭包未执行。 |
| 官方 libyuv | 保留需要的RGB通道/格式转换，**整体替代**未核清的定制libyuv源/头/对象，不能混编旧row函数。 | 官方Chromium `3e38ce50589d9319badc0501f96d6c5b2b177472`（1840），[LICENSE](https://chromium.googlesource.com/libyuv/libyuv/+/3e38ce50589d9319badc0501f96d6c5b2b177472/LICENSE) BSD三条款，[PATENTS](https://chromium.googlesource.com/libyuv/libyuv/+/3e38ce50589d9319badc0501f96d6c5b2b177472/PATENTS)及AUTHORS已在前轮比对。 | 两处定制反向符号必须组合官方API或裁掉；颜色/alpha/stride/尾部和性能尚未验证。若关闭libyuv JPEG支持，还须去除`uvc_mjpeg2rgbx_new`等剩余JPEG符号引用。 |
| 平台/工具链运行库 | Android libc/libm/liblog/libandroid、JNI、线程及NDK所选C++运行库等。 | 通过最终选定SDK/NDK工具链；此轮未把其二进制或许可清单锁定为新依赖。 | 实际NEEDED项、C++runtime选型、ABI/链接、16KiB以及工具链许可材料由隔离构建产物确定。 |

这里保留的三个底层库不具有UAC输入实现；不要因采集卡是复合设备便把音频路径混入视频线程。除官方libyuv外，vendored来源的上游版本对应仍按上表保留未知。

## Java直接/传递组件：两种范围必须分开

以低层 `UVCCamera`、`USBMonitor` 及JNI注册的 `UVCControl` 为种子，去掉注释/字符串后按import和同包简单类型名形成保守候选，结果为12个文件，完整路径/SHA/边见 [JAVA_CANDIDATES.md](JAVA_CANDIDATES.md) 与JSON。**这不是javac或JNI编译闭包，也不证明反射、平台资源、consumer rules或运行时可达性完整。**

| 角色 | 保留候选 |
| --- | --- |
| 设备权限/连接 | `com.serenegiant.usb.USBMonitor`、`DeviceFilter`、`USBVendorId`、`com.serenegiant.utils.HandlerThreadHandler` |
| 低层相机及JNI合同 | `UVCCamera`、`UVCControl`、`UVCParam` |
| 协商格式数据 | `Format`、`Size`（均为上游类，不是Sidecar的UvcDriver.Format） |
| 原生回调签名 | `IFrameCallback`、`IStatusCallback`、`IButtonCallback` |
| 生成/平台输入 | namespace为`com.serenegiant.uvccamera`的BuildConfig；Android framework、Java运行库、平台org.json与org.xmlpull。不因此另加桌面XML/JAR依赖。 |
| 第三方import | `Format`、`Size`、`UVCParam`中的`androidx.annotation.NonNull`。候选集合未找到core、appcompat、legacy或Hutool的import。无import命中不代表最终链接/资源/反射证明。 |

项目及部分文件头给出Apache-2.0；个别数据文件没有独立头，这个项目级声明不能替代所有借用代码来源审查。现有固定归档的`libuvccamera/libs/`没有JAR文件；原Gradle仍有fileTree声明，最终源码快照不得悄然追加未审查本地JAR。

**保留原模块而不裁Java源时**，[固定build.gradle](https://github.com/shiyinghan/UVCAndroid/blob/fcdae5f9a194e2111d57019176b53470a120415b/libuvccamera/build.gradle#L67)仍直接声明：

- `androidx.core:core:1.13.1`
- `androidx.legacy:legacy-support-v4:1.0.0`
- `androidx.appcompat:appcompat:1.6.1`
- `androidx.annotation:annotation:1.8.0`

从前轮已锁定POM模型重算这四个根的保守compile/runtime并集为 **117个坐标版本、4个额外parent/BOM模型、24组多版本冲突**。全部坐标、来源POM URL/SHA、已有主制品SHA和POM许可证见 [DECLARED_DEPENDENCIES.md](DECLARED_DEPENDENCIES.md)。没有重新下载或解析Gradle。POM-only许可证不是完整源许可结论；同一个库的多个版本不意味着都会进入APK。

**仅保留上述12个候选Java文件时**，仍引用的外部注解根可先研究 `androidx.annotation:annotation:1.8.0`。该根的旧POM并集包含下列5个坐标，不可简单宣称“只有一个注解JAR”或“这5个必须打包”：

| 坐标 | POM声明关系 / 声明许可 |
| --- | --- |
| androidx.annotation:annotation:1.8.0 | 指向annotation-jvm和Kotlin stdlib；Apache-2.0声明 |
| androidx.annotation:annotation-jvm:1.8.0 | compile依赖kotlin-stdlib 1.7.10；Apache-2.0声明 |
| org.jetbrains.kotlin:kotlin-stdlib:1.7.10 | 指向stdlib-common和JetBrains annotations；Apache-2.0声明 |
| org.jetbrains.kotlin:kotlin-stdlib-common:1.7.10 | 此POM无后续依赖；Apache-2.0声明 |
| org.jetbrains:annotations:13.0 | 此POM无后续依赖；Apache-2.0声明 |

实际JVM/Android变体、annotation使用范围、compileOnly策略、既有项目版本统一、R8/打包和最终NOTICE仍未解析。是否只作编译输入、复用主项目已有注解或修改注解使用，应由主集成在明确源保留范围后决定，本轮没有擅改源或依赖。

**Hutool不在此源码候选的四个根中。** Maven `com.herohan:UVCAndroid:1.0.13` 的发布tag是较早的 `6018c7f...`，发布元数据含 `hutool-core:5.8.35`；其完整POM并集119个版本仍属于前轮发布审计。不能把117/5的源码候选推导套到现有AAR，也不能在旧AAR上直接排除Hutool来宣称已修复。Hutool混合许可及源码对应维持[原证据](../../hutool/README.md)。

## 可操作的构建裁剪边界

初版隔离源目标应从上述12个Java候选及所列原生组件边界评估，保留需要的JNI签名和能力JSON；六组件清单描述调查对象，不表示libyuv必需。仅在确认还需相应转换时才保留官方libyuv。候选不复制demo、高层`com.herohan.uvcapp`录照/录像/音频帮助器或UI部件，采用主项目自己的、可审查的构建输入。以下裁剪是候选设计，尚未证明能编译：

- 以RGBX/RGBA视频回调为所需边界；显式处理`ABGRToYUY2`、`ABGRToRGB565`引用，或在确认不需要这些回调后同时裁掉实现与选择分支。仅“不调用它们”不能保证不存在链接引用。
- 保留当前MJPEG/TurboJPEG及YUYV/libuvc入口，先解决字节、alpha、矩阵与stride合同；不要顺手全部改成官方libyuv MJPEG解码。细节见[四API替代报告](../../followup/video-alternatives/README.md)。
- 如需libyuv，使用完整的固定官方源/头选项；未来如关掉其JPEG部分或完全去掉libyuv，必须证明桥接源不再引用其对应函数。libjpeg-turbo仍因当前MJPEG解码需要而保留。
- `_onload.cpp`同时注册UVCCamera与UVCControl，Parameters依赖RapidJSON；第一版不为少量文件删改这些ABI/数据合同。进一步裁掉回调、controls、diag/example或JSON库前，应取得符号/引用证据。
- 不直接合并上游manifest的RECORD_AUDIO、MANAGE_EXTERNAL_STORAGE、READ/WRITE_EXTERNAL_STORAGE和全部服务权限。视频目标只携带主应用实际需要且已定义的声明；CAMERA/USB/foreground能力由实际Android路径和目标SDK确定，库声明不等于已获授权。
- 上游固定根声明AGP8.5.1、wrapper Gradle8.7、SDK34/min21/Java8；库还带发布插件0.34.0。`Application.mk`另写android-16。它们是源证据，不是本轮选定或完整校验的工具链；NDK、C++runtime、API优先级、构建插件/传递图尚未锁定。发布插件不应因读取源码而变成视频运行时依赖。

实际编译/链接应证明选定路径不再引用那四个定制libyuv API、没有旧定制对象/预编译库混入，并完整保留适用许可证/通知。使用官方源只关闭其定制实现这一项来源问题，不会自动关闭libusb/libuvc/JPEG/工具链或整个产品的许可和来源条件。

## 成本：输入fps不等于候选fps

| 成本项 | 可推导量 / 不能据此承诺的事项 |
| --- | --- |
| 1080p RGBA单面 | `1920*1080*4 = 8,294,400 B`，约7.91MiB。只是一个连续像素面，不含队列及设备数据。当前fixture的7MiB owned额度容不下它。 |
| 1080p30一次CPU复制 | 按每字节读一次、写一次，理想流量 `8*1920*1080*30 = 497,664,000 B/s`。这是算式，不是实测吞吐、CPU占用或总线速度；多次复制/遮盖/散列、padding/cache效果及GPU队列另计。 |
| 主机收到1–5fps候选 | 若仍以30fps接USB、解码30fps后在Java丢弃，USB传输和解码成本仍在；只有被减少的后续阶段受益。不能从候选fps推出设备发帧fps。提前丢弃压缩帧/降低USB模式需要另证正确性及PRD符合性，非现有实现结论。 |
| 两段官方反向转换 | 整帧ABGR→ARGB→YUY2至少源4P、临时4P、目的2P；官方ARGBToYUY2 tight stride还会行合并并分配约2P scratch，合计约12P，即1080p约23.73MiB，仍不含解码/USB/Surface。 |
| 按行组合候选 | ARGB scratch `4W`，YUY2内部约`2*roundUp64(W)`，1920宽约11,520 B；但内部逐次分配/派发开销和时间尚未测，不能声称一定更快。 |
| 保留上游线程/池 | UVCPreview原生frame pool、压缩帧、解码目标、JNI复制、Sidecar队列和遮盖后preview都可能并存。先做整体所有权/容量上限和背压方案，不能只给Java数组计数。 |

同色彩、同尺寸是对比前提；不得用缩小画面、改变屏幕时序或降低文字可读性掩盖开销。官方libyuv不是EDID、主屏路由或采集卡协商修复器。

## 主集成的路线判定条件

可以继续最小源候选的理由：它保留负责UVC访问的底层链路，能明确隔离来源未核清的libyuv定制代码，并有较小的Java候选范围；整个原模块及发布AAR则携带更广的源/依赖/权限范围。选择前须明确下列结果，保持G0/G1/G2原有含义：

1. **能力合同可写清**：所选卡的USB模式和应用像素输出分别可表达，尺寸/stride/颜色/alpha/时间/连接身份明确；fixture接口和预算缺口已由主集成另定，不能隐式resize或硬塞进现接口。
2. **源与构建范围可核对**：12个Java只是候选而非保证；最终Java/JNI/原生选源、生成类、依赖变体、工具链和许可材料有可复核清单，旧定制符号及对象退出实际产物。未取得这一步前不能声称“小依赖闭包已锁定”。
3. **生命周期与数据边界成立**：USB授权/attachment代次、open/start/close和晚回调绑定现有generation；遮盖前原始帧不进入可见Surface、文件或上传；停止先拒绝发布，设备及应用清理分别取得可解释结果。
4. **成本可承担而不改冻结体验**：基于真实输入fps/格式测量整个接收—解码—复制—遮盖—发布链的内存、CPU/热、延迟和背压；模式/屏幕变化沿用暂停人工核屏，不静默调主屏或换麦。
5. **实际设备条件验证**：USB枚举/probe/commit成功、主屏时序保持、文字/颜色可读、权限撤销/拔插/关闭/恢复、最终ABI/16KiB及既有长期运行要求需要相应设备证据。API或README不替代它们；视频成功也不证明UAC或双向音源成功。

现阶段结论是“值得做最小范围的隔离源可行性工作”，不是实机准入、性能承诺或生产接入完成。

## 证据和复跑

`inventory.py`仅用Python标准库，读取8个固定输入：两个旧源码归档、4个当前核心接口/管线文件、发现器、前轮传递依赖JSON。它不联网、复制候选源码至构建、安装工具或调用Maven/Gradle/native。全部源码哈希、Java候选边、117/5两套POM子集及六个原生源子树在`capabilities.json`，并生成三个索引Markdown。

```powershell
python -B -O android\uvc-sim\provenance\feasibility\capabilities\inventory.py --source-cache C:\Users\USER\Documents\Codex\2026-11-10\task-4\sidecar-uvc-licenses\android\uvc-sim\build\provenance\libyuv
python -B -O -m unittest discover -s android\uvc-sim\provenance\feasibility\capabilities -p test_inventory.py -v
```

默认比较全部既有输出，缺输出或内容不同均失败；只有显式`--write-report`在输入校验及派生成功后生成/重写输出，输入锁不会自动刷新。旧缓存只读；合成检查临时文件仅在`android/uvc-sim/build/feasibility/capabilities/`。前轮POM/制品哈希是已存证据的再分析，不代表本轮又做了完整Gradle决议或源许可证扫描。

**旧归档重建限制：**本脚本依赖前轮已有归档的原始字节锁，不提供在线下载/自动重建。固定commit的Gitiles `tar.gz`重新生成时，归档容器字节可能变化，即使源码内容仍相同，也会被现有raw SHA正确拒绝。不可据此自动刷新旧锁或称新下载已等价。新机器若没有这些缓存，应使用另行审查的固定commit单文件Git blob/decoded-source身份方案，再显式形成新证据；当前目录不悄然迁移该信任边界。JSON中的逐文件hash可帮助后续比较，但本轮没有实现接受新归档的等价判定。
