# V3 项目状态 · 2026-10-06

工作区 `/workspace/mdm`，起点 main d737d44。PRD V3.0 与测试用例 V1.0 全文读取并原样保存，V2文档归档。没有AGENTS.md，未做IFS/KRS迁移、生产切换或清库。

M0—M6本地开发与验证已完成，覆盖来源结构/Join/Grain/Mapping/Schema/Identity/AST/规则版本/双人发布/原子发号/台账/扫描/回写/异常/对账/指标/中文页面。玻璃布7关联表与ERP View、各类别配置、film/board XOR均验证。来源对象改变生成依赖告警；旧版本解释不受新默认规则影响。

最终证据：测试文档P0 160/160，P1 4/5；HTTP101组0失败；Java49次执行0失败（含WritebackTest继承的11项回归、历史BusinessTest10项）；浏览器4/4；ERP自动轮询无需人工发号；PENDING任务重启恢复；两库61表恢复摘要一致。预览/发号本机30样本P95为26.33/34.43ms。覆盖矩阵及可读摘要在 requirements-matrix.md / acceptance-report.md / acceptance-evidence.json。

恢复工作时先读README、known-issues.md和operations.md，保留数据库/卷、已有文件与.runtime证据。进程不随快照保证保留，执行scripts/start.sh；更新后端先构建再stop/start。测试命令scripts/test.sh，后端/http/browser/operations可分模块；完整all必须串行。报告生成器tests/v3_report.py仅从完成的运行证据更新矩阵。

仍未完成：真实ERP写回UAT、正式料号/Identity签收、PERF-009五万条容量、生产SSO/网络/秘密/监控/RPO-RTO及企业浏览器基线。JDBC当前PostgreSQL，FILE读取适配器未实现；高级配置使用JSON编辑器。没有宣称生产就绪。
