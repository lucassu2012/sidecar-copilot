# Transfer wire v1：现有合成回环协议

本文归一当前 Python 发送队列、HTTP adapter 和 SQLite receiver 的协议，供后续 Android 适配对照。它不修改封版 PRD、生产接口、旧帧存储格式或验收编号。当前只处理本机合成材料的既有密文；没有真实云服务、Android 上传客户端或设备身份与可信时钟证明。

## 范围与版本

机器可读结构见 [transfer-v1.schema.json](../contracts/transfer-v1.schema.json)。按路由选择其中的 `$defs`，不能只验证顶层消息联合后便允许任意路由使用任意消息。

“wire v1”是本说明和 schema 的版本。现有请求没有 `wire_version`、版本 Header 或 `/v1` 路径；不要擅自加入。`Manifest.media_version` 固定为整数 `1`，FastAPI 的 OpenAPI 信息版本为 `0.1.0`，二者都不提供协议版本协商。请求及 Binding/Manifest/Deletion 中的未知字段、或不支持的媒体版本必须按当前代码拒绝，不能降级猜测；响应外层字段的兼容行为见下文。

本说明核对的实现 SHA-256：

| 文件 | SHA-256 |
| --- | --- |
| `sidecar/transfer_contract.py` | `7a1ef2d33c0b922eb72f34ca0c79f6def51c7e5d483cd9ba747684dff7588ded` |
| `sidecar/transfer_http.py` | `0c88ebcbccd12e69395e654ee8a57554383f648a148002d55934030211162f68` |
| `sidecar/transfer_receiver.py` | `cd1e154aa0162396ad15a8da0af620294a24e124adc03385e0a791eac477a67f` |
| `sidecar/transfer_queue.py` | `a67aa52ee50fc62bfaacd575ba50a0c3a535a7e6eb2127c507b78334f2dd2c36` |

`contracts/frame.schema.json` 描述旧本地物理存储映射，包含浮点秒和段偏移；它不是本协议的 Manifest。`contracts/openapi.json` 与 [PROCESSING_SLICE.md](PROCESSING_SLICE.md) 描述旧合成本地 API，不能作为 receiver wire 的完整契约。receiver 自身的 `/openapi.json` 也不能表达手工 `Request` 解析中的全部字段、词法和持久门禁。`Sidecar.upload()` 的旧模拟提交与 `UploadCoordinator` 的真实回环传输必须分别说明；本地队列 `status()` 不访问远端，也不等于远端 status。

## 基础类型、单位与不可变记录

标识符是完整匹配 `[A-Za-z0-9_.:@-]{1,160}` 的 ASCII 字符串。摘要和 upload ID 是 64 位小写十六进制字符串。所有 JSON 整数字段必须在解析后满足 `type(value) is int`；拒绝布尔值、`1.0`、`1e0`、数字字符串及越界值。通用非负整数上限为 `9223372036854775807`（`2^63−1`），并不是 JavaScript 的精确整数上限。客户端必须保留完整十进制整数，不得经浮点数舍入再编码。

