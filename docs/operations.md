# V4 运行与安全

## 环境与启动

默认 `APP_ENV=development`，仅用于本地演示；自动种子和 `/dev/login` 在生产模式关闭。生产设置：

- `APP_ENV=production`。
- `DATABASE_URL`：专用 PostgreSQL 连接，由部署环境注入；不要写入 Git 或浏览器。
- `PUBLIC_ORIGIN`：企业 HTTPS 工作台 origin。
- `STATIC_DIR`（可选）：Vue build 目录；默认为 frontend/dist，可由 FastAPI 同源服务。
- `PORT`（本地脚本可选）：后端端口；前端开发代理固定 8080，需要同时调整 vite.config.ts。

先运行 `DATABASE_URL=... backend/.venv/bin/python scripts/bootstrap.py`，交互式创建初始租户和平台管理员，密码至少12字符，PBKDF2-SHA256 310000次，不输出密码。引导只接受空用户库。

生产以可信反向代理终止 TLS，保留 Host / Origin。Uvicorn 只信任明确的代理 IP，例如 `--proxy-headers --forwarded-allow-ips=<实际可信代理IP>`；禁止为了绕过 HTTPS 判断而信任任意来源。数据库和应用端口限制在企业授权网络，备份与随机凭据文件应加密保存并限制权限。

API Key 随机生成，仅保存哈希。轮换后旧 Key 立即失效；普通 GET 不返回 Key / hash。正式发号强制 Caller 与 payload 身份相符、类别范围、租户 ACTIVE、Caller ACTIVE。用户权限每次请求从有效成员与角色重新计算。平台会话防止跨站写入，生产 cookie 带 Secure、HttpOnly、SameSite=Strict。

生产 Caller 请求必须签名并通过 nonce 防重放，详见 api.md。按 tenant + caller + category 的分钟配额保存在数据库，支持多工作进程；Caller rateLimit 可由集成管理员按 If-Match 修改。登录亦有限流。

## 事务与持久化

成功发号在一个 PostgreSQL 事务内提交 Sequence、Assignment、Source Binding、Idempotency Record 和发号审计；不会调用外部系统。租户、来源、Identity、号码和发布切换均使用相应锁与数据库约束。查询和解释读取不可变快照，不调用调用方系统。

流水和账本在数据库层禁止修改/删除。事务尚未提交就异常退出时全部回滚；这时没有正式号码发放。提交后客户端响应丢失，重试返回原号码；调用方业务回滚不会撤销正式发号事实。

新 Release 的发布、上一 Release 的退役、配置的永久冻结原子完成。事务期间正式发号与发布切换通过类别共享/独占锁协调。所有历史配置快照永久保留；不能通过修改字典、规则或当前运行参数静默改变历史号码。

## 监控、审计与保留

`/monitoring` 包含24小时请求量、正式发号/预览量、成功率、ISSUED/REUSED比例、错误码分布、P50/P95/P99、Sequence P95、锁等待、当前发布包和 Caller 最近调用情况。指标在数据库持久保存，重启后保留。锁等待是共享数据库范围。

审计包括对象类型/ID、配置/Release版本、执行成员或 Caller、UTC 时间、requestId、操作原因与 Diff 内容。错误日志只记路径、错误类别及请求 ID，不打印 SQL 参数、HTTP 原始 payload、Token 或密码。普通输入快照只保存参与发号的属性，不镜像调用方完整业务记录；调用方必须在适配层移除无需发号的敏感字段。

账本、来源绑定、幂等记录、已发布配置和操作审计均长期保留。`scripts/cleanup.py` 只清理过期会话、超过一天的 nonce / 限流桶，完全不清理发号事实、计数器、发布快照或审计。生产可定时执行。企业三年审计、五年解释保留目标由归档与存储策略保证，默认不自动删除。

## 备份与恢复

```bash
bash scripts/backup.sh
backend/.venv/bin/python scripts/verify_restore.py
backend/.venv/bin/python scripts/verify_restart.py
```

备份脚本输出 PG 自定义格式，文件权限由 umask=077 限制。恢复验证导出 repeatable-read 一致性快照，在 **新数据库** 恢复，不覆盖源库；比较账本、序列、绑定、幂等、配置、租户、权限和审计的全部行哈希，再检查孤儿与重复。备份含凭据哈希与业务属性，生产应使用企业备份加密/访问管理。

脚本默认连接本地 Compose 数据库。自定义环境通过 RESTORE_SOURCE_DB、RESTORE_SERVER_URL 配置验证；远端 pg_dump/pg_restore 应由企业使用对应数据库工具，不把开发容器当生产备份平台。

RPO≤15分钟可通过企业定时备份/连续 WAL 归档实现，RTO≤4小时须在真实数据量与目标基础设施演练。开发演练不能证明企业灾备 SLA。

## 上线前外部验收

本项目未部署到生产。实际业务系统完整 UAT、企业正式 Identity/编码规则签收、Chrome/Edge 企业版本、反向代理证书、秘密托管、备份加密、告警值班、99.9% 可用性及生产 RPO/RTO 需要目标环境验收。开发调用方示例、系统 Chromium、容量合成数据和本机吞吐测试提供开发证据，不能替代这些外部验收。

请求关联：JSON对象响应与响应头同时提供 requestId / traceId（X-Request-ID / X-Trace-ID）。traceId 为有效 requestId 的 SHA-256 前32个十六进制字符，作为单个请求的追踪根；同一请求贯穿事务审计和指标，永久账本保留 issueRequestId / issueTraceId。重试有自身追踪根，并通过 assignmentId 关联原发号，不实现跨服务 W3C span 传播。
