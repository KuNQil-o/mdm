# V4.0 P0 实现与开发验收

依据随项目保存的 [PRD V4.0](通用物料身份与料号治理平台_PRD_V4.0.md)，本分支从零实现独立平台。附带文档用于需求和验收基线；交付范围遵循用户要求：新项目、P0 功能、推送 GitHub v4.0 分支。

2026-10-07 完成开发环境验证，机器可读结果见 [acceptance-evidence.json](acceptance-evidence.json)。以下“通过”指所列自动化场景，未代表完成 PRD 第18章全部企业上线门槛。

## P0 功能覆盖

|PRD|实现|主要代码 / 验证|
|---|---|---|
|FR-01 Caller 注册|系统类型、环境、状态、归属、类别范围、频率配额、一次性 Key 与轮换|security.py / api.py；Caller 生命周期 HTTP 和浏览器测试|
|FR-02 来源键|正式请求必填 sourceRecordKey，绑定 tenant/caller/category|service.py；来源重复及并发测试|
|FR-03 Input Profile|字段映射、常量、字典、大小写、前后缀；版本化发布|engine.py；映射与受保护派生字段测试|
|FR-04 Category|独立逻辑类别、版本、运行可用状态|service.py；类别暂停与租户测试|
|FR-05 Schema|STRING / INTEGER / DECIMAL / BOOLEAN / DATE / ENUM / REFERENCE|engine.py；全部属性类型与引用测试|
|FR-06 标准化|trim、大小写、空值、默认值、精确小数、单位、稳定字典代码|engine.py；标准化与字典快照测试|
|FR-07 Schema 不可变|发布依赖冻结，改变配置必须新版本与新 Release|迁移002 / service.py；数据库及 HTTP 不可变测试|
|FR-08 校验|结构化受控表达式，ERROR / WARNING、字段路径和错误详情|engine.py；布尔 AST、规则详情测试|
|FR-09 派生|受控确定性运算与依赖 DAG，拒绝循环/脚本/输入覆盖|engine.py；DAG 和派生测试|
|FR-10 Identity|有序属性 canonical、SHA-256、完整 canonical 对比及碰撞索引|engine.py / service.py；长身份与分隔符安全测试|
|FR-11 相同身份|REUSE_EXISTING 建立新来源绑定，REVIEW_ON_DUPLICATE 阻止自动复用|service.py；跨系统复用与人工审核策略测试|
|FR-12 来源变更|按原发布快照计算，非身份变化复用、身份变化冲突|service.py；AC-01 / AC-02|
|FR-13 编码段|结构化常量、属性、映射、格式、单位、流水、日期周期等|engine.py；类型、填充、格式、空值测试|
|FR-14 Sequence|租户/类别/规则版本/流水名/周期作用域，事务原子计数|service.py / SQL约束；100并发、回滚和重启测试|
|FR-15 Preview|API 和页面展示标准化、校验、派生、身份及编码解释；流水占位|api.py / App.vue；100次预览计数器和台账不变|
|FR-16 原子发号|身份检查、流水、账本、来源、幂等与审计同一数据库事务|service.py；进程硬退出及号码冲突回滚测试|
|FR-17 Assignment Ledger|永久发号事实、身份/输入/规则/版本/解释快照|迁移001 / service.py；查询、历史解释与不可变测试|
|FR-18 Source Binding|独立来源绑定，多来源共享 Assignment|service.py；复用、来源查询与孤儿检查|
|FR-19 双重幂等|Idempotency-Key 请求一致性与 Source Record 唯一性|service.py；响应丢失、冲突、100并发重复请求|
|FR-20 Release Package|聚合所有配置版本、依赖快照、样本和版本 Diff|service.py；新版本及发布差异测试|
|FR-21 发布前验证|依赖检查、回归样本、身份/号码碰撞、双人审批、REVIEW冻结|service.py / security.py；失败样本、审批及浏览器测试|
|FR-22 当前发布|每类别唯一 PUBLISHED；正式号不接受任意历史版本|service.py / SQL唯一约束；发布切换及历史解释测试|
|FR-23 输入最小化|只保留允许且用于发号解释的属性；拒绝未知字段|engine.py；备注脱离快照与日志脱敏测试|
|FR-24 多租户|所有配置和事实带 tenantId；用户成员、角色和类别范围校验|security.py / api.py；同号码跨租户及权限隔离测试|
|FR-25 审计|请求ID、执行身份、操作、对象、版本、时间、原因及 Diff|迁移006 / service.py；审计范围与凭据脱敏测试|

