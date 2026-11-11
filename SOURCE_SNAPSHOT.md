# 公开源码与开发 APK 快照

日期：2026-11-11。现有首页进度文档保留不动，本次新增完整当前主线源码、测试、构建脚本、契约、文档、独立实验源码及5个CED8开发APK。没有导入本地Git历史、覆盖工作树或重新开始开发。

## 入口

- `sidecar/`、`tests/`、`pyproject.toml`：Python参考实现、持久传输及测试。
- `android/`：当前Android开发应用、instrumentation测试和构建脚本；UVC模块仍隔离于APK。
- `contracts/`、`docs/`、`README.development.md`：契约和历史开发记录。历史“未公开”“未构建”描述不代表本次上传状态。
- [开发APK与校验信息](artifacts/android/ced8d30a/README.md)：**CED8构建/签名通过，未安装、未执行候选运行回归，非生产发布。** F134模拟器结果不转算为CED8通过。
- `experiments/uvc-worktree/android/uvc-sim/`：最新已提交UVC实验树62eb348及未跟踪JNI边界实验的独立快照。13个相关工作树的HEAD均为62eb348祖先或自身，没有复制13套重复应用；这些内容不表示已合并主应用。
- `experiments/uvc-slice/`：独立原创UVC合成切片，不含下载的第三方AAR。
- `publication/`：来源、脱敏、排除范围和发布文件哈希。

## 构建与使用边界

Python按 `pyproject.toml` 安装依赖，入口与测试方法见 `README.development.md`。Android需要自行安装JDK17、Android platform35与Build Tools35.0.0，按 `android/build*.ps1` 的参数指定SDK/JDK路径。脚本中的工具路径是开发默认值，不随仓库分发工具链。

本次仅做出版前的完整性、安全/许可范围和静态检查，没有重跑应用全量测试、重建APK、启动模拟器或操作实体设备。原始日志仍保存在本地，因此部分历史文档中的build/evidence链接不在公开仓库内。来源检查中本机用户名改为USER，私有资料ID删除；需要原作者工作树/提交对象的历史导入或研究脚本不是公共仓库的构建前置条件。

主线源起点为496afd3936c7a601a5fcb34af4a4820aa76d51f3，包含该检出当前尚未提交的开发源文件，不能用该旧commit代表当前源。候选76个输入与公开Android文件逐字节一致；其他独立实验不在候选APK内。封版PRD v1.5未更改，PRD正文不是构建输入且不随本次源码上传。完整G0–G3未签收。

## 排除与许可

未上传任何签名私钥/密钥容器、账号凭据、.git历史、SDK/JDK、缓存、运行数据库、原始运行日志、下载的第三方二进制或旧APK中间产物。独立UVCAndroid AAR的分发许可尚未清理，已排除。

**项目自身许可证尚待作者选定。** 当前发布没有代选MIT、Apache或其他许可证。第三方摘录保留其自己的许可；请阅读 [LICENSE_STATUS.md](LICENSE_STATUS.md)。公开源码不替代明确的再使用许可。

## 保留的检查问题

发布前只读验证发现，`android/uvc-integration/run_checks.py` 的 `load_imports()` 在原始工作树和公开副本均报告 `Imported files missing/extra: ['provenance/README.md']`。这是原有清单范围问题，本次不修改开发源码。已单独核验清单列出的44个文件的长度、SHA-256和Git blob均匹配，但不声称完整UVC校验器通过。
