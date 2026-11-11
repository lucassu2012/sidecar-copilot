# F134E5D4 串行 Android 回归

F134E5D4 固定候选的本轮 Android 回归完成：11 次尝试中，10 次实际执行的套件全部通过；1 次在锁屏前置门禁被拒，未启动测试主体。源码和五包始终未改，未把 ED140/AD74 的旧结果转算。所有数据为合成输入，设备为独占 API35 ranchu AVD；不是实体手机验收。

## 基线及运行绑定

- 候选目录：`android/build/meeting-isolation-candidate-20261110`。73 项输入清单 SHA256 `086BB7BC0C4254993F9CE5B8F1D1708F0E2AF3E88D130086AE2C491B9BC14879`；工作树与冻结源一致。
- 五包清单 SHA256 `E6D8EFF78ED0BB084743534BA866907DB89AADA5944554D41509756AD257C9D3`，共同证书 `67F5BBEEF95AEF18C3EAA034A30C451605735937464557C690E759586648B031`。
- 独立新 AVD `sidecar-f134`，串口 `emulator-5598`，user 0，boot ID `2a70d036-d38d-4270-a46d-08426ee6c570`。启动前无模拟器，物理可用约21.7 GiB；未更改系统虚拟内存设置。
- 每次绑定源码、两包安装前后拉回哈希、完整日志、命令时间与退出码、script/manifest、索引及独立 host launcher。实际执行的套件均为唯一成功终态，launcher 退出0。首次前置失败只有后置两包收据，不伪造测试前拉包。
- 逐次索引共1451项、外层命令570条、外层逐次拉包126条均已复核；控制脚本内部收据另行保留，不把审核数量当新增产品断言。

| 套件 | 实际结果 | 运行目录 |
|---|---|---|
| Native meeting_isolation_only | 锁屏前置拒绝；测试主体未启动 | `20261110T172801Z-Native-90ddccd9` |
| Native meeting_isolation_only | 95 项通过（Full 子集） | `20261110T172902Z-Native-4de49cb0` |
| Native  | 23 类共 2031 项通过 | `20261110T172933Z-Native-ab86ac9b` |
| Combined  | 44 项通过 | `20261110T173048Z-Combined-32dadbd2` |
| Mixed mixed_controls | 48 项通过 | `20261110T173122Z-Mixed-1984fa94` |
| Interruption interruption_only | 15 项通过 | `20261110T173201Z-Interruption-a7306620` |
| UI  | 64 项通过 | `20261110T173214Z-UI-4facca16` |
| Diagnostic  | 12 项通过 | `20261110T173254Z-Diagnostic-73dcf68f` |
| Revocation  | 16 项通过，实际 OS 撤权 | `20261110T173307Z-Revocation-4534c393` |
| FGS  | 230 项通过（含 held-store 26） | `20261110T173343Z-FGS-b4913b02` |
| Crash  | 10 场景、137 verify 检查通过；11 次预期进程终止 | `20261110T173425Z-Crash-182daa91` |

以上是每套件原始断言数，存在覆盖重叠，不能相加作为独立需求数。95 已包含在2031中；held-store 26 已包含在FGS230中。

## 已验证的关键行为

未知 bounded regular `.new`/不同副本只隔离目标，目标字节及别名保留；邻居可打开/修改，两侧到期对象继续清理，普通维护仍报告未完成。普通 I/O 注入、缺钥/密文认证、时钟、取消和持久删除清理故障仍阻断整体成功，普通构造的删除恢复未放宽。它们属于可控故障模型与模拟器文件/Keystore行为，不是实体存储器故障或掉电。

UI64 实际覆盖新增局部隔离、未知 ID/标题/正文不展示、邻居打开、整场/单笔记在读取后删除时不发布旧内容、离页取消、普通I/O全局拒读，以及维护完成后才出现未知副本与时钟失信时的最后一道门禁。home 由测试反射进入，不覆盖系统凭证确认交互；删除竞态由受控存储调用注入，不等于完整按钮确认路径。

Combined 初态为双拒权、无通知通道；44项通过。Mixed四种页面/通知交叉控制均通过，原8秒 after_fsync 等待和诊断未延长。撤权回归保存真实 pm revoke 退出0、麦克风 true→false、PID5936→6031、token变化和新进程启动时间晚于READY的同boot/UUID证据。

FGS合成停止调度最大1ms仅是此次软件夹具观测，不能推导实体设备停止延迟。Crash是实际Android测试进程终止：21阶段含11个准确checkpoint后的预期终止、10个verify共137项；不是闪存断电或物理擦除证据。

## 失败、边界与未完成项

首次 `90ddccd9` 中，凭证验证命令虽返回verified，trust仍为deviceLocked=1；门禁正确阻止执行。安装前setup曾观察deviceLocked=0，不能据此解释后续所有锁屏原因。保留原失败，随后用自有合成PIN在正常锁屏界面解锁，记录1→0，再以新收据执行，未放宽门禁。

首次终态查询 `dumpsys activity instrumentation` 返回不支持；其退出0不被当作无活动证据。后续完整activity dump、两个服务状态、所有测试终态和五包最终拉回记录分别保留。

历史873 BLOCK_APPEND前置超时根因仍未闭合，本次未复现不能宣布修复。慢线四个异常链反例为主动违反内部异常契约的注入防御缺口，尚未找到正常生产生成链；详见运行目录 `EXCEPTION_CONTRACT_REVIEW.md`。1046项宿主结果与519项异常图观察均不转算本次Android通过，四个负例没有被隐藏；本轮不修改冻结版本。

目标未知副本的最终恢复/期限处置、完整旧快照回滚、Android30/OEM、真实输入与硬件停止、真实断电、备份运行行为及物理擦除仍未验收。G2/G3 未签收。留存线的73输入独立六阶段26/7/65/40尚需后续窗口执行；本Full中的相关子集不替代它。

## 设备窗口及交接

17:36:53.971748 UTC 已关闭自有AVD PID69892/74260，确认串口消失，保留其磁盘和所有证据。原释放记录还包含一个系统自动关闭辅助进程 `emulator -kill 74260 -sleep 20`；没有将它改写为空。17:38:18.716257 UTC 单独资源复查确认所有 emulator/qemu 进程为空，可供下一工作线串行占用。没有关闭他人设备或操作实体手机。

- 释放收据：`android/build/f134-device-runs/window-released.json`
- 后续资源复查：`android/build/f134-device-runs/release-followup.json`
- 精确最终实装五包：`android/build/f134-device-runs/final-device/packages.json`
- 本轮汇总及原始收据：`android/build/f134-device-runs/runtime-summary.json`
- 独立审计：`android/build/f134-device-runs/INDEPENDENT_AUDIT.md`

| 包 | 最终实装 SHA256 |
|---|---|
| `sidecar-dev.apk` | `F134E5D46CCCA874418D8CE8E2520C2E55D793320D19939A2933994D9976B47C` |
| `sidecar-tests.apk` | `D203A8686C68B8C71BE4A0D3B024A146535C68EAEE381042D75F90732F891ED8` |
| `sidecar-fixture.apk` | `B0648D76EC0531FE98ACB883E7831E5962E6BAE1F49C44AAF916904BCFC4834B` |
| `sidecar-fixture-tests.apk` | `6654DAA872010E20377541C1918FFF6B6F0EFDE8CB96C5713B289058F3B00628` |
| `crash-tests.apk` | `EABB4D9D164E1A223516B2C95B2858C00AC2DE65F593A68D7905B10B0D82E7C7` |
