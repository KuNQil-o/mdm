# V3 运行与恢复

`start.sh` 等待 PostgreSQL 健康、建立开发 ERP 只读账号/独立库（存在则保留）、启动 9092 ERP/8080 后端/5173 前端并检查响应。`stop.sh` 只停止脚本登记的项目进程，不停数据库，不删除卷。修改后端后先打包，再 stop/start 加载内容哈希 JAR。

暂停来源或租户会阻止新的扫描、发号和回写；既有账本、游标、任务保留。SCAN_WORKERS / WRITEBACK_WORKERS 仅控制当前实例；跨实例暂停应使用来源/租户状态。管理员使用运行指标、任务/解释链及 `.runtime` 日志定位错误。不要用 PID 或端口代替实际业务验证。

发号失败：修复 ERP 数据或发布新版本，重试处理任务。已发身份/编码属性改变进入异常，应在 ERP 建新物料申请新号。正式号不删除、不回收、不因 ERP 保存失败重发新号。

开发 ERP 主动申请入口为前端 `/erp/`。`seed.sh` 初始化已发布演示配置；ERP 来源记录先提交，再调用 MDM 并自动回填。集成绑定 `erp_mdm_integration` 和请求状态/幂等键 `erp_number_request` 保存在 ERP 库中，重启后保留。结果未知时打开原来源记录重试，不能以另建记录代替恢复。服务端客户端凭据通过 `ERP_DEMO_SOURCE_KEY` 提供，浏览器不接触该值。操作见 [ERP 模拟器测试说明](ERP模拟器测试说明.md)。

回写失败：先查询 ERP 当前值。相同号确认成功，其他非空号冲突，空值才能条件写入。未知结果不能直接重发；人工重试需要原因和已确认空值。更换适配器须发布新配置，然后 `rebuild`，保留 parent/revision、原号、稳定幂等键和历史任务。SENDING 租约超时恢复仍先查 ERP；无幂等能力时禁止盲发。定时重试间隔默认 60/300/1800/7200 秒，开发故障测试使用短延迟。

每日 02:00 UTC 和人工对账检查 ERP 空号、值不一致、未确认、缺任务及 ERP 有号但无 MDM 账本。对账只登记差异。来源结构新版本形成 Diff、依赖报告和告警；不会修改既有发布版本。复杂结构可交由 ERP View 实现。

## 备份演练

```bash
bash scripts/stop.sh
bash scripts/backup.sh
python3 tests/v3_backup_verify.py
bash scripts/start.sh
```

备份包括平台 PostgreSQL 和独立开发 ERP PostgreSQL，含 SHA256 清单。验证脚本动态枚举两库全部 public 表，恢复到随机后缀的新数据库，比较每表数量和行摘要；不覆盖源库、不删除旧库。覆盖 Source、Release、Registry、Assignment、Sequence、Cursor、Pending Writeback/Attempt、ERP 事实/回执及下游数据。运行中 `pg_dump` 单库内部一致，但跨库一致性须暂停写入并协调恢复点。

`tests/v3_restart.py` 在当前开发工作区关闭本实例 Worker 创建 PENDING 扫描/回写，停止项目进程再启动，重新登录后检查任务恢复、游标、原台账字段和正式号，同时比较 ERP 集成绑定与主动请求表的数量和摘要。仅用于开发演练，不能对生产服务直接运行。

恢复到真实部署时先禁用 Worker、核对凭据/目标/当前 ERP 值，再按租约与幂等策略启用。生产数据库、真实 ERP 备份授权、保留期和 RPO/RTO 尚未验收。
