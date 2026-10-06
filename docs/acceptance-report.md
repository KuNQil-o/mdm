# V3 开发验收报告

生成时间：2026-10-06T10:33:01.994667+00:00。唯一基线 PRD V3.0，验收文档 V1.0。代码与规则未为测试削弱约束。

## 已完成

来源系统/DDL/JDBC/API Object 与结构版本、Diff/告警/影响报告；多层等值 Join/Root/Grain；Mapping/单位/Schema/受限 AST；ERP Reference 与跨 Dataset 投影；完整 canonical Identity；独立 Golden+ERP契约测试和双人发布；历史版本解释及回退；预览与原子流水/不可变 Ledger/Task/Outbox；持久扫描、增量游标和定时轮询；幂等、100并发防重；查询确认/值保护/重试/未知结果/新适配器任务链；异常工作台、对账、指标及中文 V3 管理台。

玻璃布从主表加7个关联表展开，另验证ERP View等价。铜箔/化学原料/半固化片/胶片/基板/成胶/一般物料/耗材使用同一配置引擎。胶片和基板四种尺寸/裁切组合均验证，双选及都不选拒绝。BOM、库存、工艺兼容数据只在 ERP 中保存。未迁移 IFS/KRS，未做生产切换。

## 测试结果

|检查|结果|
|---|---|
|测试文档 P0|160/160 开发环境通过|
|测试文档 P1|4/5 通过；PERF-009 未执行|
|HTTP 验收|101组，覆盖156个文档编号，0失败|
|JUnit|49次执行，0失败/错误/跳过；各套件 {'BusinessTest': 10, 'NumberingTest': 11, 'DatasetEngineTest': 4, 'WritebackTest': 21, 'SourceMetadataTest': 3}|
|Chromium E2E|4/4通过：玻璃布/铜箔/胶片/基板|
|前端|vue-tsc与Vite生产构建通过|
|ERP自动轮询|仅在ERP建记录，Worker发号并确认回写，无人工物料审批/发号请求|
|重启|PENDING扫描及回写恢复，游标/原台账/正式号保留|
|备份恢复|平台46表+独立ERP15表，共61表全部行数/摘要一致；恢复到新库|

WritebackTest 包含继承的11项编号回归，不能将重复执行当成独立新增测试。V3独立测试28项，历史BusinessTest10项。预期DB故障日志对应主动注入，JUnit最终均通过。

30次本机抽样：预览P95 **26.33 ms**，发号P95 **34.43 ms**。100不同Identity并发发号无重号；100不同ERP键同Identity仅一条台账。上述不构成生产SLA证明。

完整逐用例映射见 [requirements-matrix.md](requirements-matrix.md)，可读机器证据见 [acceptance-evidence.json](acceptance-evidence.json)。详细原始运行证据留在 `.runtime` / Surefire；文档只提交摘要。

## 未完成与风险

真实ERP/接口平台写回UAT、企业正式编码公式及Identity签收、PERF-009 5万条容量验证、生产SSO/凭据/网络/监控/RPO/RTO和企业浏览器基线尚未完成。JDBC当前PostgreSQL，FILE读取适配器未实现；复杂配置仍用JSON编辑器。详见 [known-issues.md](known-issues.md)。PRD18.4生产发布门槛因此未宣称满足。

## 运行

`cd /workspace/mdm` 后执行 `scripts/install.sh`、`scripts/start.sh`、`scripts/seed.sh`。`scripts/test.sh http`创建可复核V3配置/ERP数据；`scripts/test.sh all`重现完整验证。前端5173、后端8080、开发ERP9092；云环境使用产品端口访问方式。editor设计/集成，reviewer审批发布，自审禁止。详细配置与暂停/恢复见 [README](../README.md) / [operations.md](operations.md)。
