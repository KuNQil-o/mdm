# V3 实施决策

- PRD V3.0 为唯一需求基线。原文与测试用例保留，V2 文档放入 archive-v2 作历史资料。
- 模块化单体：Java21 / Spring Boot3.4.3 / Vue3 / PostgreSQL16.6。没有 Redis/Kafka 必需依赖。
- ERP SoR。MDM 的 SourceSnapshot 明确为 NON_AUTHORITATIVE_COPY，Ledger 是号码发放证据，二者不替代 ERP 物料事实。
- Dataset Root 稳定主键/唯一键；默认一行一物料。Join 只允许 1:1/N:1 等值关联，重复不取第一条；缺失必须配置 ERROR/NULL/DEFAULT。
- 身份 canonical 保留完整类型化值及字段顺序；SHA256 用作定位，数据库以完整 canonical 比较分配碰撞槽。默认同类别一 Identity 只对应一来源记录。
- 实际执行顺序按 PRD7.4：读取→Mapping→标准化/派生/校验→Identity→Code→Ledger；Preview 不消耗序列。
- 发布固定对象与配置依赖；独立 Golden 和 ERP 契约通过后双人审批。历史账本永远解释当时版本，新默认配置只服务新物料。
- 默认 existingNoPolicy IGNORE；可配置 VERIFY / IMPORT_AS_LEGACY / ERROR。禁止覆盖 ERP 其他非空号，禁止自动改既有正式号。
- 关联参考表变更选择 RESCAN_UNISSUED，避免仅依赖 Root 时间漏掉未发号候选。ERP API 推送只接受绑定来源身份。
- 编号、流水、Ledger、Task、Outbox、操作日志原子提交；外部 HTTP 在编号事务提交后执行。条件写入+稳定幂等键+当前值查询处理不确定结果。
- ERP 业务 Reference 来源 Dataset 显式声明并随发布固定。纯 MDM 配置字典需显式 METADATA_CATALOG 标记；不从 MDM 目录冒充 ERP 业务事实。
- 部署控制网络目标与环境凭据绑定，业务设计员不能扩大网络/秘密读取权限。开发 ERP 仅测试，不是企业 UAT。
- 生产指标、真实 ERP 接入、正式业务公式与容量验收列为后续验证；不做 IFS/KRS 迁移或生产切换。
