# UVC 真实接入前的接口契约复核

**当前交付仍是候选驱动方案、纯替身模型及静态证据，没有已实现并通过验证的真实 UVC driver。** 本轮补充了可核验的新源码关系和接口合同，没有重复运行上一轮模型、增加搜索上限、引入运行时二进制或启动模拟器。

独立分支 `codex/uvc-interface-contract`，基线 `3dbbcb17f26816689d4b51b714996d937b9f4acd`；变更仅在 `android/uvc-sim/interface-contract/`。没有修改公共接口、生产代码或冻结 PRD。真实设备、native 接入与分发门禁保持关闭。

## 本轮新增结论及其限制

| 新增或补强的证据 | 对最小 adapter 的具体要求 | 尚不能据此证明 |
| --- | --- | --- |
| 固定 libusb 的成功 submit 在释放内部锁后才返回；独立事件线程可在这段间隔进入 completion/callback，callback 内可合法 free transfer | **调用 submit 前预登记 operation**。将 adapter 的 operation/context 生命周期与 raw transfer 分开，保留调用返回后仍会访问的对象所需的独立引用。不能等 submit 返回后才登记，也不能将 BUSY 当作一个全新失败提交来清理旧 cycle | 这是源码允许的交错推断，不是 native 复现或 libusb 漏洞。libusb 允许的 callback 内 free 与项目选择延迟 free 的保守策略必须区分；具体 adapter 的锁与引用正确性尚未验证 |
| cancel 的返回值与回调退出是不同事件；同步 control 也会参与事件处理；uvc_wrap 在把 handle 返回给调用者之前可能已提交 status transfer | NOT_FOUND 不当作已排空；取消调用、同步请求、回调和迟到 open handle 的资源引用分别管理。创建失败、部分初始化和返回码必须逐项处理 | 模型的原子 open/submit 不覆盖这些真实调用栈交错。没有修复 libuvc 的 status 关闭或错误传播 |
| Android requestPermission 返回设备和授权结果，但没有项目的 attachment epoch/request revision；getFD 返回借用值，getter/close 的同步边界不足以替 app 管理借用 | 预登记权限请求与 nonce，匹配本次连接代次和授权 revision；拒绝旧回调自动 open/start。平台 connection 是唯一 FD owner，native 只借用；FD 数字（含合法的 0）不能当会话身份 | 固定 Android 15 参考源码不是目标手机固件。PendingIntent flags/receiver 实现、实际权限路由和 OEM 行为未因这张表而通过 |
| 新取得固定 libuvc 的 generator、YAML 和 README 文本，补足 ctrl-gen.c 的来源线索 | 保留根许可证与各文件来源；40 个 YAML 控制和 80 个 get/set 函数的名称、顺序与行位置可重算 | 没运行上游生成器，没证明完整再生成字节一致、构建闭包或最终二进制对应关系。缺独立文件头不等于无授权 |

原生部分固定官方 **libusb 1.0.30 / libuvc 0.0.9**。具体签名、返回码、所有权、阻塞条件、源码行和项目待实现条件在 [NATIVE_CONTRACT.md](NATIVE_CONTRACT.md) / [native-contract.json](native-contract.json)，Android 部分在 [ANDROID_CONTRACT.md](ANDROID_CONTRACT.md) / [android-contract.json](android-contract.json)。两组共 28 项契约，分开标记源码事实、继承结论和项目自有约束；这不是已编译的 ABI 适配层。

另有一项仅限所查 AOSP 参考实现的失败清理观察：JNI open 失败和 usb_device_new 失败分支各有 close 站点。不能把参考源码直接概括成“所有失败路径已保证唯一关闭”，也不能据此断言目标手机存在同样问题。需要按实际系统版本核对；本轮未操作系统或设备。

## 超时不是退栈，唤醒不是排空

控制请求的截止时间到达，只说明上层不再等待结果；它不会自动使同步 C 调用返回。libusb 的取消请求、event-handler 的等待参数、interrupt_event_handler 也不是所有 transfer、callback 和 JNI 引用已退出的证明。即使使用非零 transfer timeout，仍须处理实际完成事件和调用返回；任何用户回调若阻塞，也不能被 poll 等待参数限制成一个全栈停止时限。

候选关闭次序应先撤销发布，禁止新提交/重提交，继续服务事件直至传输、回调和非泵业务调用完成；再请求泵退出并唤醒它，等 event-handler 真正返回和线程 join 后，才释放泵自身的 context 借用并退出 context、由唯一 owner 关闭平台连接。具体设备/stream/status 的释放细节仍取决于待修源码路径，不能照此段直接宣称完整算法已实现。未完成时保持既有停止未确认处理，不能强制 free 制造成功回执。

应用拥有的像素清理另有完成条件。native 关闭、旧模型允许新 sid、某一个回调或 StopReceipt 都不能直接替代冻结 PRD 的组合停止/模式切换门禁。

