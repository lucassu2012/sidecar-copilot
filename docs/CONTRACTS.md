# G0 契约草案（待评审）

核心接口为 `sidecar.core.Sidecar` 的本地 Python 方法。旧合成本地 HTTP API 的路由与认证契约见 `contracts/openapi.json` 和 `PROCESSING_SLICE.md`。独立密文接收器的真实回环传输另见 [TRANSFER_WIRE_V1.md](TRANSFER_WIRE_V1.md) 与 [transfer-v1.schema.json](../contracts/transfer-v1.schema.json)，两套 API 的字段和时间单位不能混用。它们覆盖部分接口族，仍不等于完整第 16.2 节契约或已通过评审。

| 方法 | 输入和关键结果 | 约束 |
| --- | --- | --- |
| create_session / start / pause / end | owner、session、mode、consent、generation | 单活动采集；准备不开录；结束后不恢复；暂停挂起旧上传 |
| prepare_in_person | owner、desktop session → 稳定目标 ID | 幂等暂停桌面；全局暂停继续阻止 start |
| capture | owner、session、generation、stream、kind、start/end、合成文字 | 当前范围必须合法；最多 10 秒；源类型按模式；写盘后提交 |
| read | owner、frame ID → 合成载荷 | 认证标志、归属、撤销、删除、期限、元数据、哈希、AEAD |
| network / Sidecar.upload（旧合成索引） | 授信、非计费、network_generation、frame ID | 默认失败关闭；该方法只是模拟提交；重复 ID 返回同一结果 |
| UploadCoordinator / Receiver（独立传输切片） | 不可变 Binding/Manifest、单独删除代次、密文分片 | 真实回环 HTTP 与持久队列/接收器；媒体发送必须经过 guard；远端验证不代表处理就绪；仅合成材料 |
| delete_range / compact / recover | session、半开区间 [start,end) | 删除整相交帧；瞬时笔记按创建时刻；tombstone 独立保留 |
| add_note | session、text、scope、ranges | ended 可补；背景/range/unassigned 分开；新资料独立缓冲 |
| search / EvidenceService.episodes | owner、关键词 → 带来源的结果 | 核心单源子串查询及同会话/boot候选窗联合查询；非自然语言推理 |
| correct | frame、base_revision、合成更正 | 冲突抛 Conflict；更新索引、隐藏旧产物、期限不变 |
| request_job / finish_job / artifact | ended session、manifest、附件版本 | 全部合法输入已上传后生成合成摘录；旧快照拒绝 |
| cancel / revoke | owner、session | 取消本轮与阻止自动分离；once例外不永久解禁；没有远端撤销或计费保证 |

`Blocked` 表示当前权限/门禁/数据健康不足；`Conflict` 表示基线或输入快照过时；不自动扩大许可重试。调用方输入必须为合成素材。适配真实 API 前须增加身份凭据、设备绑定代次、policy_scope、模型配置、费用、可信时间和服务端独立校验；本原型的 owner 字符串不提供认证。

`contracts/frame.schema.json` 是物理位置与缓冲元数据的最小草案。字节偏移是存储映射，frame ID 是不可变业务身份；紧凑重写不改变 frame ID、来源时间和期限。schema 校验不替代权限检查或 AEAD。未暴露密钥或其导出接口。

旧 frame schema 的语义保持不变，不用它验证接收器 Manifest。新的 transfer wire 要求严格整数纳秒、不可变 stream/boot/key 引用和独立删除代次；schema 描述数学类型与值域，严格解析类型和词法仍须代码补验。其中 Binding 是服务端 token 映射，不能由请求体选择。旧静态 OpenAPI 不覆盖此 wire；接收器运行时 OpenAPI 也不替代严格解析、跨字段和持久门禁校验。wire v1 是文档/schema 版本，不新增请求版本字段、Header 或路由前缀。
