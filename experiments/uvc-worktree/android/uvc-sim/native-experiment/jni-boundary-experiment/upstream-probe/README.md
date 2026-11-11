# 固定上游控制函数的隔离探针

本目录给主 runner 提供编译与运行输入；作者没有执行编译或测试。执行结果应引用主 runner 的记录，不能把本文件当作 PASS 证据。

被编译的上游函数体是固定 libuvc `448d78b9` 的原样 `src/ctrl-gen.c`。真实公共 `libuvc.h` 和固定 libusb `87a55632` 的 `libusb.h` 来自相邻 `source-evidence/cache/`，由来源锁验证。没有复制或修改生产 include，没有链接真实 libusb/libuvc，也没有 USB 调用。具体命令及输入 SHA 见 [BUILD_SPEC.json](BUILD_SPEC.json)。

`fixtures/libuvc/libuvc_internal.h` 是项目组装的缩减夹具，仅满足这一个翻译单元的成员访问。它不具备真正内部布局、pthread、状态回调、引用计数或资源行为；不得与真实库交换对象。四个 endian 宏和 selector 常量取自固定上游，其余结构是项目自有，并非整个文件均为原创。完整 [libuvc BSD notice](../source-evidence/licenses/libuvc-LICENSE.txt) 作为版本化文件保留，不只依赖被忽略的缓存。`libuvc_config.h` 是禁用 JPEG 的项目配置夹具，不是 CMake 输出。`sys/time.h` 仅引入本机 Windows SDK/UCRT 的声明，先 include winsock2，避免与后来的 windows.h 重复定义；它不是 Android 时间 ABI。

控制 harness 以脚本字面量检查请求方向、request、selector、terminal/interface index、长度和 timeout，并提供有限的返回字节。六个编码场景覆盖 camera 的 32 位小端、processing 的有符号 16 位和 selector 的单字节 GET/SET；八个返回场景覆盖三个负错误、正短读、零短读、正短写、零短写及负写错误。输出总计应为 14 个场景、14 次 fake control 调用。独立负控制故意要求错误 request，必须由同一请求检查报出精确违规，而不是只接受任意崩溃或异常退出。

两项现象是需要保留的上游行为：4 字节 GET 返回 0 时函数返回 `UVC_SUCCESS`，但输出仍为调用前 sentinel；SET 返回 0 也与成功同值。正短读/写返回正数，完整读取才更新 getter 输出。本探针只观察并锁定这些结果，没有修复或允许适配层把它们当作成功。所有被测函数都传入 `timeout=0`；fake 立即返回指定的 `-7` 只能证明错误传播，不能证明真实控制有超时上限、可被 stop 打断或最终退栈。

`api_signature_gate.c` 使用真实公共声明和 C11 类型断言检查七个函数：libusb wrap、cancel、events、control，以及 libuvc wrap、stop_streaming、stream_stop。只编译该对象，不链接和调用这些函数。Windows x64 上的声明可编译不证明 Android ABI，也不验证上游的取消、回调排空或 FD 借用实现。

没有加入 `ctrl.c`：其 generic control 和长度查询是另一组行为；尤其 `uvc_get_ctrl_len` 对非负短读缺少完整长度判断，不能靠为缺失字节预填值的替身掩盖未初始化读取。没有编译 device/stream/io/sync 源，也没有 native 事件线程。本目录不能替代 JVM/JNI 实验，更不能闭合 JNI 回调线程、global ref、生存期或真实 libusb/libuvc 停止问题。