## 旧模型证据的精确边界

本轮只读取并核对旧记录的哈希和计数，**没有重新运行模型**：

| 已记录实验 | 保留节点 / 关闭节点 | 截断或范围 |
| --- | --- | --- |
| status + async control | 4,000 / 0 | 达到状态上限；6,533 条候选边受上限阻挡。它们不是 6,533 个唯一未知状态，也不证明所有关闭路径不可达 |
| status + blocking control | 1,272 / 168 | 在该实验定义的有限空间内没有未展开前沿；不覆盖任意真实线程、设备或 C/C++ 内存交错 |
| 定向正常关闭样例 | 2 条，另列 | 不混计为上面 BFS 的关闭节点 |
| 场景/验证器记录 | 38 项：27 正常、4 模型故障策略、7 日志变异 | 计数来自旧记录；不是本轮新增测试，也不是 JNI/USB 运行结果 |

模型的控制超时是注入事件，不是实际时钟或退栈时延测量。显式 cancel 调用栈交错只有定向验证，主 BFS 的 cancel_result 是调用已返回后的安全通知。atomic submit 假设未覆盖本轮发现的 callback-before-submit-return；真实 open/start、内核/设备响应、内存分配失败、ABI/页尺寸与性能都不能由这些数字推断。精确字段见 [verification-boundary.json](verification-boundary.json)。

## 许可证据与未关闭材料

[LICENSE_BOUNDARY.md](LICENSE_BOUNDARY.md) 和 [license-evidence.json](license-evidence.json) 固定了 31 项参考输入，其中 28 项继承、3 项新增官方文本（25,083 字节）。libusb 的 LGPL-2.1-or-later、libuvc 根 BSD-3-Clause、utlist 的独立条款，以及可选 JPEG/SIMD/其他组件仍分别保留。生成文件、generator/YAML 没有独立头的观察不能抹去根许可证的积极授权，也不能补成完整传递依赖或成品分发审核。

后续仍需明确实际 source/target 清单、工具链/ABI/链接方式、所有修改与对应源码、适用通知和 LGPL 的替换/重新链接材料；JPEG 只在实际选择时纳入完整核查。旧 UAC blob 来源门禁未变。本轮没有新增运行时依赖或音频能力。

## 复跑与可操作等待条件

仓库根目录运行：

```powershell
python -B android/uvc-sim/interface-contract/check_contracts.py
```

检查器只读固定缓存和已提交记录：核查 SHA-256、源码 Git blob、适用的 commit→tree→member 关系、精确行内锚点、5 项限定文件的文本缺项、40→80 名称/行矩阵及旧模型计数。它不编译接口、不执行生成器/旧模型/native、不联网。缺缓存或不匹配直接失败，不自动重新锁定。默认缓存是兄弟 `sidecar-uvc-route/android/uvc-sim/build/routes` 与本工作区 `android/uvc-sim/build/interface-contract/licenses`；可用 `--route-cache` / `--new-cache` 明确指定同内容缓存。

`validation.json` 保存首次 `--record` 的结果与交付哈希；该选项拒绝覆盖已有记录，默认重放不写证据。许可研究 owner 记录的 4 个内存负控制在汇总中明确标为未重执行，不冒充新增模型测试。当前静态校验记录见 [validation.json](validation.json)。

本轮新增的静态问题已整理到可执行的接口合同。剩余工作需要以下具体输入，**没有这些输入时停止扩跑相同模型或刷新相同来源**：

- **主集成给出公共合同**：授权连接/请求代次、operation 与 FD owner、wire/sink 格式、总缓冲预算、错误映射、组合停止完成条件。当前各研究分支不各自改一套公共接口。
- **选定隔离 native 实验范围和源码构建清单**：先针对 callback-before-submit-return、取消调用/完成交错、status 禁止重提交与排空、超时后迟到退栈、部分初始化失败构造可控实验。需要可审查的实际实现/补丁及正确的工具链、ABI、源码/依赖证据，不能只改模型使其通过。
- **实际设备/OEM 的既有门禁材料**：具体手机系统构建、采集卡/固件、供电 Hub、显示链路、线材与格式组合，以及被选来源、原显示保持、断连/恢复和停止证据。源码、README、CI 或 API 存在都不能替代这些材料。当前没有执行该阶段。
- **主集成核对结果并保留失败出口**：无法证明停止/来源/显示合同的组合维持不可用；不默认降级显示、不自动开始或换麦，不增加 PRD 状态或验收编号。

详细责任与设备前提见 [DEVICE_PREREQUISITES.md](DEVICE_PREREQUISITES.md)。合并建议仅限本目录的研究提交；目标分支须保留其引用的前置来源证据。冻结 PRD 仍为 Library 中 v1.5 version 0（原 Library ID 与 1021 行依据见前置交接），未重建副本或扩充范围。
