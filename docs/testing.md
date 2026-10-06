# V3 验证方式

运行 `scripts/test.sh all` 串行执行后端、HTTP、前端/浏览器、重启、两库备份恢复。各模块刚实现时也分别执行相关测试。完整证据及未测项见 acceptance-report.md / requirements-matrix.md。

- Java：真实 mdm_test PostgreSQL。SourceMetadataTest（DDL/只读/隔离）、DatasetEngineTest（Join/Grain/空值/类型）、NumberingTest（原子提交、100 并发、完整 Identity、租户、版本/搜索）、WritebackTest（业务失败、5xx、丢响应、未知结果、值保护、扫描及适配器重建）。WritebackTest 继承 NumberingTest，因此报告的执行次数含重复回归。BusinessTest 为历史内部回归，不冒充 V3 覆盖。
- HTTP：tests/v3_acceptance.py，独立 ERP 事实数据与明确 Golden；每组写用例编号、状态、证据和耗时。所有行业规则位于测试元数据。每轮新前缀，保留数据供浏览器/人工复核。
- ERP 主动申请：`scripts/test.sh erp` 执行 tests/erp_workflow.py 的 12 项实际 ERP → MDM → ERP 验证，覆盖仅保存、来源身份请求、多表属性及单位、12 次同记录并发、Identity 冲突、缺失数据修复、已发规格保护、备注变更、ERP 值保护、参考表变更、响应丢失后的持久幂等恢复和回写查询确认。
- 浏览器：frontend/e2e/v3.spec.ts 的四个 MDM 流程和 frontend/e2e/erp.spec.ts 的三个 ERP 流程，使用真实 Chromium。ERP 覆盖保存并申请、刷新持久化、重复申请、身份重复提示及修正恢复、仅保存后申请和凭据不下发。旧 V2 页面测试保留历史，当前 Playwright testMatch 选择上述两份文件。
- 运维：v3_restart.py 检查 PENDING 扫描/回写及原号在进程重启后恢复；v3_backup_verify.py 恢复两库至新库并逐表比较。

证据路径：`.runtime/v3-build.log`、`backend/target/surefire-reports`、`.runtime/v3-acceptance-results.json`、`.runtime/v3-acceptance-fixtures.json`、`.runtime/browser-results.json`、`.runtime/v3-restart-evidence.json`、`.runtime/v3-backup-evidence.json`。失败保留截图/trace 于 `.runtime/browser-artifacts`。脚本不吞掉退出状态。

ERP 流程证据另存 `.runtime/erp-workflow-results.json`，浏览器页面截图为 `.runtime/erp-page.png`。ERP 来源记录、集成绑定和请求状态均在独立 ERP PostgreSQL 中持久化。

故障测试会故意终止自身数据库连接、注入条件化 Outbox 插入异常，出现 FATAL/堆栈属预期；通过标准是事务无半账、后续恢复成功及 JUnit 零失败。不可仅凭日志末尾或零测试判定通过。

P95 为本机抽样，100 并发和示例料号不替代真实 ERP UAT、企业公式或 PERF-009 的 5 万条容量验收。云访问方式、生产身份和旧浏览器兼容另行验证。
