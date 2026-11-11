本轮把 `ctrl-gen.c` 的“未追踪生成来源”推进到固定提交内可核验的文本对应：找到了生成器、内嵌模板和同树 YAML 输入候选；40 个控制名称与 80 个 getter/setter 名称及顺序完全对应。没有执行生成器，不能据此宣称生成产物逐字节可复现或许可证闭包已经完成。

基线为 `3dbbcb17f26816689d4b51b714996d937b9f4acd`。继承 [upstream-driver 的固定源证据](../provenance/routes/upstream-driver/REPORT.md) 和 [协议研究的源基线](../protocol-research/source-baseline.json)，没有覆盖历史证据。完整锁、行号、精确锚点和计算列表在 [license-evidence.json](license-evidence.json)：31 项源输入，其中 28 项复用只读缓存，新增 3 项官方固定提交文本、共 25,083 字节；53 项声明、5 项限定范围的缺项扫描。

| 项目 | 固定版本及提交 | 本轮确认的许可证据 |
| --- | --- | --- |
| libusb | v1.0.30 / `87a55632db62c9bdc58cd31d3ccfa673f1bb017f` | 根 `COPYING` 是 LGPL 2.1 文本；Android `libusb.mk` 列出的 10 个 C 文件各自明确选择 2.1 或更新版本。因此该组已读文件记为 LGPL-2.1-or-later，不能仅由根许可证推出整个依赖集合已审完。 |
| libuvc | v0.0.9 / `448d78b9aa4f54172c1ca5b5d1b4a6b9b93f7957` | 根 `LICENSE.txt` 是积极的 BSD-3-Clause 仓库级授权证据；7 个非生成基础 C 文件另有 Ken Tossell 2010–2012 的三条款声明。根文本的版权年份为 2010–2015，应保留不同声明。 |
| utlist.h | libuvc 固定树内 blob `bb097a2668d0336de2c8b734608e977f02c88fbc` | 文件自报 2.0.2，Troy D. Hanson 2007–2017；文本为 BSD-1-Clause，不能用 libuvc 根 BSD3 覆盖。没有宣称识别了完整外部 uthash 发布快照。 |
| 可选 JPEG | 尚未由本路线锁定最终链接实现 | `frame-mjpeg.c` 有 Robert Xiao 2014 的 BSD3 头及默认 Huffman 表来源说明；实际 JPEG 库、头文件和传递依赖需另行固定，不能由该 C 文件头代替。 |

新增文件均由 GitHub connector 读取固定提交的 UTF-8 内容，并与继承的固定 commit → tree → blob 成员关系核验；不是仅计算下载文件自己的哈希。下表 SHA-256 对应 UTF-8 原样字节，未做换行或 Unicode 规范化。JSON 的 `wire_sha256` 在 connector 场景也指这些观察字节，不声称捕获了 HTTP 传输封包。

