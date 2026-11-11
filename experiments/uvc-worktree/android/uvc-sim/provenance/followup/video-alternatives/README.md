# libyuv 定制转换的视频替代研究

结论：优先研究“固定 UVCAndroid 源码 + 未修改的官方 libyuv + 小范围适配层”的隔离构建。第一阶段保留现有 MJPEG/TurboJPEG 与 YUYV/libuvc 解码入口，以 RGBX 字节流作为视频边界；只有确实需要保留的 YUYV、RGB565 回调才组合官方转换 API。**这是候选重建路线，没有构建、性能、颜色等价或设备准入结论。** 不建议把四个定制函数同时按名字机械替换。

本研究遵守已有 PRD 边界：不安装电脑 agent/驱动，不改变主屏选定时序；文字可读性需要实际验证；输入变化时沿用暂停和人工核屏；视频与音频授权分离，不换麦；保留现有停止屏障和遮盖。没有新增功能、状态或验收编号。G0 模拟工作可继续，以下源码事实不能替代 G1/G2 实机证据。

## 固定来源与证据范围

| 输入 | 固定身份 / 本轮用途 |
| --- | --- |
| UVCAndroid | [`fcdae5f9a194e2111d57019176b53470a120415b`](https://github.com/shiyinghan/UVCAndroid/tree/fcdae5f9a194e2111d57019176b53470a120415b)，源码归档 SHA-256 `2b48b1f8b4fd71b92dcb9654c63ad70b2f757a819f62d2a054a7d606210a449f`。本轮研究这个源码候选，不把它当作 Maven 1.0.13 的生产构建。 |
| 官方 libyuv | Chromium 官方 [`3e38ce50589d9319badc0501f96d6c5b2b177472`](https://chromium.googlesource.com/libyuv/libyuv/+/3e38ce50589d9319badc0501f96d6c5b2b177472)，归档 SHA-256 `49998fb4ff4dd5438c7170d47c8861cbe33c842315bb0d021da4d8e4f993ff2f`；历史版本号 1840，只用于这条固定候选路线。 |
| 官方 libjpeg-turbo | 官方 tag `2.1.2` 的观察 ref 指向 [`ecf021bc0d6f435daacff7c35ccaeef0145df1b9`](https://github.com/libjpeg-turbo/libjpeg-turbo/tree/ecf021bc0d6f435daacff7c35ccaeef0145df1b9)。只核对固定 `turbojpeg.h` 和 `LICENSE.md`，两文件与 UVCAndroid 所带对应文件逐字节相同；没有声称整个 vendored JPEG 树未经修改。 |
| Camera2 | Android 官方平台及 API 文档，2026-11-10 查阅；属于平台条件说明，未固定或检查任何手机的系统镜像/HAL。链接在比较表及停止语义段落。 |

`inputs.lock.json` 固定 5 个输入，`evidence.json` 记录原始 SHA、大小、原生文件清单、选定源码行及精确 URL。扫描 UVCAndroid 的 499 个原生源码/头文件/make 文件与官方 libyuv 的 110 个此类文件；额外保留 37 个关键文件的证据。这里是**字面标识符位置及源码分支分析，不是编译调用图**：不解析所有宏、动态符号查找、链接裁剪或外部调用者，也没有 ABI/符号表验证。

## 找到的调用位置和编译依赖

| 源码位置 | 静态观察 | 对最小替代的影响 |
| --- | --- | --- |
| [UVCPreview.cpp:573–615](https://github.com/shiyinghan/UVCAndroid/blob/fcdae5f9a194e2111d57019176b53470a120415b/libuvccamera/src/main/jni/UVCCamera/UVCPreview.cpp#L573) | MJPEG 分支调用 `uvc_mjpeg2rgbx_tj`；YUYV 分支调用 `uvc_yuyv2rgbx`。 | 所见预览分支没有调用新增 `MJPGToABGR` 或 `YUY2ToABGR`。 |
| [ConvertHelper.cpp:179](https://github.com/shiyinghan/UVCAndroid/blob/fcdae5f9a194e2111d57019176b53470a120415b/libuvccamera/src/main/jni/UVCCamera/ConvertHelper.cpp#L179) | `uvc_rgbx_to_yuyv` 调用定制 `ABGRToYUY2`。 | 由 [RAW/YUV 回调选择](https://github.com/shiyinghan/UVCAndroid/blob/fcdae5f9a194e2111d57019176b53470a120415b/libuvccamera/src/main/jni/UVCCamera/UVCPreview.cpp#L277)引用；RGBX 回调不走转换函数。 |
| [ConvertHelper.cpp:309](https://github.com/shiyinghan/UVCAndroid/blob/fcdae5f9a194e2111d57019176b53470a120415b/libuvccamera/src/main/jni/UVCCamera/ConvertHelper.cpp#L309) | `uvc_rgbx_to_rgb565` 调用定制 `ABGRToRGB565`。 | RGB565 回调分支引用；不应为文字画面默认选择较低精度的 RGB565。 |
| 官方 libyuv 全部被扫描原生文件 | 四个定制 API 的完整标识符均不存在。UVCAndroid vendored libyuv 外只有上面两处标识符命中。 | 不能原样替换源目录后假定成功链接，也不能仅“不选择回调”便认定链接引用已消失。 |

当前 [Gradle 构建配置](https://github.com/shiyinghan/UVCAndroid/blob/fcdae5f9a194e2111d57019176b53470a120415b/libuvccamera/build.gradle#L57) 选择 ndk-build 的 `jni/Android.mk`。它纳入 jpeg-turbo、libyuv、libusb、libuvc、UVCCamera；[UVCCamera/Android.mk](https://github.com/shiyinghan/UVCAndroid/blob/fcdae5f9a194e2111d57019176b53470a120415b/libuvccamera/src/main/jni/UVCCamera/Android.mk#L50) 编译 `ConvertHelper.cpp` 并链接 `usb1.0`、`uvc`、`jpeg-turbo212`、`yuv`。因此必须在隔离构建中明确替代两处引用，或明确裁掉未需要的回调实现与选择分支。

vendored [libyuv/Android.mk](https://github.com/shiyinghan/UVCAndroid/blob/fcdae5f9a194e2111d57019176b53470a120415b/libuvccamera/src/main/jni/libyuv/Android.mk#L54) 默认加入 JPEG 解码源、`HAVE_JPEG`，并改为链接 `jpeg-turbo212`；官方历史 Android.mk 的 JPEG 库名字不同。未来应在项目自己的构建适配中显式绑定源文件、头路径、JPEG 目标与 ABI，不移植来源未清的转换实现。`ConvertHelper.cpp` 中未被所见预览引用的 `uvc_mjpeg2rgbx_new` 还引用 `MJPGToNV21`；若裁掉 libyuv JPEG 支持，也必须处理这个编译/链接输入。仓库 CMake 路线未纳入 libyuv、UVCCamera CMake 也未列 ConvertHelper，不能当成已验证的等价入口。16 KiB 链接选项仍需要最终产物和运行时验证。

## 字节、颜色、步长不能靠名称推断

1. 官方 [formats.md](https://chromium.googlesource.com/libyuv/libyuv/+/3e38ce50589d9319badc0501f96d6c5b2b177472/docs/formats.md) 以寄存器顺序命名 ARGB。小端内存中 ARGB 为 `B,G,R,A`，ABGR 为 `R,G,B,A`；Android 预览的 RGBA/RGBX 内存因此应对应 libyuv ABGR，而不是 libyuv 名称中的 RGBA。YUY2 是 `Y0,U0,Y1,V0` 的打包 4:2:2；所有 stride 参数要以相应 API 的字节单位核对。
2. 当前 TurboJPEG 入口传 `TJPF_RGBX` 和 pitch 0。官方固定 [turbojpeg.h](https://github.com/libjpeg-turbo/libjpeg-turbo/blob/ecf021bc0d6f435daacff7c35ccaeef0145df1b9/turbojpeg.h#L180) 明确 X 在解码后未定义；`TJPF_RGBA` 才保证 alpha 为 255。当前预览创建的是 RGBA window，不能把未定义的 X 当作已验证的不透明 alpha。候选适配应明确输出合同，例如经验证选择 `TJPF_RGBA`，并检查容量与 pitch；本轮未修改它。
3. 当前 [libuvc YUYV 转换宏](https://github.com/shiyinghan/UVCAndroid/blob/fcdae5f9a194e2111d57019176b53470a120415b/libuvccamera/src/main/jni/libuvc/src/frame.c#L510) 使用原 Y 加约 1.403V、1.773U 的 601 全范围式系数，没有减 16/乘 1.164。官方 [`YUY2ToARGB`](https://chromium.googlesource.com/libyuv/libyuv/+/3e38ce50589d9319badc0501f96d6c5b2b177472/source/convert_argb.cc#3963) 固定采用 `kYuvI601Constants`，即 601 limited 路线。前者的整数舍入与后者也不同；替换会改变黑白级和彩色文字，不能声称位等价。实际采集卡的颜色范围/矩阵仍未证明。
4. 定制 `YUY2ToABGR` 传 `kYvuI601Constants`，其 C row 却保留 U、V 实参顺序并换写出通道；官方 `I422ToABGR` 同时交换 U/V 和镜像矩阵。这个组合是额外语义疑点，不能以函数名推定定制版本正确，亦未做运行验证。定制 `ABGRToRGB565Row_C` 的双像素分支把 R 取自字节 0，而奇数尾分支把 R 取自字节 2，存在可见的静态不一致。替代目标应是明确的像素合同，而不是复制这些可疑行为。
5. 官方历史 [`MJPGToARGB`](https://chromium.googlesource.com/libyuv/libyuv/+/3e38ce50589d9319badc0501f96d6c5b2b177472/source/convert_jpeg.cc#465) 支持所列 4:2:0、4:2:2、4:4:4、灰度分支，调用的是 `I420/I422/I444/I400ToARGB`；这些入口使用 limited 路线。定制 ABGR 版沿用对应 I 系入口。它们不能当成当前 TurboJPEG 解码的全范围等价物，也不代表支持所有 JPEG 颜色空间/采样因子、损坏帧或缩放情况。
6. [ConvertHelper.cpp](https://github.com/shiyinghan/UVCAndroid/blob/fcdae5f9a194e2111d57019176b53470a120415b/libuvccamera/src/main/jni/UVCCamera/ConvertHelper.cpp#L158) 多个函数在 `uvc_ensure_frame_size` 前保存 `out->data`；而 [ensure 函数](https://github.com/shiyinghan/UVCAndroid/blob/fcdae5f9a194e2111d57019176b53470a120415b/libuvccamera/src/main/jni/libuvc/src/frame.c#L42) 可 realloc。需要在成功分配后重新取得指针，并以 checked arithmetic 验证宽高、每行可读写字节、实际长度、stride 和目标容量。`uvc_mjpeg2rgbx_new` 还有把 `in` 结构体地址当作 NV21 平面的明显静态问题，不能选为替代入口。现有 libuvc 转换按 8 像素块、Surface 拷贝使用自己的宽度/stride 假设；它们都需要边界验证。

## 四个 API 的候选替代映射

下表只是可由固定官方源码支持的组合方向，**不是已实现或已测试的适配代码**。正尺寸、明确方向、独立缓冲和已验证的矩阵/范围是共同前提；本轮不假定允许源目的地址重叠。

| 定制入口 | 官方组合候选 | 语义和成本边界 |
| --- | --- | --- |
| `YUY2ToABGR` | `YUY2ToARGB` → `ARGBToABGR`；或 `YUY2ToI422` → `I422ToABGR` | 前者两遍、额外 4 字节/像素；后者保留 4:2:2，平面 scratch 为 `W+2*ceil(W/2)` 每行或每帧乘 H。默认是 601 limited；需要其他范围时选择明确的 matrix 入口。不得经 I420 降为 4:2:0 来假装等价。当前预览没有找到此定制入口的外部字面调用。 |
| `MJPGToABGR` | `MJPGToARGB` → `ARGBToABGR` | 增加一个 4WH 临时面和一次通道遍历，另有解码器内部缓冲；历史实现的矩阵/采样支持如上，不优先替换当前 TurboJPEG 解码。不要借此自动缩小画面。 |
| `ABGRToRGB565` | `ABGRToARGB` → `ARGBToRGB565` | 官方通道交换是无损排列；随后量化到 R5/G6/B5。需核对小端打包、stride、奇数宽、SIMD尾部和显示合同。不能宣称复制了定制 C 尾部结果；不作为文字预览默认格式。 |
| `ABGRToYUY2` | `ABGRToARGB` → `ARGBToYUY2` | 输出 4:2:2 的 YUYV 顺序，官方 RGB→YUV 系数有 16/128 偏移及舍入；色度按相邻像素平均。整帧 tight stride 会触发行合并与内部大 scratch，见下一节。尽量不要做不必要的 RGBX→YUYV→RGB 往返。 |

官方通道互换、RGB565、YUY2 的定义分别见 [convert_argb.cc](https://chromium.googlesource.com/libyuv/libyuv/+/3e38ce50589d9319badc0501f96d6c5b2b177472/source/convert_argb.cc#2790)、[convert_from_argb.cc](https://chromium.googlesource.com/libyuv/libyuv/+/3e38ce50589d9319badc0501f96d6c5b2b177472/source/convert_from_argb.cc#884)、[row_common.cc](https://chromium.googlesource.com/libyuv/libyuv/+/3e38ce50589d9319badc0501f96d6c5b2b177472/source/row_common.cc#305)。C/SIMD 派发、负高度、stride 与 round/truncate 差异未做执行比对。

## 路线比较和最小决定

| 路线 | 移除未核清定制代码的方式 | 文字/时序与内存影响 | 尚未关闭的条件 | 选择 |
| --- | --- | --- | --- | --- |
| 保留当前解码入口 + 官方 libyuv | 用完整固定官方树替换定制树；保留必要通道转换。两处反向引用显式组合或在内部裁剪。 | RGBX 直出避免再做色度降采样；不改变 USB/HDMI 模式。按行反向转换可限制 scratch。 | 颜色合同、alpha、指针/stride、构建选源、性能、停止和实机均待验证。 | **优先的隔离源码候选。** |
| 全面转为官方 libyuv 前向解码 | 四个映射全部走官方组合。 | 统一一套 API，但可能改变 JPEG/YUYV 的范围/矩阵，增加遍历和缓冲。 | 更多像素等价及解码格式差异；并不减少 JPEG 依赖。 | 暂不作为最小路线。 |
| 仅 libuvc + 官方 TurboJPEG 直接 RGBX/RGBA | 所需视频适配只保留 transport/decode；不编译 libyuv 及全部相关回调帮助函数。 | 可少一次转换且少一个库；保留原始图像尺寸。 | 仓库的 `uvc_yuyv2rgbx`/`uvc_mjpeg2rgbx` 是固定 fork 中的实现，不能称为未修改的上游 libuvc API。需要更明确的内部接口裁剪、libuvc 算术/stride审查与 JPEG 构建来源。 | 可比较的更小变体；先证明 PRD所需输出只需 RGBX，再评估，非已获准整体重写。 |
| 系统 Camera2 external camera | App不打包这组 libyuv 定制实现，使用平台交付的相机 provider。 | 输出格式、帧率、分辨率受设备 HAL/config 限制；可能还需 YUV_420_888→RGBA。 | 目标系统必须已有 UVC kernel、USB host、external provider配置；API存在不能保证手机枚举采集卡或保持需要的模式。 | 条件性备用方案，不能当通用替换。 |

Android 官方 [external USB camera 文档](https://source.android.com/docs/core/camera/external-usb-cameras)明确依赖设备端 kernel/provider 配置，且指出此通用实现有性能用途边界。文档给出的尺寸、fps 示例不是任意手机的保证。应用不能通过普通 Camera2 调用补上缺失的厂商 HAL；本项目也不把改系统镜像或电脑驱动作为解决方案。只有目标手机现有系统真实枚举、可选流组合和画面验证通过，Camera2 路线才可推进。

最小顺序：先确定视频边界的 RGBX/RGBA、范围、alpha、尺寸、stride、时间戳和所有权合同；保留已读源代码中的解码入口，禁止未审查的辅助路径；用未修改官方 libyuv 加项目自有适配构建输入，显式消除两处定制符号；随后在独立许可的测试阶段以生成图样检查色条、黑白级、细字、奇数宽、带padding行、无效JPEG、取消竞态及预算。只有取得相应授权后才进行隔离构建与后续实机工作；本次研究没有执行这些步骤，也不新增审批流程或PRD编号。

## 内存、步长与停止屏障

设 P=WH，下面只计理想连续像素面，不含源压缩帧、解码器内部数据、libusb传输、上游frame pool、JNI对象、Surface/HAL队列或既有遮盖/编码缓冲；这些均需计入现有预算，不能另建无上限池。

| 项目 | 大小 / 1920×1080示例 |
| --- | --- |
| RGBX/RGBA 一面 | 4P = 8,294,400 B，约 7.91 MiB |
| YUYV或RGB565 一面 | 2P = 4,147,200 B，约 3.96 MiB |
| 整帧通道交换临时面 | 额外 4P，约 7.91 MiB；RGBX源、ARGB临时面、YUYV目的面合计至少 10P。 |
| `ARGBToYUY2` 内部 scratch | 官方代码在 tight stride 时先 `width *= height`，再申请 `2*roundUp64(width)`。所以整帧组合可能再加约 2P，使上述峰值达到约 12P = 23.73 MiB，仍未计其余池/解码/Surface。 |
| 按行组合候选 | 自有 ARGB 行缓冲 4W，加YUY2函数内部 `2*roundUp64(W)`，1920宽约11,520 B；每行调用仍有官方内部申请/派发成本。RGB565组合只需这4W行缓冲及其小型实现细节。性能未测，不能声称更快。 |

只用完整、验证过的偶数宽 YUYV布局；若设备实际布局不合合同应按现有变化/失败流程暂停，不能静默裁宽或调主屏时序。按行适配每次使用已核对的原始行地址/stride和height=1，保持色度相邻像素关系；不得对相邻帧共享可写scratch，也不能未经验证原地交换。所有乘法、对齐和最后一行可访问范围都要先做溢出及容量检查。

[UVCPreview.cpp:855–882](https://github.com/shiyinghan/UVCAndroid/blob/fcdae5f9a194e2111d57019176b53470a120415b/libuvccamera/src/main/jni/UVCCamera/UVCPreview.cpp#L855)把原生地址包成 DirectByteBuffer，同步回调后立即回收frame。Sidecar不能保留这个裸地址供异步处理；必须在回调有效期内按现有预算复制/接管到受控缓冲，并维持遮盖前后所有权和清理。原生 `draw_preview_one` 会直接提交Surface；不能绕过已有授权、遮盖与generation发布检查去接用户可见Surface。停止/拔插/模式变化必须先使旧generation不能发布，随后排空回调、释放/清理缓冲，沿用现有恢复与人工核屏要求。

Camera2也不改变这一原则：[stopRepeating](https://developer.android.com/reference/android/hardware/camera2/CameraCaptureSession#stopRepeating())允许已在途请求完成，`abortCaptures`也可能产生完成回调，故单独调用API不等于停止发布屏障。使用ImageReader时按每个 [Image.Plane](https://developer.android.com/reference/android/media/Image.Plane) 的row/pixel stride及最后一行有效容量读；Image关闭后不能再访问其buffer。不能假定YUV_420_888总是连续I420或NV21。视频会话的创建、恢复或后端选择不得触发音频授权、选择UAC输入或静默换麦。

## 许可证与后续验证边界

官方固定 libyuv 的 [LICENSE](https://chromium.googlesource.com/libyuv/libyuv/+/3e38ce50589d9319badc0501f96d6c5b2b177472/LICENSE)、[PATENTS](https://chromium.googlesource.com/libyuv/libyuv/+/3e38ce50589d9319badc0501f96d6c5b2b177472/PATENTS)、[AUTHORS](https://chromium.googlesource.com/libyuv/libyuv/+/3e38ce50589d9319badc0501f96d6c5b2b177472/AUTHORS)均包含在核验归档中，分别为BSD三条款、Google专利授予文本、历史作者名单；原始SHA见[前轮libyuv报告](../../libyuv/README.md)。选择官方源并不自动清除其它依赖义务；未来适配只调用官方API，不复制来源未核清的定制实现，仍需保留适用声明并审核最终选源/链接/分发材料。

官方固定 [libjpeg-turbo LICENSE.md](https://github.com/libjpeg-turbo/libjpeg-turbo/blob/ecf021bc0d6f435daacff7c35ccaeef0145df1b9/LICENSE.md)区分IJG、BSD三条款及zlib部分，不能把使用TurboJPEG理解为只需一个BSD标签。libuvc固定fork的 [LICENSE.txt](https://github.com/shiyinghan/UVCAndroid/blob/fcdae5f9a194e2111d57019176b53470a120415b/libuvccamera/src/main/jni/libuvc/LICENSE.txt)及相关源码头也须保留；libusb、Java/AndroidX、工具链和最终原生制品来源/许可条件仍在原评审范围内。两个JPEG文件字节相同不等于全部JPEG库审计完成，官方libyuv存在某API也不等于硬件兼容。

后续需真实关闭：选源与全部编译依赖清单、未定义定制符号归零、颜色/alpha/stride和尾部图样、压缩尺寸与损坏帧处理、实际采集卡时序及文字可读性、CPU/热/内存/延迟、取消/断开/恢复和零陈旧发布、最终ABI与16 KiB设备行为，以及完整NOTICE/对应源码/二进制映射。当前没有哪条路线因这份报告得到G1/G2放行。

## 复跑

仅Python标准库；默认离线，现有旧缓存只读：

```powershell
python -B android\uvc-sim\provenance\followup\video-alternatives\inspect_sources.py --existing-cache C:\Users\USER\Documents\Codex\2026-11-10\task-4\sidecar-uvc-licenses\android\uvc-sim\build\provenance\libyuv
python -B -m unittest discover -s android\uvc-sim\provenance\followup\video-alternatives -p test_inspection.py -v
```

自己的补充缓存位于 `android/uvc-sim/build/native-research/video-alternatives/`；两个归档可直接从旧缓存读取，不复制/解压至源码。新机器可用`--online`取得缺失的锁定输入，下载后必须匹配SHA和大小；metadata外观变化也会失败，需要人工评审新证据，不静默改锁。常规复跑只比较既有`evidence.json`，缺报告会在读取输入前失败非零，不会把缺少比较基准当作成功。只有显式`--write-report`才在所有输入和预期扫描条件成功后首次生成或重写证据。缺输入、改字节、额外/缺失预期符号、异常归档路径均失败非零。程序不会生成或覆盖`inputs.lock.json`。

本轮锁定离线生成通过，发现2处vendored libyuv外定制标识符；5项合成完整性检查通过（改内容、改内容并另放伪收据、离线缺输入、缺报告、归档路径越界）。缺报告负例还确认未读取输入、未隐式生成报告。没有提取执行归档、编译上游、引入运行时依赖、连接设备、发布或提交Git。
