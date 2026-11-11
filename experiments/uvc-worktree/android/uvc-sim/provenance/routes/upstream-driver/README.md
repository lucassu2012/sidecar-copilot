# 上游驱动静态来源重放

主结论见 [REPORT.md](REPORT.md)。`inputs.lock.json` 是被版本控制保留的信任输入；ignored cache 内没有可覆盖它的 receipt。`evidence.json` 是脚本输出，既有输出默认必须存在且与重放一致。只使用 Python 标准库，不执行任何下载的源文件/构建脚本。

在 worktree 根运行：

```powershell
python android/uvc-sim/provenance/routes/upstream-driver/inspect_sources.py
python android/uvc-sim/provenance/routes/upstream-driver/test_inspect_sources.py
```

默认 cache 为 `android/uvc-sim/build/routes/upstream-driver`。可通过 `--cache <path>` 使用同内容缓存；测试临时目录也只建在给定 cache 下。缺输入、字节不符、tree 截断、缺 evidence、report 差异均失败退出，不把缺缓存当不存在或成功。测试不会联网。

只有重建已锁缓存时显式运行：

```powershell
python android/uvc-sim/provenance/routes/upstream-driver/inspect_sources.py --fetch-missing
```

这只拉缺失的、锁中已有 URL，限定官方 GitHub API/raw 主机、单文件上限、SHA/size 和 Git blob 身份，下载后校验成功才落缓存。已有文件不匹配时拒绝，不静默重拉或改锁。仓库固定 commit 源文件稳定；GitHub release/tag API 原始 JSON 仍可能因上游编辑或返回表示改变而不再匹配，届时应保留失败，人工重新评审输入，不能自行刷新锁。这一缓存重建不是完整源码 checkout；缺失头文件/构建输入不在该静态研究锁的覆盖范围。

首次显式生成 evidence 使用 `--write-report`；已有 evidence 如不同仍拒绝覆盖。没有 `--write-report` 时，缺报告必定失败。锁及报告不支持自动“接受当前缓存”。

本轮实际结果：离线重放 exit 0 / 49 inputs / `readiness=NOT_ESTABLISHED`；11 个校验器完整性测试全部通过。没有编译检查、ABI/ELF 检查、native 合成试验、实机验证或许可全覆盖完成声明。