| 新增源 | Git blob SHA-1 | SHA-256 |
| --- | --- | --- |
| [src/ctrl-gen.py](https://github.com/libuvc/libuvc/blob/448d78b9aa4f54172c1ca5b5d1b4a6b9b93f7957/src/ctrl-gen.py) | `68994453affce7a76eacf92eae5d285b7fec24bf` | `8f7051a8d4446e55163e47a072157d04ea2883e9871c4e19269d3b6a6ad201a5` |
| [standard-units.yaml](https://github.com/libuvc/libuvc/blob/448d78b9aa4f54172c1ca5b5d1b4a6b9b93f7957/standard-units.yaml) | `198c401647a225070593f04b06bdac020cc7f02a` | `cb97e20756479e9db58fd17f795476fa8d5647f4e601a922121d7e4200152bee` |
| [README.md](https://github.com/libuvc/libuvc/blob/448d78b9aa4f54172c1ca5b5d1b4a6b9b93f7957/README.md) | `031b755610c9c97f56d0d1ea0f9e239945d45c5a` | `ff9983a8a096ea8f3aed42c721c19312adc8a0cd8080a18894a90a98d150a844` |

生成来源的可验证增量如下。

1. `ctrl-gen.c:1–6` 的生成提示和 prologue 对应 `ctrl-gen.py:279–287` 的输出字符串。生成器 `74–127` 行内嵌 `GETTER_TEMPLATE` 和 `SETTER_TEMPLATE`；`179–202` 行将控制元数据填入模板。本次读到的生成器没有另读外部模板文件。
2. `ctrl-gen.py:239–258、272–277` 接收 `-i/--input`，逐个打开输入文件，以 PyYAML `SafeLoader` 读取 `units`。`inputs` 初始为空；生成 C 头里的 `ctrl-gen.py def` 没带输入，因此不是完整的重建命令。`standard-units.yaml` 是同树内与输出名称对应的输入候选；没有把候选当作已经证明的历史执行命令。
3. 对 YAML 用正则 `^      ([a-z_]+):$`，对 C 用 `^uvc_error_t uvc_(get|set)_([a-z_]+)\(`，均为 `MULTILINE`。提取出 40 个不同控制和 80 个函数；将每个控制按 get、set 展开后，列表和顺序完全一致。JSON 保存完整提取列表及 40 行映射，不只保存一个布尔结果。
4. [CMakeLists.txt:62–71](https://github.com/libuvc/libuvc/blob/448d78b9aa4f54172c1ca5b5d1b4a6b9b93f7957/CMakeLists.txt#L62) 直接列出已提交的 `src/ctrl-gen.c`。该文件未出现 `ctrl-gen.py` 或 `standard-units.yaml`，固定 README 也未给出生成命令。这限定于已检查文本，不宣称所有历史或外部构建路径都不存在生成步骤。

名称及顺序对应不覆盖函数体、枚举/类型/头文件完整性、预处理配置、完整构建图或最终二进制身份。没有运行 Python/PyYAML 上游代码，没有验证其版本兼容性，没有做再生成字节比较。若未来需要声明可复现生成，再补齐准确调用、输入次序、Python/PyYAML 版本及生成结果比较；当前直接审查固定的已提交 C 文件不以重生成作为先决条件。

`ctrl-gen.py`、`standard-units.yaml` 和 `ctrl-gen.c` 未发现独立 copyright/SPDX/license-agreement 声明，文件全文人工阅读与 JSON 中限定的正则扫描相符。这不否定 [同一固定树的 BSD3 根授权](https://github.com/libuvc/libuvc/blob/448d78b9aa4f54172c1ca5b5d1b4a6b9b93f7957/LICENSE.txt)，也不等于“未授权”或新增审批要求。逐贡献的来源和权属历史本轮未审；模板与名称对应本身不会证明该历史。

JPEG 边界继续独立记录。[CMakeLists.txt:75–87、156–160](https://github.com/libuvc/libuvc/blob/448d78b9aa4f54172c1ca5b5d1b4a6b9b93f7957/CMakeLists.txt#L75) 通过条件分支加入 `frame-mjpeg.c` 并链接 `JPEG::JPEG`。`frame-mjpeg.c:40、146–150` 使用 `jpeglib.h` 和 `JCS_EXT_BGR` 等接口；`56–57` 行保留 ISO/IEC 10918-1:1993(E) K.3.3 的默认 Huffman 表来源说明。需要把实际 JPEG 实现的精确版本、所用头文件/扩展接口、构建配置与许可材料绑定起来。`DISABLE_JPEG` 只说明存在关闭分支，不能据此更改已要求的采集格式；已读源码也不能证明最终包真的排除了 JPEG。

实际分发前尚需可检查的交付材料，不能由本轮文本研究代替：

- 最终选用的源码、头文件、配置、生成输入与本地补丁清单，逐项关联固定版本和需要保留的作者/许可声明。当前只是选取文件，不是完整源码或传递依赖清单。
- 按实际 ABI 记录源码到目标文件、共享库和包的对应关系、链接清单与最终依赖；若启用 JPEG，纳入其实际实现和依赖。未来构建阶段才产生这些材料，本轮未构建或检查二进制。
- 随实际分发物准备相符的许可证和版权通知，包括 libusb、libuvc、utlist，以及启用时的 JPEG 与相关来源说明；本报告不充当应用的通知包。
- 明确所采用的 LGPL 分发机制并准备相应材料。固定 [COPYING 第 6 节](https://github.com/libusb/libusb/blob/87a55632db62c9bdc58cd31d3ccfa673f1bb017f/COPYING#L276) 涉及通知、用户修改/调试及不同机制；对应源码与修改、适用的重链接材料或共享库替换能力，应依实际选择留证。`BUILD_SHARED_LIBRARY` 不是这些条件已经满足的证明，也不自动要求把未选择机制的所有备选材料一律交齐。

从本工作区根目录离线重放：

```powershell
python -B android/uvc-sim/interface-contract/check_contracts.py
```

默认复用兄弟工作区 `sidecar-uvc-route/android/uvc-sim/build/routes`，新文本缓存位于本工作区 `android/uvc-sim/build/interface-contract/licenses`。可分别用 `--route-cache` 和 `--new-cache` 指定其他已准备的缓存根；旧源的路径含 `upstream-driver/` 前缀。缓存不随 tracked 报告分发；缺缓存或哈希不符应失败。本轮没有声称该命令能从空缓存联网重建继承的元数据字节表示；新文本的固定 URL 和 blob 身份已列出。默认验证不写记录、不联网、不执行生成器或旧模型。

静态校验另用内存中的 4 个负控确认检测能力：删除一个 YAML 控制、改名一个 C getter、互换首组 get/set 标签均破坏名称/数量/顺序校验；给生成器附加一个换行破坏已锁 blob。原始缓存未改动。这些只验证取证检查的检测能力，不是新增 native、性能、硬件或 PRD 验收测试。
