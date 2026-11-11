# JNI/native 准备：真实上游 C、来源与剩余缺口

本轮有可审查的最小真实 C 编译候选：固定 `libuvc/src/ctrl-gen.c`、真实 `libuvc.h` 与 `libusb.h`，搭配明确隔离的内部结构/平台声明 fixture 和 fake control 函数。它覆盖选定控制请求的编码与返回行为，不是完整 libusb/libuvc 构建、真实内部 ABI、Android native 驱动或设备验证。单独的 JVM/JNI 边界实验也不能替代这些上游实现；执行结果统一以根维护的 README、VALIDATION 和 validation.json 为准，本来源任务没有执行编译、测试、旧模型或 native。

## 输入与离线重放

工作树基线 `62eb3485a056c91720609f38ba436122bfdcb420`；官方固定输入为 libusb **1.0.30 / `87a55632db62c9bdc58cd31d3ccfa673f1bb017f`** 与 libuvc **0.0.9 / `448d78b9aa4f54172c1ca5b5d1b4a6b9b93f7957`**。本轮先按继承锁重核旧 cache 的全部 49 个输入大小、SHA-256 及适用的 Git blob SHA-1，再原样复制 21 个必要文本到 ignored `source-evidence/cache/`。未重新下载或执行上游代码。

[inputs.json](source-evidence/inputs.json) 记录官方固定 URL、commit、原路径、字节/SHA/blob、继承锁和 generator 证据文件的哈希。`role=compile_candidate` 恰好标记上面三个真实输入，其余是原 API、构建 recipe 和许可参考。该清单锁定本实验所选输入，不宣称覆盖完整源码包、传递头文件、工具链/sysroot 或发行闭包。[anchors.json](source-evidence/anchors.json) 锁定 15 项结论、35 个行范围，并限定检查 80 个直接 control 调用的 timeout 字面量及 `uvc_close` 函数体内缺少显式 status cancel 的范围。

从仓库根目录运行：

```powershell
python android/uvc-sim/native-experiment/jni-boundary-experiment/source-evidence/verify_sources.py
# 干净 checkout 缺少 ignored 文本时，显式恢复固定官方源码；没有二进制下载：
python android/uvc-sim/native-experiment/jni-boundary-experiment/source-evidence/verify_sources.py --fetch
```

默认只读离线，成功打印 `Source evidence verified: 21 inputs, 35 anchors.`，退出 0；失败退出 1，不生成 tracked 报告。可用 `--cache-root` 指向具有 `libusb/`、`libuvc/` 子目录的现有只读缓存。`--fetch` 只允许写本目录 cache：先拒绝已有坏缓存，固定原始文本 URL、禁止重定向，下载后核大小/SHA/blob和全部锚点，再原子落盘且不覆盖已有文件。独占锁冲突失败，不自动刷新锁或删除外来 part/锁。fresh checkout 仍需要固定官方 URL 可达；工具仅用 Python 标准库。主 runner 另外锁定该脚本、metadata、fixture 和实际工具输入，哈希匹配不等于源码语义正确。

## 最小编译方案的真实边界

具体文件、命令与预期观察见 [upstream-probe/README](upstream-probe/README.md) 和 [BUILD_SPEC.json](upstream-probe/BUILD_SPEC.json)。原样编译 `ctrl-gen.c`；不链接 libusb/libuvc runtime，`libusb_control_transfer` 由原创 harness 实现，三个单元 getter 返回项目构造的 camera/processing/selector 描述。实际成员访问只有 `devh->usb_devh` 与 `devh->info->ctrl_if.bInterfaceNumber`，不能将缩减结构交给真实库。

`fixtures/libuvc/libuvc_internal.h` 是项目组装的测试头：四个 endian 宏和 selector 常量保留上游表达式，其余最小结构由项目编写，不能称整个文件均为原创。真实 internal 头包含 pthread、libusb、utlist 及其他状态/线程/引用字段，fixture 没有实现这些行为。真实公共头仍保持原样；公共声明能编译不证明真实内部结构或 Android ABI。

