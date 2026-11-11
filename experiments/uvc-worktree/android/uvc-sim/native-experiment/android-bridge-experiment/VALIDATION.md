# Android USB 接收桥：构建与宿主验证

独立分支 `codex/uvc-android-bridge`，基线 `3e5abb1b880040ceff775c5cd43d5ad61115af9b`。仅新增 `android/uvc-sim/native-experiment/android-bridge-experiment/`，不修改父层代码或主线公共/App/Manifest/Service 文件。

## 执行与回执

```powershell
python -B android/uvc-sim/native-experiment/android-bridge-experiment/run_checks.py
```

2026-11-10 UTC 执行，以下阶段均退出 `0`：

| 阶段 | 实际输入与结果 |
|---|---|
| 生产 SDK 编译 | 3 份公共类型 + 3 份 adapter + 2 份 owner + 4 份本轮 bridge。只使用真实 Android 35 `android.jar`；`javac --release 8 -Xlint:all -Werror`。 |
| JAR / DEX | 生成生产 JAR；D8 使用 Android SDK library，以 min API 30 生成 `classes.dex`。没有测试类或 Android doubles。 |
| Manifest | 静态检查 min 30/target 35、唯一非导出 receiver、无 intent-filter/Activity/Service，再经 AAPT2 link 成功。 |
| 替身与测试编译 | 14 份项目自有 Android doubles 独立空 classpath 编译；4 份测试只引用生产 class 和 doubles，禁止隐式编译。 |
| 宿主执行 | 运行最初那份真实 SDK 编译的生产 class；输出 class 名不能重叠覆盖生产类，运行前后生产 class 散列相同。 |

```text
BRIDGE_CHECKS_OK cases=23 assertions=425 negative_controls=2
```

[validation.json](validation.json) 是 `build/run-20261110T184229-39f250db/result.json` 的逐字副本，SHA-256：

```text
ba3361b02c93d12fdbb5520097fbc79cc490c3dcf9267f431202b4f13371018f
```

回执含 38 个实际输入的大小/散列、完整命令、退出码、stdout/stderr、JDK launcher 身份和构建产物散列。22 份继承输入与 5 份 SDK 文件锁在执行前后均匹配，源文件不变。除工具 version 命令的正常版本输出外，各编译、打包、DEX 和宿主检查 stderr 均为空；host-checks 出现非空 stderr 会直接判失败，避免漏掉异步未捕获异常。

## 固定工具链与产物

- Android SDK Platform 15 / API 35，revision 2，extension 13；`android.jar` SHA-256 为 `4566663c3876e022b4fa4ced8c8697c4ab1688267f090114fd92d027b32e619b`。
- Android Build Tools 35.0.0；AAPT2 报告 `2.19-11948202`，D8 报告 `8.6.2-dev`，具体 build ID、二进制散列见输入锁/回执。
- 已安装 Temurin JDK `17.0.19+10`；使用显式 executable 路径，移除 Java 环境注入选项，禁用 annotation processing/implicit compile。

| 本次 ignored 构建产物 | 字节数 | SHA-256 |
|---|---:|---|
| bridge.jar | 91,492 | `5985e212265e0534239c269759ed7ed940e10d7b96e9e134093abbd486b74317` |
| dex/classes.dex | 77,160 | `86c8aa6e22a5498e91474a87848a828da40cf9cf85991b44451b8ba205699338` |
| manifest-check.apk | 771 | `fafc66cbaff8ff2ff89f79ed046948cfef4728fd5bd4d1319ccf2325c0861a48` |

这些产物只保留在本地忽略的 run 目录，不签名、安装、部署或版本化。Manifest 检查容器没有拼装生产 DEX，不是可运行 App 的交付物。复现的时间、目录、JAR 容器与回执散列可变化；固定输入、命令和行为结果才是复核依据，不声称跨机器逐字可重建。

## 23 个场景的实际范围

