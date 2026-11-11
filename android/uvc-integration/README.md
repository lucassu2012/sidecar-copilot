# UVC 隔离集成

这里把已审查的 Java adapter、USB connection owner 和 Android USB bridge 放到同一份公共合同下重新编译和验证。模块尚未连接主 APK、真实 native backend 或设备采集；主应用的权限、Manifest、开始入口和设备门禁均未因本次导入而改变。

## 精确来源和文件归属

| 本目录 | 原工作区 | 精确 Git 提交 | 内容 |
| --- | --- | --- | --- |
| `adapter/` | `task-4/sidecar-uvc-adapter` | `ff46084debcfef8b29420fdb786c5ddfc3e19981` | 3 个生产源、3 个宿主测试源 |
| `owner/` | `task-4/sidecar-usb-owner` | `3e5abb1b880040ceff775c5cd43d5ad61115af9b` | 2 个生产源、4 个宿主测试源 |
| `bridge/` | `task-4/sidecar-android-usb-bridge` | `62eb3485a056c91720609f38ba436122bfdcb420` | 4 个生产源、4 个测试源、14 个 Android 替身源、独立 Manifest |

[source-manifest.json](provenance/source-manifest.json) 为全部 44 个导入文件记录原仓库、精确提交、路径、Git blob、字节数、SHA-256 和目标路径。导入时三个工作区均为指定提交且无未提交变更，每个工作文件与提交对象逐字匹配。导入代码未改包名、接口或实现。

公共合同仅来自主线 [android/uvc-contract/src](../uvc-contract/src)。三个交付所引用的三份合同快照都已与这三个主线文件逐字比对，记录在来源清单中；没有复制 `contract-baseline`。主集成拥有公共合同和产品接线，本目录拥有隔离 adapter/owner/bridge 与验证编排。未来接口或来源修改必须重新协调、更新精确输入并保留原结果，不能自动把变化视为原提交的内容。

[import_sources.py](import_sources.py) 记录一次性离线导入过程，已有目标时拒绝覆盖。日常验证使用下述 runner，无需外部工作区仍存在。源码来源清单不会由 runner 自动刷新。

## 可重复验证

在项目根目录使用已安装的 Python 3.10+、JDK 17、Android 35 SDK 和 35.0.0 build-tools：

```powershell
python -B android/uvc-integration/run_checks.py --jdk 'E:\tools\jdk-17.0.19+10' --sdk 'E:\tools\android-sdk'
```

runner 依次执行以下步骤，全部输出写入新的 `build/run-*/`，失败也保留命令、时间、退出码和日志：

1. 校验导入文件、主线合同和 SDK 输入哈希，记录本次源码、脚本和工具来源。
2. 将主线合同 3 源与本目录 9 个生产源，使用真实 Android 35 SDK 编译一次。禁止隐式源码搜索和注解处理器。
3. 生成隔离生产 JAR 和 min API 30 DEX，使用 AAPT2 检查本目录的独立 Manifest。
4. 分开编译 Android 替身和测试，拒绝测试类覆盖生产类。公共合同、adapter、owner 检查使用纯宿主路径；bridge 检查使用已经由真实 SDK 编译的同一组生产 class 和明确的 Android 替身。
5. 校验源码和生产 class 未在运行中变化，保存产物哈希及终态收据。

主线 `ContractChecks` 以及 adapter、owner、bridge 的测试均在这次集成上重新运行；旧交付的通过数量和历史验证文件没有导入为本目录的新结果。每次结果仅覆盖其收据中的精确输入。`manifest-check.apk` 是 AAPT2 的代码为空的 Manifest 检查产物，未装入 DEX、签名或安装，不能当作可部署应用。

## 实际接线和停止边界

生产代码提供以下可组合部件：`IsolatedCaptureSession` 维护后端调用/回调/缓冲义务；`UsbConnectionOwner` 管理平台连接及 FD 借用；`AndroidPlatformUsbPort` 调用 Android USB API；`AndroidUsbLifecycleBridge` 提供显式用户动作、权限及生命周期停止入口。构造、attach 和 grant 不自动打开设备或开始采集。

bridge 的宿主组合测试实际实例化上述生产类，并通过受控后端走借用、open/start、回调像素、停止、native 排空、归还 lease、owner close 和应用缓冲释放，最后汇入主线 `StopBarrier`。后端、Android 系统和应用像素持有者仍是测试替身；生产 lifecycle 的 `openFromUser()` 只调用 owner 的 open，不提供完整产品采集流程。真实应用的 session open/start 编排、借用归还、native drain 更新、发布门禁和 `BufferEvidence` 生产者仍需接线。

必须继续保留这些合同：submit 返回前可能已回调；超时或 cancel 的 NOT_FOUND 不代表调用退栈、回调退出或可释放；FD 0 有效但不代表授权，新 owner/boot/run/stop 身份不可混用。native、平台 owner 和应用缓冲分别提交停止证据，某一支完成不能代替其他分支；应用仍持有帧时不能确认完整停止。既有 4000 状态探索只是一项有界模型记录，本次不执行或提升其证明范围。

## 来源材料和未完成门禁

`provenance/adapter`、`provenance/owner`、`provenance/bridge` 保存各提交的原始来源与缺口说明。它们保留原交付的时间语境和相对链接；例如 adapter 交付时尚缺 owner、owner 交付时尚缺 Android bridge。当前目录以本 README 的组合边界和新运行收据为准，不能把原文中的旧结果或缺口描述直接当作当前结果。缺失的历史链接应通过来源清单中的原提交与路径追溯，不重建或改写旧证据。

Android 来源材料中的 AOSP URL、哈希和源码锚点仅为已提交的参考元数据。本次没有获取或重新验证其完整文本缓存，也没有把 AOSP 源码复制进运行代码。导入的 Java 实现和测试来自本项目的三个独立工作区，没有引入 libusb、libuvc、JPEG、UAC、Hutool、JNI、AAR 或其他第三方运行二进制。SDK 是既有编译输入；调用 SDK 与复制、链接或分发平台实现的许可范围不同。原生来源、构建和分发闭包仍需按实际选择的版本和产物另行核实，既有许可说明未被本次宿主通过结果替代。

尚未执行 Android 框架/Binder、系统权限对话框、真实 USB FD、OEM 行为、JNI/native 回调、UVC 格式与画质、UAC 声音、热耗和长时采集验证。未选择手机/采集卡/固件/Hub/供电/线缆组合，未启动模拟器或真实设备；G2/G3 和真实采集门禁不因此通过。这里不新增冻结 PRD 的功能、状态或测试编号。
