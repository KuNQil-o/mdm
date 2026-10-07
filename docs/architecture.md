# V4 核心实现与边界

`backend/app/engine.py` 是纯配置引擎，不访问数据库或外部系统。它负责受控表达式、Schema 类型、定点十进制、单位、枚举/引用标准码、DAG 派生、Validation、Identity 与 Segment 模板。行业示例只在 `backend/demo/*.json` 中；seed.py 使用同一治理服务发布配置，不按行业选择不同发号实现。

`service.py` 是领域服务，负责配置版本、发布、不可变账本、Source Binding、查询解释和监控。`security.py` 管理会话、Caller 哈希凭据、角色与类别范围。`api.py` 管理 HTTP、原始 Decimal 解析、大小限制、签名、防重放、限流、统一错误和指标。`contract.py` 提供面向集成方的 OpenAPI 契约。所有业务表都以 tenant_id 隔离，跨租户引用由复合外键阻止。

## 原子发号顺序

1. 鉴权并验证 Tenant、Caller、类别范围，按 tenant + caller + category 限流。签名 nonce 和限流预算单独提交，不代表发号成功。
2. 在正式事务中锁 tenant + caller + Idempotency-Key，比较完整请求语义 Hash。
3. 锁 tenant + caller + category + sourceRecordKey，查永久来源绑定。已绑定请求以最初发布快照重新判定身份；不变即返回原结果，变化返回冲突。
4. 新来源获得 Category 治理共享锁，选择当前唯一 Published Release，加载永久配置快照。
5. 标准化 → 派生 → 校验 → Identity；锁 tenant + category + identityHash。
6. 在 Hash 桶中比较完整 canonical，按当前类别策略复用或返回人工确认冲突。
7. 不存在时使用事务性 UPSERT 获取 Code Rule Version 的 Sequence，计算号码，再锁 tenant + materialNo 并检查唯一性。
8. 在同一个数据库事务中写 Ledger、Source Binding、Idempotency Record 和审计，提交后返回 ISSUED / REUSED。

Idempotency、来源键、完整身份和号码分别有独立的一致性保证。来源绑定与 Assignment 分离，一个 Assignment 可以绑定多个 Caller 的多条记录。故障注入仅存在于内部测试调用参数，没有开放 HTTP 故障入口。

数据库唯一约束涵盖 tenant + materialNo、tenant + category + hash + collisionIndex、tenant + caller + category + sourceKey、tenant + caller + idempotencyKey。长 canonical 不直接作为受 PostgreSQL B-tree 大小限制的索引键；触发器验证真实 SHA-256 并通过桶锁和完整 canonical 比较保证身份唯一。对不同 canonical 的哈希碰撞保留独立 collisionIndex，绝不只凭 Hash 复用。

Ledger、Binding、成功幂等响应和 Audit 为 append-only，数据库触发器阻止 UPDATE / DELETE。Sequence 不可回退、修改作用域或删除。提交前进程崩溃不会留下部分号码事实，已提交号码不会因调用方重试或业务保存失败而回收。

## 版本治理

Category Version、Schema、Input Profile、Validation、Derivation、Identity、Code Rule 与字典的每个版本各有自己的 UUID 和语义定义。发布包 refs 显式引用所有依赖；不在普通参数中即时改变编码语义。

提交执行完整依赖检查、DAG 检查、回归样本、Identity/号码冲突检查、字符/长度检查与上一当前版本 Diff，然后冻结依赖。审批重新确认依赖没变且样本通过，要求审批人与提交人不同。Category 治理独占锁阻止正式发号跨越发布切换；新包发布和旧包退役原子完成，部分唯一索引保证每类别仅一个当前 Published Release。

解释读取 Ledger 的输入/映射/标准化/派生/校验/Segment 快照，以及对应的 Release 配置快照；不按最新字典或规则重算历史号码。

## 性能边界

连接池最大24，每个 HTTP 请求在工作线程执行同步 SQL；限流和签名使用同一连接单独提交，避免并发时嵌套获取第二个连接导致连接池耗尽。Assignment 查询分页，按 tenant/category/time、号码、Identity hash、来源键、来源 Assignment 和 requestId 建索引。数据库指标持久化，监控不依赖进程内缓存。

平台没有 JDBC ERP 连接、表结构解析、SQL 编辑器、Dataset、扫描、写回 Worker、Outbox 或对账流程。调用方适配与最终业务保存由调用方示例说明，但完全位于 MDM 事务之外。P1 的批量导入、异步 API、历史号码解析、复杂对象 Schema 和完整多域 MDM 没有进入本项目。
