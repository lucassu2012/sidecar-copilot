# 可选 MJPEG 解码器：官方 libjpeg-turbo 源码路线

本目录只核验文本来源、许可证与构建条件，没有配置或编译上游工程，没有下载发布资产，也没有执行任何 JPEG 解码。当前不能据此将库或二进制加入产品。

## 固定来源和维护观察

- 仓库：[libjpeg-turbo/libjpeg-turbo](https://github.com/libjpeg-turbo/libjpeg-turbo)。
- 固定版本 **3.2.1**，commit **7b4993786d856cbccabfc0ed2f923ba000e326cc**。
- 官方 [release](https://github.com/libjpeg-turbo/libjpeg-turbo/releases/tag/3.2.1) 的 API 在 2026-11-10 观察到发布于 **2026-10-27T20:58:25Z**，非 draft、非 prerelease，仓库未归档。记录见 [observation.json](observation.json)。这是观察时的版本选择依据，不是长期维护承诺、安全结论或 Android 构建成功证据。
- [inputs.lock.json](inputs.lock.json) 固定 6 个源文件、许可文件或构建说明的 URL、SHA-256、长度与 Git blob SHA-1。前 4 个 blob 额外对照过固定提交的 GitHub 根目录 contents 元数据；另 2 个 blob 仅由取得的字节计算。没有声称完整源码树成员校验或完整传递依赖清单。

## 为什么需要单独决策

libuvc 提供 USB 视频流及帧封装；MJPEG payload 仍需 JPEG 解码。官方 libuvc 的 optional JPEG support 要通过确切构建图检查，不能让系统 find_package(JPEG) 随机选择机器上的库，也不能直接沿用旧 AAR 中修改过的 JPEG 二进制。可以禁用 libuvc 内置 JPEG 路径，由自有解码适配器调用固定的 libjpeg API；该构建组合目前未执行。

[固定 jpeglib.h](https://github.com/libjpeg-turbo/libjpeg-turbo/blob/7b4993786d856cbccabfc0ed2f923ba000e326cc/src/jpeglib.h#L278) 说明 RGBX 的 X 字节未定义，而 JCS_EXT_RGBA 指定不透明 alpha；同文件声明 jpeg_mem_src / jpeg_read_header / jpeg_read_scanlines。因此候选适配器可直接解码到自有 RGBA 缓冲，避免额外使用 libyuv 或 RGB565/YUY2 中间格式。API 声明不证明具体采集卡的 MJPEG（例如缺失表、截断帧、异常尺寸）可被接受；尺寸、采样、输出容量、错误恢复和退出后的缓冲清理仍须验证，不能让默认 error_exit 终止应用。

仅选 YUYV 可以省掉 JPEG 解码依赖，但是否满足传输带宽、清晰度和冻结显示链路要求须由被选组合证明；不能为了删依赖擅自降低清晰度或刷新率。

## 构建依赖边界与许可

[顶层 CMakeLists.txt L722–790](https://github.com/libjpeg-turbo/libjpeg-turbo/blob/7b4993786d856cbccabfc0ed2f923ba000e326cc/CMakeLists.txt#L722) 与 [sharedlib/CMakeLists.txt L33–56](https://github.com/libjpeg-turbo/libjpeg-turbo/blob/7b4993786d856cbccabfc0ed2f923ba000e326cc/sharedlib/CMakeLists.txt#L33) 显示，libspng/zlib 的构建分支受 WITH_TURBOJPEG OR WITH_TOOLS OR WITH_TESTS 控制。只写 WITH_TURBOJPEG=OFF 不够；WITH_TOOLS/WITH_TESTS 也需显式关闭，WITH_JNA 需为空（顶层 L311–314 可把 TurboJPEG 重新打开），并限定只交付 jpeg 目标。这是静态构建候选，不是已经生成的最小二进制闭包；禁用工具/上游测试只适用于候选产品构建，单独验证构建仍需执行上游及适配器的解码测试，不能省略正确性验证。

依据固定的 [LICENSE.md](https://github.com/libjpeg-turbo/libjpeg-turbo/blob/7b4993786d856cbccabfc0ed2f923ba000e326cc/LICENSE.md) 与 [README.ijg 的许可条款](https://github.com/libjpeg-turbo/libjpeg-turbo/blob/7b4993786d856cbccabfc0ed2f923ba000e326cc/README.ijg#L114)：
- libjpeg API 主体采用 IJG 条款；分发源码要保留 README 与声明并注明修改，二进制文档需有 IJG 致谢。
- TurboJPEG API、构建/测试系统等采用 BSD-3-Clause；SIMD 源码采用 zlib 条款。保留各组件原始通知，不将整个项目简写为一个 BSD 许可证。
- 若启用默认 TurboJPEG/工具路径，还引入 zlib，以及带 BSD-2-Clause、libpng-2.0 等声明的 libspng；不能沿用旧 2.1.2 的依赖结论。本轮没有逐文件核验这些额外子树或授权分发它们。
- Android 构建说明只证明上游记录了 NDK 路线；没有证明当前目标 NDK、API、ABI、16 KiB 页或手机可用。最终 selected targets、系统运行库、SIMD 单元、链接方式与通知仍需按实际构建重新审核。

## 可复现检查

从仓库根目录运行：

```powershell
python android/uvc-sim/provenance/routes/mjpeg-decoder/audit_decoder.py
```

缺缓存时加 `--fetch`：只获取锁定提交的 6 个文本文件到 ignored build/routes/mjpeg-decoder；既有文件不覆盖。脚本检查输入长度/哈希/UTF-8、拒绝越界路径和二进制，并对每个文件验证异长与等长篡改会被拒绝。6 项源码锚点是文本存在性检查，不是 CMake 求值或解码测试。报告明确 decode_tests_executed=0、binary_closure_verified=false。
