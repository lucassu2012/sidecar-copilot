# Android USB 权限接收与生命周期桥

本目录在 `3e5abb1b880040ceff775c5cd43d5ad61115af9b` 的 owner 边界上新增实际 Android API 调用：权限 PendingIntent、非导出的接收器、系统拔线广播、UsbManager/UsbDeviceConnection 和显式生命周期接线。生产代码用真实 Android 35 SDK 编译；运行验证使用隔离的宿主 Android 替身。没有真实 Android/Binder 执行或设备结果。

全部新增文件位于本目录。父层 owner/adapter/公共类型和主线 App、Manifest、Service、构建脚本均不修改。[inputs-lock.json](inputs-lock.json) 固定 22 份继承输入及 5 份已安装 SDK 输入；主线观察到的 min API 30、target API 35 另有来源散列。冻结 PRD 不新增功能、状态分支或测试编号。

## 已实现的接线

- [AndroidPlatformUsbPort](src/local/sidecar/copilot/usbbridge/AndroidPlatformUsbPort.java) 实际调用 Android USB API。它用已选择设备、完整 Binding 和外部可信 AttachmentGuard 做前后检查；设备名、VID/PID 和 FD 值不代替 attachment epoch。
- [PermissionReceiver](src/local/sidecar/copilot/usbbridge/PermissionReceiver.java) 与进程内 registry 核对 action、data、component、设备元数据及确实存在的 Boolean 授权结果，然后只消费一次。创建 PendingIntent 时不预填系统结果 extras。新的进程没有旧 registry，不能靠旧广播恢复准入。
- PendingIntent 显式指向本 App 的私有 receiver；随机 token 放入参与匹配的 action/data，独立于 extras。API 31+ 指定 MUTABLE，API 30 使用该平台的默认可变语义，两者均 ONE_SHOT；不使用 unsafe implicit 或允许覆盖路由字段的 fill-in flags。固定 AOSP 结果填充、匹配和 creator UID 依据见 [SOURCE_AND_GAPS.md](SOURCE_AND_GAPS.md)。
- [AndroidUsbLifecycleBridge](src/local/sidecar/copilot/usbbridge/AndroidUsbLifecycleBridge.java) 提供 attach、显式用户请求权限/打开、用户停止、host 生命周期停止和权限失效入口。构造、监控注册、attach/grant 广播都不调用 open 或 start；异步拒权也进入本轮关闭接线。没有 native factory、驱动装载或自动采集。

[AndroidManifest.xml](AndroidManifest.xml) 是隔离构建用的接收器声明：min 30/target 35、receiver 全限定名、`exported=false`、无 intent-filter。它没有 Activity、Service 或 USB attach 自动启动入口；不是已合并进主线的 Manifest。主集成审查后只合入必要 receiver 声明和调用点，不能把本文件整体替换 App Manifest。

## 生命周期与资源

首次停止序列先撤销应用发布准入，再关 owner/native 准入，最后撤销本地权限关联；PendingIntent.cancel 与 unregisterReceiver 交给可信、非阻塞且延后执行的清理 executor。receiver 本身只处理元数据和这些非阻塞入口，不执行打开、取 FD、connection.close 或 join。失败和同步重入不能跳过后续撤销，重复调用也不是停止成功回执。

已经进入的注册/请求可以晚于停止返回：晚取得的 PendingIntent 仍进入取消账本，迟到回调不能复活旧 owner。撤销本地 registry 或 cancel PendingIntent 不等于撤销系统已经授予的 USB 权限，也不能保证关闭已经出现或在途的系统授权对话框。

实际打开后得到的 UsbDeviceConnection 无论结果是否迟到，都交给既有 owner 清理；bridge 不根据 FD 数字自行关闭资源。完整 native 排空、所有 lease 归还及在途平台调用退出后，owner 才能唯一关闭 connection。应用像素仍由独立 BufferEvidence 约束，取消 receiver 不代表图像已清零。

port 内部打开前检查明确返回 false 时，尚未调用 openDevice，使用既有 SPI 的 null 表示未取得连接，避免误入资源归属未知的永久 pending。父 owner 将这条窄路径记为通用 IO；这里不承诺精确区分断连/权限原因。实际系统打开或预检查调用抛异常仍沿保守路径处理。

`onLifecycleStop` 是主线 host 的显式 hook，不替主线决定 Activity.onPause/onDestroy、后台服务或屏幕状态何时应停止。AttachmentGuard、产品开始确认、发布/遮盖及队列证据也必须接到主线真实生产者。本轮保留原生接入和真实采集门禁。

## 构建与复现

```powershell
python -B android/uvc-sim/native-experiment/android-bridge-experiment/run_checks.py --sdk 'E:\tools\android-sdk' --jdk 'E:\tools\jdk-17.0.19+10'
```

runner 分开完成：真实 Android SDK 编译生产代码；生成 JAR 和 min API 30 DEX；AAPT2 检查 receiver Manifest；独立编译测试专用 Android 替身和测试；最后运行最初那份 SDK 编译出的生产 class。测试不能重新编译或用同名类覆盖生产实现。所有产物只在忽略的 `build/run-*/`，不签名、不安装、不启动模拟器或 ADB。

`manifest-check.apk` 只是 AAPT2 的 Manifest 检查容器，没有拼装生产 DEX；不是可部署应用交付。最终执行记录、输入散列和具体限制见 [VALIDATION.md](VALIDATION.md) 与 [validation.json](validation.json)。本轮不重跑父层旧 suite，也不将历史计数合并进新结果。

## 后续集成仍需完成

主集成需要挂载 receiver 声明和 host 调用点，提供真实附件代次/权限观察、camera/privacy 前提及服务策略；在实际 Android 上核验 PendingIntent/Binder/广播保护、拒权/晚到结果和生命周期交错。原生驱动、许可证与构建分发闭包、JNI、设备兼容性和真实采集验证仍未由此桥解决。详细固定来源、许可和实机条件见 [SOURCE_AND_GAPS.md](SOURCE_AND_GAPS.md)。