JSON Schema 的 `integer` 按数学值判断，因此可接受 `1.0`；单靠 schema 不能重现当前严格解析规则。[JSON Schema 官方整数说明](https://json-schema.org/understanding-json-schema/reference/numeric#integer)明确了这一差异。UTF-8 JSON 还需拒绝重复键、NaN、Infinity 和溢出为无穷的浮点数；请求及 Binding/Manifest/Deletion 的缺失字段、未知字段均不属于兼容扩展。

Binding、Manifest、Deletion 均要求完整字段集合，无可省略字段。Binding 由服务端 bearer token 映射决定，请求不能提交或选择 Binding。

| 记录 | 字段及约束 |
| --- | --- |
| Binding | `owner_id`、`tenant_id`、`device_id`、`policy_scope_id`、`processor_id`、`key_scope` 为标识符；`binding_generation` 为 `1..2^63−1`。整组参与身份隔离和摘要。 |
| Manifest 身份 | `frame_id`、`session_id`、`stream_id`、`boot_id`、`key_ref` 为标识符；`boot_id` 不可 null。`source_type` 仅为 `audio`、`desktop_frame`、`manual_note`。 |
| Manifest 来源时间 | `clock_basis="monotonic_ns"`；`sample_start_ns`、`sample_end_ns`、`eligible_at_ns` 均为同一 `boot_id` 内的非负单调时钟纳秒。 |
| Manifest 期限与版本 | `expires_at_utc_ns` 为 Unix epoch 起算的 UTC 纳秒；`capture_generation` 为非负整数，允许 0；`media_version=1`。 |
| Manifest 密文 | `byte_length` 为 `1..8388608` 字节；`sha256` 为整个既有密文的 SHA-256；`key_ref` 只是引用，不包含 DEK。接收器不解密，不规定新的 AEAD/AAD 或密文封装。 |
| Deletion | `deletion_id`、`session_id` 为标识符；`boot_id` 必填，可为标识符或显式 JSON null；`start_ns`、`end_ns` 为非负单调纳秒；`generation` 为 `1..2^63−1`。 |

普通来源使用半开区间 `[sample_start_ns,sample_end_ns)`，必须 `start < end`。只有 `manual_note` 允许 `start == end` 的瞬时点；手工笔记也允许非零区间。必须满足 `eligible_at_ns >= sample_end_ns + 900000000000`（900 秒）；Java 等实现必须使用检查溢出的加法。不能把溢出值回绕后接受。该结构约束不证明当前已经等待满 900 秒，实际发送还需要下述时间门禁。

删除区间为非空 `[start_ns,end_ns)`。同一 Binding、同一 session、且 boot 匹配时：非零来源与删除相交当且仅当 `delete.start < source.end && delete.end > source.start`；瞬时笔记命中当且仅当 `delete.start <= point && point < delete.end`。删除不含 stream 字段，覆盖匹配范围内所有相交流。

`Deletion.boot_id=null` 明确保留旧的跨 boot 范围屏障：对该 session 每个 boot 的数值区间分别应用，不是墙钟区间，也不能用当前 boot 替换或省略字段。非 null 只匹配该 boot。

Android 样本序号、单调时间、UTC 毫秒不能直接互换。当前 Python 合成适配把来源秒换为纳秒时，start 向下取整、end 向上取整、eligible 取来源向上取整与 `end+900秒` 的较大值、expires 向下取整；这不构成 Android 时钟映射或跨重启可信校时实现。接收器注入的 UTC 被视为上层已验证时间；真实可信时间 provider 仍未实现。

## HTTP 路由与载荷

当前客户端只接受字面地址 `http://127.0.0.1:<port>`，端口 `1024..65535`；没有域名解析、代理、重定向跟随或生产 HTTPS 配置。每个已注册路由，包括 `/openapi.json`，都要求恰好一个 `Authorization: Bearer <token>`。服务端将 token 映射到不可变 Binding。token 语法完整匹配 `[A-Za-z0-9._~+/-]{32,512}={0,2}`，即 32..512 个基字符加最多两个末尾等号；长度检查不证明随机性，调用方须提供随机 token，不能输出或放入日志。

| 方法与路径 | 输入 | 成功输出 / schema 定义 |
| --- | --- | --- |
| `POST /uploads/init` | JSON `{manifest, deletion_generation}` | `ObjectReceipt`；请求为 `InitRequest` |
| `PUT /uploads/{upload_id}/parts/{index}` | 原始密文字节；恰好一个 `X-Deletion-Generation` | `ObjectReceipt`；没有 JSON 分片包装 |
| `POST /uploads/{upload_id}/commit` | JSON `{deletion_generation}` | `ObjectReceipt`；请求为 `CommitRequest` |
| `GET /uploads/{upload_id}/status?deletion_generation=N` | 恰好这一个 query 参数且只出现一次 | `ObjectReceipt` |
| `GET /uploads/{upload_id}/object?deletion_generation=N` | 同上 | 原始 `application/octet-stream` 密文 |
| `POST /deletions` | JSON `{deletion}` | `DeletionReceipt`；请求为 `DeletionRequest` |
| `GET /deletions/{deletion_id}` | 不允许 query | `DeletionReceipt` |

以上成功状态均为 HTTP 200。除两个带代次的 GET 外，其余五个业务路由均拒绝 query。有请求体时，JSON 的 `Content-Type` 为 `application/json`，分片为 `application/octet-stream`；必须恰好一个 Content-Type，媒体类型比较忽略大小写并允许分号参数，但 JSON 实际只按 UTF-8 解码。元数据请求上限 262144 字节，分片请求上限 65536 字节。Content-Length 可省略；提供时必须唯一、合法十进制且与实际长度一致。响应带 `Cache-Control: no-store` 与 `X-Content-Type-Options: nosniff`。

header、query 和分片 index 的整数文本完整匹配 `0|[1-9][0-9]{0,18}`，然后检查 `<=2^63−1`。拒绝前导零、正负号、空白和指数；这不是 JSON 整数字段的字符串替代。upload ID 必须为 64 位小写十六进制。Deletion ID 路径按标识符规则检查，客户端按路径段转义。

分片大小 `PART_BYTES=65536`，`part_count=ceil(byte_length/65536)`，索引从 0 起。每片长度必须精确等于 `min(65536, byte_length-index*65536)`；最多 128 片。64 KiB 分片和 8 MiB 对象上限是当前本地实验限制，不能写成冻结 PRD 的产品上限。

## 身份、幂等与回执

对象 ID 仅由 Binding 和 frame ID 推导。固定 Binding/frame ID 下 Manifest 的所有字段不可变；修改时间、期限、密文摘要、key_ref 等字段不会创建“同一对象的新版本”，而会产生幂等冲突。同索引相同分片可重复提交，不同内容产生 `409 idempotency_conflict`。

每次对象请求的 `deletion_generation` 独立于 Manifest，初值为 0，必须**等于**服务端 Binding/session 当前代次；旧值和未来值均拒绝。删除后的相邻合法对象也必须携带新的当前代次；对象 ID 与 Manifest 摘要保持不变。已相交对象即便带最新代次仍被屏障拒绝。删除提交使用 Deletion 内的 `generation`，删除状态查询不携带代次。

`ObjectReceipt` 字段为 `upload_id`、`state`、`manifest`、`manifest_hash`、`deletion_generation`、`received_parts`、`part_bytes=65536`、`verified`。当前只返回 `staging` 或 `committed`；删除受阻时返回错误，不返回 `state=deleted`。`received_parts` 为已验证分片的严格整数索引，排序、唯一、在该 Manifest 范围内。committed 必须列出全部索引且 `verified=true`；staging 由接收器输出 `verified=false`。

commit、committed status 和 object read 都重新检查准入与持久对象完整性。commit 只有在提交后重新读取元数据及全部分片、核对长度和 SHA-256 后才能成功返回；提交事务与后续读取不是同一个事务。`verified` 表示字节/摘要一致性，不表示 AEAD 验证、真实来源可信或下游处理完成。当前客户端不拒绝响应外层未知字段，且 staging 的 verified 标志没有独立强制检查；schema 描述接收器实际输出形状，不声称所有接收端已逐项执行 schema。嵌套 Manifest 仍按完整不可变记录核对。

新 deletion ID 必须使用 `generation=current+1`；相同 ID、相同完整 Deletion 重试幂等，不再递增。同 ID 改内容为 `409 idempotency_conflict`；未见过的旧 ID、跳代均为 `409 deletion_conflict`。后续代次前进后，重试已知旧 ID 仍返回该删除的原代次回执。

接收器先在独立 SQLite 事务提交屏障和代次，再清理受影响分片。清理失败不能回滚屏障或报告删除完成，而返回 `503 cleanup_pending`。`GET /deletions/{id}` 返回 `blocked=true`、`cleanup=pending|complete`、`physical_erasure=not_guaranteed`；重开接收器会重试 pending 清理。`complete` 仅表示受管理分片清理完成，不保证 WAL、旧备份或闪存物理擦除。当前清理还会解析同会话其他 Manifest，损坏的邻近元数据可能使清理保持 pending；不得以拒读代替清理完成。

已观察到过期的对象在返回 `410 expired` 前先持久记录拒读屏障，时钟回拨或同 frame ID 重试不能复活。期限不延长；过期拒读本身不清理密文分片，不能报告级联删除已完成。当前 receiver 信任注入 UTC（默认 `time.time_ns`），只检查非负整数类型；任何前跳值达到期限都会建立持久过期屏障。可信 UTC 提供者与异常前跳识别尚未实现，不能把这项保守拒读结果当作真实期限已经到达的证明。

## Canonical JSON 与摘要

当前 canonical 算法精确定义为 Python `json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(',', ':'), allow_nan=False).encode('utf-8')`。无 BOM、缩进、空格或末尾换行；对象键排序，数组保留次序；整数用十进制编码，不做 Unicode 归一化。它是当前受限字段集的算法，不是通用 RFC 8785/JCS 实现。当前记录的键和字符串均限制为 ASCII，跨语言实现应按下列向量逐字节核对。

```text
binding_key   = hex_lower(SHA256(canonical(Binding)))
manifest_hash = hex_lower(SHA256(canonical({"binding": Binding, "manifest": Manifest})))
upload_id     = hex_lower(SHA256(canonical([binding_key, frame_id])))
```

`deletion_generation` 不进入以上任何摘要。不得把 Binding.key 换成原始 Binding 放入 upload ID 数组，也不得把摘要的大写 hex、JSON 空白或浮点形式视为等价字节。

以下是可重算的**哈希格式向量**。`00010203` 仅为人工四字节输入，不是有效 AEAD 密文或真实媒体示例。下面对象只是向量容器，不是新增请求结构：

```json
{
  "binding": {
    "owner_id": "alice", "tenant_id": "local", "device_id": "synthetic-device",
    "binding_generation": 1, "policy_scope_id": "development",
    "processor_id": "opaque-receiver", "key_scope": "local-key"
  },
  "manifest": {
    "frame_id": "vector-frame", "session_id": "vector-session", "stream_id": "C",
    "source_type": "audio", "boot_id": "vector-boot", "clock_basis": "monotonic_ns",
    "sample_start_ns": 0, "sample_end_ns": 5000000000, "eligible_at_ns": 905000000000,
    "expires_at_utc_ns": 1800000000000000000, "capture_generation": 1,
    "media_version": 1, "byte_length": 4,
    "sha256": "054edec1d0211f624fed0cbca9d4f9400b0e491c43742af2c5b0abebf0c990d8",
    "key_ref": "frame:vector-frame"
  }
}
```

| 结果 | 小写 SHA-256 |
| --- | --- |
| binding_key | `6a8e86f9b9d23e0a05e07e2cfc5299fe9c4d6f3f012f87259be13def83b6fdd7` |
| manifest_hash | `813e2db9169b76f5b2ba45187daa08d3739861f482fef46dc87d361c10d8f988` |
| upload_id | `f4af7fe44a3fc43746e024611e40717d68afecf8f895a295ad7b990329846fbb` |

## 错误与不确定结果

受控错误体为 `{"code":"lowercase_code"}`，代码完整匹配 `[a-z][a-z0-9_]{0,63}`。下表记录当前实现，不能把枚举当作拒绝所有未来错误码的理由。未注册路由的 404/405 等框架响应可能是其他结构；客户端将不可识别、截断、错误长度或错误内容类型的响应视为 `UncertainTransfer`，不能凭 HTTP 码猜测提交结果。

| HTTP | 当前代码及含义 |
| --- | --- |
| 401 | `authentication_required`：身份不通过，附 `WWW-Authenticate: Bearer`。 |
| 404 | `not_found`：该身份下对象不存在；`unknown_deletion`：删除 ID 未登记。 |
| 409 | `idempotency_conflict`：同身份/ID 的不可变内容冲突；`deletion_conflict`：代次或范围屏障拒绝；`incomplete_upload`：尚未完整提交。 |
| 410 | `expired`：已持久记录期限拒绝。 |
| 413 / 415 | `request_too_large` / `unsupported_content_type`。 |
| 422 | `invalid_identity`、`invalid_integer`、`invalid_fields`、`unsupported_source_or_clock`、`invalid_hash`、`invalid_source_range`、`insufficient_buffer`、`invalid_deletion_range`、`invalid_upload_id`；HTTP 另有 `invalid_request`、`invalid_query`、`invalid_json`、`invalid_generation`；分片另有 `invalid_part`、`invalid_part_length`、`hash_mismatch`。 |
| 500 | `integrity_error`：已存记录/分片损坏；`invalid_response`：接收器输出错误；`internal_error`：HTTP 边界隐藏未分类异常。不输出异常原文、路径、密文或 token。 |
| 503 | `receiver_closed`、`clock_unavailable`、`storage_unavailable`、`cleanup_pending`。`durability_unavailable` 属于 Receiver 初始化失败，通常发生在启动 HTTP 前。 |

未分类 TransferError 可被边界归一为 `transfer_error`。HTTP adapter 支持传递合法 429 与非负有限秒数的 Retry-After；当前接收器没有实现生产限流服务。客户端只接受十进制秒（可含小数），不接受 HTTP 日期格式。

adapter 不自动重试。超时、断连、响应丢失都可能发生在服务端已提交之后；必须以相同 ID/Manifest 先查远端 status，再决定补片或 commit，不能新建 ID 或重置预算。删除响应不确定时先查相同 deletion ID；`cleanup=pending` 不能转成完成。客户端校验对象读回长度/摘要与最终 status，再结合本地门禁确认远端已验证；本地 `remote_verified` 缓存不是每次重新查远端的证明，也不是下游处理就绪。

队列持久保存尝试次数、字节预算和退避；429、5xx 或不确定结果只在既定有界策略内重试。`409 deletion_conflict` 只有在本地代次已前进且原帧仍合法时才按新代次重试；不能泛化为忽略所有冲突。该预算不等同供应商计费保证。

## Guard 必经路径与持久边界

`LoopbackTransport(..., guard=None)` 保留给接收器/传输夹具，不能直接作为媒体上传入口。媒体传输必须经过 `UploadCoordinator` 的受控发送路径；包括 HTTP headers 在内，每次 `socket.send` 前都调用 guard，而非仅在 init 或一次完整请求前检查。

guard 与本地删除/outbox 事务共用 `Sidecar.lock`，检查来源身份、删除/期限/隔离状态、解锁状态、隐私暂停、会话上传暂停、同 boot 可信单调时间和缓冲成熟、网络授信/非计费/代次、当前删除代次及无未同步删除。它在锁内持久预扣最多 65536 字节，再执行一次非阻塞 socket syscall；已知部分发送退回未发送额，EAGAIN 退回全部，未知结果保留保守计数。connect、等待可写、等待响应不持有该锁，但 SQLite 持久提交仍在锁内；本说明不承诺慢盘下停止响应的固定上限。

本地 gate 与远端 SQLite 是两个事务域；已进入内核/网络的字节不可召回，失败响应不能证明没有出域。Java 缓冲 HTTP 写入、透明重试或连接池不能自动继承 Python 的逐 syscall 门禁保证，Android 适配前必须重新验证这一边界。

删除控制通道仅检查网络授权与网络代次，允许锁屏、媒体暂停或会话撤销后继续同步无媒体/无密钥的删除指令。它不计入媒体字节预算，当前没有独立持久退避调度，也没有安装无人值守 worker。处理任务取消、上传暂停和隐私删除是不同的既有操作：取消处理不等于远端删对象，暂停不删除既有远端对象，本地删除/outbox 落盘不等于远端清理完成。相同网络删除重试应复用 outbox 的删除 ID，不应再次调用本地 delete_range 生成另一项删除。

## Schema 与代码校验分工

| JSON Schema 可表达 | 必须由解析器、代码或持久状态执行 |
| --- | --- |
| 必填字段、禁止额外请求/record 字段、类型、常量、枚举、标识符字符集、整数值域 | 重复 JSON 键、严格整数词法/解析类型、HTTP 重复 Header/query、UTF-8 与实际字节长度 |
| 期限/来源时间字段各自为非负整数 | 时间单位来源真实性、boot 对应、start/end 顺序、900 秒加法与成熟、可信 UTC 与跨重启规则 |
| 分片索引数组唯一、长度最多 128、每项 0..127 | 排序、实际 part_count 边界、最后片精确长度、已收片实际哈希、committed 全片齐备 |
| 回执字段与 state/verified 输出关系 | Binding token 归属、immutable Manifest 对比、canonical 摘要、幂等、每请求代次、范围命中与期限屏障 |
| 删除回执 pending/complete、物理擦除不保证 | 屏障先提交、清理后完成、崩溃恢复、WAL/备份边界、逐次发送 guard 与持久预算 |

schema 允许响应外层额外字段，以反映当前消费者的兼容行为；这不允许修改请求或嵌套 Manifest。schema 校验通过不表示接口已在 Android、真实采集设备或生产环境验收，也不替代冻结 PRD 的阶段门禁。