现有工具条件由根的 [inputs-lock.json](inputs-lock.json) 记录：可用已安装的 MSVC/JDK 17/Android SDK；初始 PATH 没有 CMake/Clang/GCC，SDK 没有 NDK。MSVC host 最小编译不要求安装上述工具。真实 `libuvc.h:10–11` 无条件依赖 `sys/time.h` 和生成 config，frame 还含 `timeval/timespec`；本轮 `sys/time.h` fixture 引用 Windows SDK/UCRT 的 `winsock2.h/time.h`，config fixture 固定 0.0.9 并关闭 JPEG，均不是 Android 时间 ABI 或 CMake 生成结果。真实 `libusb.h:28–68,111–124` 已有 MSVC `ssize_t`/inline 和 Windows 声明分支；stub 定义必须保留 `LIBUSB_CALL`。这只是具备编译尝试的源码条件，实际通过与警告由根的结果记录。

probe 选择 camera 32 位、processing 有符号 16 位、selector 单字节 GET/SET，并观察完整、错误与短返回；完整 80 个函数的语义并未被逐一验证。固定 C 的 GET 只在长度完全匹配时写输出，否则直接返回实际值，因此 **短 GET 返回 0 会等同 `UVC_SUCCESS`，但输出未更新；短 SET 返回 0 也与成功同值**。正短返回则仍是正数。本实验保留这些原始行为，不是修复，也不授权适配层把短传输当有效控制结果。类型 gate 只编译真实 wrap/cancel/events/control/stop 声明，不链接或调用这些真实函数。

## 原始 API 对后续 owner/JNI 的约束

下表结论的固定源码行与精确子串可在 `anchors.json` 查到；官方链接只指向锁定提交。