文件路径相对于 [backend/app](../backend/app)、[backend/migrations](../backend/migrations)、[前端](../frontend/src/App.vue)。HTTP 契约、错误与配置结构见 [api.md](api.md)。查询覆盖料号、来源、身份、类别、时间和分页；管理界面提供配置、审批、账本解释、审计、指标、租户与权限。

## AC-01 至 AC-14

|PRD验收项|自动化证据（tests/test_p0.py）|结果|
|---|---|---|
|AC-01 非 Identity 属性变化|test_ac01_normalization_and_non_identity_change|通过|
|AC-02 Identity 属性变化|同上，明确断言 SOURCE_IDENTITY_CHANGED_AFTER_ISSUE|通过|
|AC-03 网络超时重试|test_ac02_response_lost_idempotent_retry；另有持久化 Caller 重试与应用重启验证|通过|
|AC-04 不同幂等键重复|test_ac04_source_binding_new_keys，100个同来源请求|通过|
|AC-05 同 Identity 跨来源|test_ac05_cross_system_identity_reuse_and_review_policy|通过|
|AC-06 并发唯一性|test_ac06_100_concurrent_same_identity；同来源100并发|通过|
|AC-07 号码唯一|test_ac07_100_distinct_identity_unique_numbers；编码冲突完整回滚|通过|
|AC-08 Preview 不占号|test_ac08_100_previews_no_sequence_or_assignment|通过|
|AC-09 历史可解释|test_ac09_query_explanation_history；字典版本快照|通过|
|AC-10 发布不可变|test_ac10_published_immutable_new_version_diff；SQL触发器检查|通过|
|AC-11 行业无关|test_ac11_three_industries_configuration_only，玻璃布/轴承/电阻共用核心|通过|
|AC-12 ERP 无关|test_ac12_caller_contract_auth_lifecycle_and_isolation；独立Caller SQLite闭环|通过|
|AC-13 原子事务恢复|test_ac13_process_death_after_sequence_atomic_recovery，实际子进程 os._exit(73)|通过|
|AC-14 配置审批|test_ac14_approval_separation_draft_forbidden；双用户浏览器发布|通过|

测试函数早期编号与 PRD 的 AC-02/03 不完全一致，上表按行为映射。响应丢失测试先让服务提交，再丢弃首次响应、用同一键重试；没有模拟企业网关所有故障形式。

## 实测结果与条件

- 后端 **33/33**：真实 PostgreSQL、独立测试库、HTTP 服务、100并发、事务约束及进程故障；不是纯内存 mock。
- 浏览器 **4/4**：系统 Chromium，工作台/三类别预览/解释、Caller生命周期、配置回归及双人发布、只读角色/租户/移动布局。Vue TypeScript 检查和生产构建通过。
- 容量：独立库 **1,000,000** 条合成 Assignment、**503** 个 Category；数据库约束启用，索引查询28.69ms、复用5.54ms、新发号4.14ms。批量SQL装载用于容量验证，不是百万次HTTP吞吐验证，也不代表企业数据分布。
- 基线吞吐：按 **20次/秒** 调度 **200** 次正式请求，200成功且号码唯一；正式号 P95 **15.60ms**，Preview P95 **9.75ms**。仅约10秒的本机样本，不承诺长期稳态或生产 SLA。
- 备份恢复：一致性快照恢复到新库，11个权威表逐行指纹相同；孤儿来源绑定、重复身份、重复号码均为0。
- 应用重启：原 Assignment 和 materialNo 保持不变，原幂等键重试返回 REUSED。
- npm audit / pip-audit：扫描时均为 **0** 个已知依赖漏洞；Python静态致命错误检查通过。不是企业渗透测试或安全签收。

复现命令在 [README](../README.md)。原始 XML、浏览器 JSON、截图与本地凭据分开保存在不提交的 `.runtime/`；仓库证据文件只保存汇总，不包含 Key、cookie、密码或业务输入快照。

## 待目标环境完成的验收

PRD 第18章要求至少一个**真实业务系统**完成 UAT；当前只验证独立开发 Caller 的“创建业务记录 → HTTP → 发号 → 自己保存号码”闭环。尚未获得企业系统、正式规则或部署环境，不能声称完成该门槛。

企业安全基线签收、真实 Chrome/Edge 版本、TLS及秘密托管、生产长期20次/秒、月99.9%可用性、RPO≤15分钟 / RTO≤4小时需由目标环境联调与演练确认。交付提供可运行代码、测试和运维入口；本次没有部署到生产。
