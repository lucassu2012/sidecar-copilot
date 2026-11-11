# ED140E29 Android 回归与测试修正

本轮在独占的 API 35 `ranchu` 模拟器上串行执行，未操作实体手机或真实采集设备。开发 APK 保持 `ED140E2986AD48D5EC6D4DEFCC2FCEF28F9B0DFCCFBE3DF0739334C4F2B3934A`。原冻结候选和失败记录未被覆盖。

原 native test APK `1C746A93` 缺少 linked session 的已记录同意前置，Pending focused 与全量 Native 均被生产门禁正确拒绝。仅在测试夹具中补 `recordDeclaration`，构建独立测试包 `AD74CF9E04C0A8EA3C391AF50846A0D587793AA06215F4D57612C8FF9F961999`；四个其他 APK、61 个生产类和生产源码均不变。修正包验证通过后才把该一行改动合入主线，当前 72 项输入与修正快照完全匹配。原包的两次失败仍是失败，不能称原五包组合全部通过。

| 范围 | 实际结果与版本 |
| --- | --- |
| WAL / 删除屏障 | 原 ED app/test：74 / 98 通过 |
| 原子文件 / 清理恢复 / 范围屏障 | 原 ED app/test：100 / 107 / 120 通过 |
| 原 Pending / 原全量 Native | 两次失败：缺少测试前置同意记录；全量在 Pending 处停止，后续未达 |
| 修正 Pending / 修正全量 Native | ED app + AD74 test：164 / 1,936 通过；focused 是 full 的子集，不能相加 |
| 控制组合冷态 / 暖态 | 同一 fixture/test APK：44 / 44 通过；初始权限均拒绝，冷态无通道，暖态保留通道/历史并显式撤销权限 |
| 页面/通知交叉控制 | 完整编排最终 48 通过；notification-first 路径验证另一页面按钮禁用，不声称第二操作成功派发 |
| 中断路径 / 独立中断诊断 | 15 / 12 通过，分别运行和绑定，互不替代 |
| UI 保存/删除/离页 | 33 通过 |
| 实际 OS 撤权恢复 | 16 通过；记录真实 pm revoke 退出 0，同 boot 新 PID/启动时间/token，保留已提交样本 |
| 前台服务 | 总计 230 通过，其中包含 held-store 机制 26；不是 256。2 ms 是本轮合成软件派发观测，不是实机停止指标 |
| 进程终止恢复 | 10 场景、10 次 verify 共 137 项通过；另有 11 次预期进程终止，不计为额外通过项 |

## 首次失败和编排修正

20 份运行收据中，16 份完整通过、4 份保留失败。除两次原 native 失败外，交叉控制首轮因主机传入非 UUID 的 run_id 在入口被拒；第二轮设备完成 48 项且 CODE -1，但主机误用通用摘要解析而退出 1。修正 UUID 和专用摘要解析后，第三轮完整编排通过。两份旧脚本均按 SHA 保存，旧收据、日志和索引均未重写。没有延长 8 秒等待、20 秒夹具界限或削弱断言。

新诊断的四条混合路径和冷/暖控制均记录了 BLOCK_APPEND_DIAGNOSTIC。成功执行能证明本轮走通；它们不解释旧 873AA 首次超时，root_cause 仍为 unknown。新模拟器初始化时的锁屏前置失败也单独保留，应用测试在使用已设测试 PIN 正常解锁后才开始。

## 版本绑定与窗口

每个阶段保存原始命令、UTC、stdout/stderr、ADB 实际退出码、前后拉回的 app/test APK、同 boot 身份、logcat 和证据索引。除首次 WAL 直接执行外，均额外记录独立 launcher 的真实进程退出码。首次 WAL 的工具执行结果为 0，但没有单独落盘 launcher 收据；`first-wal-host-observation.json` 明确保留这一限制，不能冒充事后重建的原始收据。修正全量 Native 内也执行了同一 WAL 检查。

原 72 输入/五包记录在 `android/build/mixed-recovery-20261110`；修正测试快照在 `android/build/ed140e29-test-followup-20261110`。运行目录为 `android/build/ed140e29-device-runs`，`runtime-summary.json` 列出全部尝试及哈希，`INDEPENDENT_AUDIT.md` 记录独立核对。源码集不同的成功结果只归属其实际测试包。

自有 `sidecar-ed140e29 / emulator-5598` 于 `2026-11-10T16:56:59.4552537Z` 确认关闭，进程和 serial 均消失，窗口已释放。只关闭本任务实例；独立 AVD 目录及证据保留，没有更改虚拟内存或操作实体手机。

## 未关闭的边界

未知 `.new` 的全首页阻断仍未修复，见 [可用性核对](RECOVERY_AVAILABILITY_REVIEW.md)。本轮不能替代独立六阶段留存/重启续测、API 30/OEM、真实输入、物理断电、完整旧快照回滚或闪存/WAL/备份擦除验收。真实采集及完整 G0/G1/G2/G3 退出仍未批准；PRD 未新增功能、状态或正式测试编号。
