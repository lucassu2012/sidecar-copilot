# G0 实现选择与待评审边界

日期：2026-11-10。状态：参考实现，待接口/独立安全评审，不是生产批准。

采用 Python + SQLite + cryptography 建立可执行控制契约，先验证隐私不变量。Android 原生实现与后端传输通过后续 Adapter 接入；不把桌面 Python 程序安装到工作电脑作为采集代理。本原型运行于开发电脑，仅生成合成数据。

选择标准 AESGCM 与 RSA-OAEP-SHA256，逐帧独立随机 DEK 作为性能基线；不实现自创棘轮。使用 [cryptography AEAD 文档](https://cryptography.io/en/latest/hazmat/primitives/aead/) 与 [RSA 文档](https://cryptography.io/en/latest/hazmat/primitives/asymmetric/rsa/) 的接口。每个 DEK 只加密一帧，nonce 为随机 12 字节；AEAD AAD 绑定 ID、owner、会话、流、时间、boot、代次、期限与格式。独立身份、时间、哈希和帧偏移检验防止常见映射混淆；并未证明抗整个数据库回滚。

物理段先追加 ciphertext、flush/fsync，随后 SQLite FULL 同步事务记录索引。失败不确认该帧；恢复截去超出 committed_length 的尾部。已提交范围逐帧哈希校验，损坏拒读。当前仅一个进程写入，线程间使用同一 RLock 线性化暂停、采集、发送及删除；真实 Android callback/socket/服务端各自仍须独立门禁和竞态验证。

删除事务保留时间范围 tombstone，去掉被删帧密钥记录和合成索引，并隔离依赖产物。紧凑重写只复制仍合法帧：写新段→fsync→逐帧校验→事务切换映射→释放旧段。错误保留旧的唯一合法副本；下次恢复清理未映射文件。当前不做打洞，不声明密码学不可恢复或物理擦除；SQLite WAL/历史/备份仍须生产方案处理。

软件 unlocked 标志只是模拟 G3 出域屏障。测试对象中私钥常驻内存，因此不能证明“锁屏不可解密历史”威胁目标；Android 必须使用真实 Keystore、认证配置、通知及急停支持矩阵，逐设备测量后再开放。

处理器仅将固定合成文本索引/串接为摘录，不调用模型。来源文字是数据，无工具执行通道。输入 manifest 绑定来源 revision 与 attachment_revision；删除/取消/更正后旧任务不能发布。G0 暂保守阻止整会话取消范围，不将此实现当作完整 PR 34 验收。

性能风险包括逐帧公钥开销、每帧 fsync、SQLite 索引扫描和全段重写。当前没有基于实测选择批量提交、生产段大小或索引方案。先守住可恢复性，再测 Android 温升、电量、存储及尾延迟；超限时保持能力未支持，不降低隐私门禁。
