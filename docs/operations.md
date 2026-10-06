# 开发运行手册

服务/卷不依赖浏览器当前租户。每次云工作区恢复先读progress.md、核对Git和.runtime日志，用start.sh复用已有库。不要重新创建仓库或执行 `docker compose down -v`。

## 备份恢复

1. `cd /workspace/mdm` 进入项目。
2. `scripts/backup.sh` 使用pg_dump自包含格式备份当前开发库（包括JSONB、文件BYTEA、版本、Outbox/Inbox、身份与幂等记录），同时用SQLite在线backup备份ERP模拟记录；输出保存在.runtime/backups/UTC时间/。`BACKUP_DATABASE`可选择专用测试库。
3. `sha256sum -c .runtime/backups/时间/SHA256SUMS` 校验备份内容摘要。
4. `scripts/verify-restore.sh /workspace/mdm/.runtime/backups/时间/mdm.dump` 创建新的mdm_restore_时间数据库并恢复，检查重要表与迁移版本；不覆盖原库。失败保留库供诊断。
5. `scripts/stop.sh` 停止本项目开发应用写入，数据库保留。`backup.sh` 本身支持在线一致性快照，但下述比较脚本需要停写，避免逐表取摘要与备份之间发生正常业务变化。
6. `python tests/backup_verify.py` 执行更严格的实际恢复演练：保留新备份，恢复独立库，对比31张表的行数和摘要，涵盖物料版本、历史快照、事件、身份、任务、文件、幂等及迁移记录；ERP SQLite完整性检查。MD5仅用于行内容相等比较，备份文件完整性使用SHA-256。
7. `scripts/start.sh` 重新启动开发应用并检查健康响应，继续使用原数据库；演练不会切换到恢复库。

外部数据库用平台管理的pg_dump/pg_restore与安全凭证配置执行同样过程。生产恢复前停写、确认备份/WAL时间点、外部已处理事件与幂等保留范围、再切换DATABASE_URL。不能用上述开发脚本覆盖正式数据库。上线需托管备份/WAL归档、监控与真实恢复演练，不把本机耗时当生产RPO/RTO。

## 暂停与恢复

租户admin/platform admin在平台页面填写原因并暂停：后续写入与后台领取停止，已完成结果保留；正在事务中处理的一行或已发出的HTTP请求允许完成，暂停不撤销目标已经收到的调用。恢复继续未成功行/投递。

导入中心可暂停/恢复运行任务，错误或失败行修正后重新预检。SUCCESS行的UUID/申请ID保持原值。自动恢复依赖数据库任务状态及领取锁，不依赖进程内队列。应用重启使用stop/start；文件记录仍归原租户。失败任务先查看reason，再恢复或修正；不要手工重放全部源文件。

租户归档要求先取消/处理草稿申请、导入、导出与未确认投递；允许已明确失败投递保留审计。重开先SUSPENDED，再resume。归档不删除历史或释放正式料号。

## 对账与补发

查看物料详情→关联申请/历史/事件/目标结果；集成中心→目标系统→实际对账。MATCH表示目标版本与映射关键字段吻合，DIFFERENT/UNCONFIRMED/MISSING_EXTERNAL_ID需逐项核对。每日02:00UTC用config.integrationUserId（缺省inboundUserId）的当前登记成员执行；未登记执行人跳过，不以匿名管理员运行。

FAILED可填写修复原因补发，新revision记录原parentId和映射版本。RESULT_UNKNOWN必须先目标查询/人工确认“未创建”，再选confirmedNotCreated补发；已创建则记录externalId人工确认，禁止盲重发。WAIT_CONFIRMATION文件任务下载批次交目标，收到逐事件回执后登记success+externalId+reason。HTTP200也要读目标业务响应。一个目标失败不回滚本地正式数据。

真实ERP启用前需提供测试地址/错误契约测试、认证与身份查询方法、字段单位精度、成功字段、幂等与事件前序规则、业务人员UAT。连接凭证由环境/适配器提供，避免URL内嵌。本MVP可用通用接口扩展认证适配，但当前只对独立模拟器执行联调。

## 文件与投影

租户管理员执行POST `/api/v1/files/clean`（带session、X-Tenant-Code、Idempotency-Key）按retentionDays清理已到期且不被未结束导入引用的文件内容，保留文件/任务摘要。清理后下载返回410；先备份再按真实保留政策操作。演示环境没有定时删除业务数据。

投影损坏时，管理员POST `/projections/rebuild`；当前权威物料逐个锁定，使用绑定Schema重建，发布版本和Outbox不变。没有Redis缓存依赖，缓存故障演练不适用。运行指标来自实际采集，最近最多2000条当前进程样本；进程重启后重新采样，不能当月度可用性统计。
