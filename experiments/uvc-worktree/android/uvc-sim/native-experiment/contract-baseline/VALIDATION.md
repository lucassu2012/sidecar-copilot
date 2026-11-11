# UVC 公共合同与宿主所有权验证

2026-11-10，主工作目录 `C:\Users\USER\Documents\Codex\2026-11-10\task\sidecar-copilot`。交付是独立 Java 合同及自有 C++ 线程实验；不修改封版需求、不增加官方测试编号、不接 APK/JNI、不操作设备。当前文件归属和 adapter 接续合同见 [README.md](README.md)。

## 实际结果

| 验证 | 本次结果与边界 |
| --- | --- |
| Java 合同 | **352 项通过**；JDK 17.0.19，`--release 8 -Xlint:all -Werror` 编译和 runner 均退出 0。覆盖完整身份、三方停止分支、版本绑定、失败保留、真实数组清零、预算溢出和并发重复归还。 |
| 同版本矛盾证据负例 | 新测试对本模块加固前的旧 class 准确失败，退出 1，保留原结果。修复后同版本异内容会锁存协议错误；低版本和相同重复仍按既定规则处理。不是 F134 或真实驱动漏洞复现。 |
| C++ 正常策略 | MSVC 19.44.35222.0、C++17、`/W4 /WX /fsanitize=address`，**17 个生命周期场景通过**。真实线程屏障、heap payload、调用与回调引用、清零和实际释放计数；所有正常场景最终 live bytes 为 0。 |
| C++ 故意错误策略 | **9 个 UAF 负例**分别被 ASan 检出，必须匹配 heap-use-after-free 和退出 86；另 **3 个精确不变量负例**按预期失败。这 12 项是负控检出，不计为正常生命周期场景。 |
| ASan 烟测 | 正例退出 0、故意释放后读取的负例被检测，共 2 项；不与上面 17/12 重复计数。 |

Java 的最终 [receipt.json](build/4d06488ff9994d7fb6ea0ac1db475db2/receipt.json) 绑定 5 项输入；[旧实现负例](build/old-revision-negative-2bc3237031604e3fa8a9cffaeae30b08/result.json) 保留旧 class 哈希。早期 297 项和中间版本 352 项记录留存，不追加到最终计数。

C++ 的最终 [result.json](host-experiment/build/run-20261110T175508.399869Z/result.json) SHA-256 为 `30947151DE5D5C3C85D47856519972AC0058950AC213E785AD83E152B4A67E06`。31 项由 17 正常场景、12 预期故障和 2 烟测组成；外层 runner 退出 0。同目录保存固定源码、编译器及 ASan 库身份、34 份命令/输出/退出码、内存统计和 86 项索引；详见 [原生宿主交接](host-experiment/HANDOFF.md)。

正常场景包括 submit 返回前 callback、cancel 返回前 terminal、NOT_FOUND、BUSY、控制外层超时、status 重提交交错、缺 callback、迟到 open、FD 0/负值、身份入口、停止后借用、并发关闭、应用持有图像及字节预算。FD 是受控模型中的值，未实际操作系统 USB FD。缺 callback 场景先保持未确认，再由测试显式补投回调完成清理，不把超时当作 drain。

## 首次失败与审查修正

C++ 首次 `run-20261110T175313.887546Z` 在 vcvars64 的命令包装阶段退出 1，尚未编译或运行测试；源码快照与失败收据保留，但首次 setup 的完整 stderr 未归档，不追认补齐。修正本地 `.cmd` 包装后完成上述运行，没有修改系统设置或安装编译器。编译器版本查询 `cl /Bv` 无源文件退出 2 留在命令收据中，它不是构建结果；两次实际 C++ 编译均退出 0。

本模块实现期间的只读审查还纠正了构造失败前启动线程、跨 owner 借用、并发 join/关闭、把 buffer 是否存在误作唯一义务记录、BUSY 负例时序、实际 delete 前提前归还预算等实验自身问题。最终运行绑定修正后的固定源码；这些不构成上游 libusb/libuvc 或生产 Sidecar 已发生事故的证据。

中间成功运行 `run-20261110T175330.697566Z` 保留。其后复审发现实验的 ownApp 入口在 stop 后仍能分配，增加拒绝检查及原有停止准入场景内的断言，再完整运行同一 31 项得到上面的最终结果；中间成功不能替代这次修正验证，不追加计数。

## F134 基线与资源隔离

主线 F134 的 **73 项输入未变**。五包以 `android/build/meeting-isolation-candidate-20261110/source/build/` 和冻结归档 `android/build/meeting-isolation-20261110.zip` 为准；归档 SHA-256 仍为 `FDCDAD99C156B165D81794A2FE4A551B21A39A7E619212FACBEDA74F9A69DD6A`，其中五个 APK 均与冻结清单匹配。通用 `android/build/*.apk` 是更早的历史产物，不用于本候选绑定。本轮没有 Android 构建或安装，留存线的模拟器窗口未被占用。

F134 已有软件回归与本模块的宿主结果分开记录，前者不能证明新模块已接入，后者也不补足独立留存六阶段或真实凭据/完整删除按钮、真实输入断连、拔线转线下的缺口。后续留存结果由对应工作线按确切包单独接收。

## 仍未完成

没有编译或接入固定 libusb/libuvc、修补其 status teardown、实现 JNI/Android USB owner、运行真实采集、TSan 或 LeakSanitizer，也没有 Android ABI/16 KiB 页/热耗/长时性能、真实掉电或物理擦除证据。C++ 和 Java 是两个独立实现；没有跨语言 ABI/统一实际资源账本的运行绑定。StopBarrier 依赖生产者如实报告，不能证明没有漏登记或非法后续访问。

原生线可以按已明确文件边界继续 adapter/固定上游的隔离验证；许可、真实采集和分发门禁仍关闭。没有新增产品能力、解锁 G2/G3、发布或部署。
