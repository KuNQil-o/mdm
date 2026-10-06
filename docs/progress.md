# 当前进度（2026-10-06）

开发验收 MVP 交付完成，M0—M4已完成。云工作区与原PostgreSQL卷保留，未重建仓库或清空数据；源码提交状态以Git记录为准；数据库、依赖及测试产物不纳入源码提交。

Java21/Spring Boot3.4.3、Vue3、PostgreSQL16.6；迁移V1—V7均成功。后台任务和文件持久化，行业由配置表达。运行JAR按内容哈希保存，重新构建不覆盖运行归档。

最近相关检查：JUnit真实PostgreSQL10项通过（含100并发正式占号）；独立ERP HTTP7项通过；最终运行验证13项通过27.169秒；最终Chromium3流程通过38.1秒，覆盖条件/精确小数、完整核心业务、导入错误/重复/过期版本及修正继续。最终npm ci/类型检查/生产构建/后端打包成功。详细时间、命令、范围与日志在testing.md。

最后修复：导入UPDATE必须与对象类别及绑定Schema版本一致，增加两条错误路径回归；页面明确选择导入类别/Schema，类别变化清理文件/映射上下文。浏览器完整导入已复验通过。此前补齐后台导出、身份版本重绑/替代关系、NetworkNT结构校验、固定段未解析区间、类别归属策略、错误报告字段与严格UTF8。没有待关闭的本地关键流程失败。

当前V7备份 .runtime/backups/20261006T064900Z，独立恢复库mdm_restore_verify_20261006_064900；31表行数/内容摘要一致、备份SHA-256及ERP SQLite完整性通过，9.624秒。主库未替换。恢复后后端8080、Vue5173、ERP9090实际响应正常；两个租户ACTIVE，行业Schema已发布，TENANT-A示例GC-7628-1270-HONGHE可查询。重复seed创建租户0，物料/Schema/事件计数不变。

README、接口、运行手册、完整FR/BR/AC/NFR矩阵、测试记录与已知边界齐备，原始PRD与上传原件SHA-256一致。真实ERP UAT、生产负载/可用性/RPO/RTO与企业Chrome/Edge基线仍未验证，见known-issues.md；不宣称完整生产上线门槛通过。

用户已发布环境，发布后当前工作区重连验证完成（2026-10-06 06:59 UTC）：代码/依赖/数据库卷保留，31表与发布前备份证据的行数及摘要全部一致；浏览器重新登录并查看GC-7628-1270-HONGHE及3条历史，无页面错误。停止数据库后重新启动也已验证三个服务健康。首次恢复暴露数据库尚在启动时后端提前连接失败，已在start.sh/test-start.sh/test.sh中加入Compose健康等待；shell语法检查通过，实际数据库冷启动验证通过，未改业务源码、未清库或重跑全部业务测试。

cloud-environment-onboarding:setup的启动说明已增补数据库健康等待并保存新草稿，安装脚本/网络/凭证保留。用户已再次发布：新的已发布版本包含健康等待说明，三个启动脚本的修复也随工作区保留。第二次发布后直接start成功，无需临时补救；31表摘要、42条物料及浏览器登录/示例/3条历史再次核验通过，证据.runtime/post-publish-2-*.json与start.log。本次只记录验证结果，没有新增配置草稿，无需再次发布；独立新任务恢复仍未执行。启动说明继续覆盖已有仓库/no worktree、保留数据、工具激活、分离测试与备份恢复。

恢复入口：先读本文件和README，检查git与.runtime日志/pid，scripts/start.sh复用原库；后端源码变化后成功打包，再scripts/stop.sh和scripts/start.sh。scripts/test-start.sh使用独立8081/9091与mdm_test，不清库。HTTP与operations不能并发，后者重启专用测试实例；Playwright串行、目录frontend，测试期间不要改前端或npm ci。不要从main重新拉取覆盖本地工程，不执行docker compose down -v。
