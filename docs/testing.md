# V3 验证方式

运行 `scripts/test.sh all` 串行执行后端、HTTP、前端/浏览器、重启、两库备份恢复。各模块刚实现时也分别执行相关测试。完整证据及未测项见 acceptance-report.md / requirements-matrix.md。

- Java：真实 mdm_test PostgreSQL。SourceMetadataTest（DDL/只读/隔离）、DatasetEngineTest（Join/Grain/空值/类型）、NumberingTest（原子提交、100 并发、完整 Identity、租户、版本/搜索）、WritebackTest（业务失败、5xx、丢响应、未知结果、值保护、扫描及适配器重建）。WritebackTest 继承 NumberingTest，因此报告的执行次数含重复回归。BusinessTest 为历史内部回归，不冒充 V3 覆盖。
- HTTP：tests/v3_acceptance.py，独立 ERP 事实数据与明确 Golden；每组写用例编号、状态、证据和耗时。所有行业规则位于测试元数据。每轮新前缀，保留数据供浏览器/人工复核。
- 浏览器：frontend/e2e/v3.spec.ts 的四个真实 Chromium 流程。旧 V2 页面测试保留历史，当前 Playwright testMatch 明确选择 V3 流程。
- 运维：v3_restart.py 检查 PENDING 扫描/回写及原号在进程重启后恢复；v3_backup_verify.py 恢复两库至新库并逐表比较。

证据路径：`.runtime/v3-build.log`、`backend/target/surefire-reports`、`.runtime/v3-acceptance-results.json`、`.runtime/v3-acceptance-fixtures.json`、`.runtime/browser-results.json`、`.runtime/v3-restart-evidence.json`、`.runtime/v3-backup-evidence.json`。失败保留截图/trace 于 `.runtime/browser-artifacts`。脚本不吞掉退出状态。

故障测试会故意终止自身数据库连接、注入条件化 Outbox 插入异常，出现 FATAL/堆栈属预期；通过标准是事务无半账、后续恢复成功及 JUnit 零失败。不可仅凭日志末尾或零测试判定通过。

P95 为本机抽样，100 并发和示例料号不替代真实 ERP UAT、企业公式或 PERF-009 的 5 万条容量验收。云访问方式、生产身份和旧浏览器兼容另行验证。