| 范围 | 验证内容 |
|---|---|
| 用户意图与权限 | 构造/监控/授权广播不自动 request/open/start；只有显式用户入口可发请求或打开。同步与异步拒绝均关 owner 准入、撤销发布、停止并清关联。 |
| 请求关联 | 错 action/data/component/device、缺少或错型 grant/device 不消费有效 registry；请求 identity 不仅靠 extras，one-shot、mutable 与 base/fill-in 规则按固定源码建立有限替身模型。 |
| 早到与迟到 | 回调早于 request 返回时保留调用义务；撤销后迟到 callback 不能重开；隔离 classloader 的空 registry 拒绝旧 Intent，代表进程状态丢失模型而非 Android 重启实测。 |
| 设备与 FD | detach / permission revoke / lifecycle hook 按发布撤销→owner 停止→本地撤销顺序执行；同数值 FD 的新 owner 不受旧连接清理影响。 |
| 打开竞态 | owner 前检通过、port 内再次 hasPermission=false 时，SDK open 调用为 0，owner 不进入 openUncertain，并可正常排空；通用 IO 不能被写成精确故障归因。 |
| 迟到 connection | open/getFD 在途期间停止，已取得资源归旧 owner；FD 0 正常、负值失败。不会在调用栈仍存活时提前 close，也不会把迟到结果交新 owner。 |
| 平台注册/令牌 | registerReceiver 尚未返回时不提前 unregister；PendingIntent factory 尚未返回时停止，迟到 PI 被补取消且不再调用 requestPermission。 |
| 清理失败 | port cleanup executor inline/reject 时不在调用栈上取消/注销，保留诊断与未清理状态；本地 registry 已失效不等于 Android 资源已清理。 |
| 停止重入 | publication gate 同步重入只认领一个停止序列；gate 抛异常仍继续关闭准入/撤销关联，不能伪造 BufferEvidence 或完整停止成功。 |
| 实际组合链 | 使用父层真实 adapter、owner、OwnedRgba、StopBarrier 与独立应用帧持有者。native/connection 已排空后，应用帧仍阻止完整停止，释放后才完成。 |
| SDK 分支 | 宿主模型设置 SDK_INT 30、31、35，覆盖 legacy/mutable、旧/新 receiver 和 Parcelable API 分支；没有在这三个 Android 系统上运行。 |
| 两个负对照 | 故意向未登记的 Manifest 接收地址投递，精确报告 `UNREGISTERED_MANIFEST_ADDRESS`；在地址已登记时改写 PI data，精确报告 `PENDING_INTENT_IDENTITY_MISMATCH`。不把任意异常算作成功。 |

线程交错使用 latch，不以 sleep/速度阈值判通过。等待期限仅用于判卡死失败，不证明 Binder 或 native 停止有界。fake dispatcher、PendingIntent cache、Parcelable 和 USB 对象只覆盖本桥调用的有限表面；两个负对照证明测试 oracle 会发现选定违规，不证明 Android 平台真实认证。

## 来源复核与缺口

另执行了默认离线命令：

```powershell
python -B android/uvc-sim/native-experiment/android-bridge-experiment/source-evidence/verify_sources.py
```

输出为 `Verified 15 sources and 41 anchors; restored 0 missing files.`。记录见 [source-evidence/validation.json](source-evidence/validation.json)。15 个完整 AOSP 文本保持 ignored；版本化 URL、commit、大小/SHA/blob 与 18 项结论的 41 个锚点可复核。此检查不编译或运行 AOSP 源，不重新下载或引入 binary。

本轮未修改 App 真实挂载点，未执行 Android Framework/Binder、真实权限对话框、USB FD、JNI/native、设备采集/UAC；未使用 ADB、模拟器、实体硬件或留存模拟器。未重跑父层 owner/adapter/公共合同/C++/Python suite，历史计数不累加。

仍需实际 App 的 receiver/host 挂载、附件代次与权限观察、CAMERA/privacy/服务策略、发布/遮盖和队列证据接线。真实 PI/Binder 保护、OEM 投递及生命周期、物理断连/重连、真实 native 退栈与 FD 关闭须在既有准入条件满足后另行验证；合成 PASS、SDK 编译、DEX 和 Manifest link 都不能替代这些结果。来源和许可边界见 [SOURCE_AND_GAPS.md](SOURCE_AND_GAPS.md)。
