# 来源索引的解释

`source-manifest.json` 是本目录的活动来源清单。每项 `target` 都绑定原仓库、精确 Git 提交、原路径、Git blob、大小和 SHA-256，runner 校验这些导入文件以及 `main_contract_sources` 中唯一一份主线合同。Java/Manifest 的目标映射如下：

| 原路径前缀 | 当前目标 |
| --- | --- |
| `android/uvc-sim/native-experiment/src/`、`test/` | `adapter/src/`、`adapter/test/` |
| `android/uvc-sim/native-experiment/usb-owner-experiment/` | `owner/` 内对应源码/测试 |
| `android/uvc-sim/native-experiment/android-bridge-experiment/` | `bridge/` 内对应源码/测试/Manifest |
| `android/uvc-sim/native-experiment/contract-baseline/src/` | 没有复制；导入时逐字比较主线 `android/uvc-contract/src/` |

下列归档 JSON 保持原提交字节，不能直接用作当前布局的活动输入锁：

- `adapter/upstream-review.json` 的 `references` 指向原提交中的接口与许可研究。
- `owner/inputs-lock.json` 和 `bridge/inputs-lock.json` 的 `inherited_files` 混合了原源码、接口文档、合同快照及旧运行结果。已导入源码通过活动来源清单重新绑定；旧结果、`contract-baseline`、原 `interface-contract` 和其他研究目录没有复制或运行。它们仍是对应原仓库精确提交下的外部历史引用，而非被默默认定满足的新输入。bridge 锁里的 `android_sdk_files` 单独作为既有 SDK 的固定校验值使用。
- `bridge/source-evidence/inputs.json` 和 `anchors.json` 保存 AOSP 固定 URL、字节哈希和源码锚点。15 份完整文本缓存不在 Git 提交中，本次未复制、下载或重验；元数据通过仅表示它与已审提交相同。

文档中的相对链接保持原路径语境，没有改写为新结果。需要查看未迁移材料时，用活动来源清单提供的原仓库及精确提交读取原路径，例如 `git -C <source_repository> show <source_commit>:<original_path>`。这只是历史来源读取，不执行原 runner、不取得运行权限，也不继承历史通过结论。

本目录新结果只来自 `run_checks.py` 的独立 `build/run-*/result.json`。本轮导入未修改 Java 内容；没有下载、装载或链接第三方 native 代码。后续选择 JNI/native/JPEG/UAC 时仍须重新固定实际源码、工具链、依赖和分发材料。