| 依据 | 后续实现约束 / 本轮未证 |
| --- | --- |
| [libusb core.c:1371–1407](https://github.com/libusb/libusb/blob/87a55632db62c9bdc58cd31d3ccfa673f1bb017f/libusb/core.c#L1371)：`libusb_wrap_sys_device(ctx, intptr_t sys_dev, out)`；Linux fd 须保持打开至 `libusb_close`，后者不关闭该 fd。Linux backend 设置 `fd_keep=1`。 | Android 已授权 connection 的 fd 继续由唯一 Java owner 持有，native 只借用。API 不承诺 `dup`；若后续主动 dup，必须另外登记副本 owner/唯一 close。fd 数字不能充当会话身份；Android 授权、attachment epoch、授权 revision 和 stop gate 都不能由此 API 代替。此实验不调用 wrap。 |
| `libuvc/device.c:262–297`：`uvc_wrap(int sys_dev, context, out)` 委托 libusb wrap 后建立 UVC 内部状态；`init.c:94–118` 的外部 context 分支 `own_usb_ctx=0`。 | 公共类型的 `int` 与 `intptr_t` 差别要按真实平台检查，不能用任意 JNI 整数冒充原生指针。外部 context 必须自己管理 event pump、退出和 context 最终释放。host C 声明 gate不建立 Android fd 或指针 ABI 证据。 |
| [libusb io.c:305–321,1573–1623](https://github.com/libusb/libusb/blob/87a55632db62c9bdc58cd31d3ccfa673f1bb017f/libusb/io.c#L305)：cancel 异步；0 不是完成，NOT_FOUND 可表示不在进行、已完成或已取消。完成前 free 属未定义行为。 | 保留 submit/cancel 调用栈、terminal callback、buffer 和对象借用；不能由 cancel 返回码直接构造 drained。no-response 可以安全 pending，不能以等待超时伪造完成。取消调用本身仍持有 transfer 借用，callback先退栈不意味着同时在途的取消调用已退栈。 |
| `io.c:544–552,1718–1731`：回调在 event handling 中执行；上游允许在完成回调内 free，并在回调后不再使用已释放 transfer。 | 本项目延迟释放直到自身借用退出是额外保守策略，不能写成上游统一要求“所有 free 必须晚于 callback 返回”。event pump 必须服务预期 terminal；真实 JNI attach/detach、global refs、异常和回调退栈另行取证。 |
| [libusb sync.c:49–101](https://github.com/libusb/libusb/blob/87a55632db62c9bdc58cd31d3ccfa673f1bb017f/libusb/sync.c#L49)：control 的 timeout=0 表示无限；同步等待通过 events 直到完成。`ctrl-gen.c` 的 80 个直接调用全部传字面量 0。 | fake 立即返回 TIMEOUT 只证明值传播，不证明真实控制有时限、可被 stop 打断或最终退栈。后续需要明确的有界控制策略/异步取消设计；本轮没有修改原调用。 |
| `stream.c:1562–1594`：image transfers cancel 后 condition wait，可能 join 用户线程。 | 该函数没有显式有界等待，且 image stream 停止不覆盖独立 status transfer。不能把 stop 返回等同像素清零/发布关闭；BufferEvidence 与 root publication gate 仍需独立真实证据。 |

**status teardown 仍是阻塞真实接入的源码缺口。** `device.c:358–379` 在 open 时提交独立 interrupt transfer；`2116–2144` 的 callback 在部分状态重新 submit。已检查的 `uvc_close:1933–1959` 停 image stream、release interface、close libusb，部分条件下 join 自有线程，然后 `uvc_free_devh:1907–1922` 释放 status transfer；该函数体没有显式 status cancel/drain。`libusb/core.c:1572–1591` 对仍在途的 close 发出诊断并失效 dev_handle，不能视作 terminal callback/borrow 排空协议。本轮没有运行该路径，不断言目标设备必现故障，也没有把外层模拟账本当作已修复上游。后续须建立所有权、禁止重提交流程、取消/完成/调用栈排空证据，并审核失败回滚与外部 event pump。

## 构建与许可闭包

固定 CMake 声明八个基础 C 文件并链接 libusb/threads，可选 JPEG 增加 `frame-mjpeg.c` 与 JPEG 库。固定 libusb Android recipe 声明十个 C 文件（含 Linux USBFS、POSIX events/threads/netlink）、`-pthread` 和 `-llog`。三份真实 probe 输入加 Windows fixture并没有构建这些闭包；未装 NDK、未固定 Android target ABI/sysroot/完整依赖和最终打包产物，不能宣称 Android 四 ABI 库可发布。

本轮含实际上游 C/头与宏摘录，不能沿用前轮“全原创 Java，无上游复制”的范围描述。版本化保留原样 [libuvc BSD3 LICENSE](source-evidence/licenses/libuvc-LICENSE.txt) 与 [libusb LGPL COPYING](source-evidence/licenses/libusb-COPYING.txt)，哈希绑定 `inputs.json`。libusb 公共头明确 LGPL-2.1-or-later；不链接它的 runtime，并不自动证明所有头使用/二进制分发义务消失。真实 utlist 有 Troy D. Hanson 的独立通知（本 fixture 未包含它）；后续编译实际内部头要保留相应通知。JPEG、UAC 来源授权及其他既有门禁不因本 probe 而关闭。

generator 来源不是未知：继承 [LICENSE_BOUNDARY](../../interface-contract/LICENSE_BOUNDARY.md) 与已锁的 `license-evidence.json` 已定位同 commit 的 `ctrl-gen.py`、内嵌模板及 `standard-units.yaml`，40 个控制与 80 个函数名称/顺序对应。提交的 C、生成器和 YAML 缺独立版权头不等于无授权，也不扩大仓库 BSD3 授权之外的历史权利证明。当前 CMake直接编译已提交 C，无需先运行 Python/PyYAML；本轮未运行生成器，未证明历史命令、工具版本或字节重建一致性。

后续真实 JNI/NDK 接入需另外关闭：实际 Android fd 授权与 owner 接线、libusb/libuvc 原始 IO/status 的取消排空和有界控制、native 内存/像素清理、完整源码及许可发行材料、目标 ABI 构建/装载，以及真机 USB 枚举/格式/拔插恢复和音频路由。无新增 PRD 状态或编号；无设备/模拟器、真实 USB、发布、部署或远端推送；最终本地版本化由根统一处理。
