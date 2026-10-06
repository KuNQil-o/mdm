# 实际测试记录（2026-10-06）

开发验收完成；不等于生产上线验收。下表均为真实执行结果，未跳过、未禁用或用零测试代替。业务数据库为PostgreSQL16.6，非H2/内存替代；后端JDK21.0.6/Spring Boot3.4.3，前端Vue3.5.13/Vite6.2.2/TypeScript5.7.3，浏览器Chromium151/Playwright1.51.1。

## 命令、结果与证据

以下命令工作目录为 `/workspace/mdm`；浏览器命令注明单独目录。时间为北京时间，底层日志/事件为UTC。脚本均保留失败退出状态。

|检查|实际执行命令及作用|结果|当前证据|
|---|---|---|---|
|后端数据库业务|`scripts/mvn.sh -q package`：编译、在已准备的mdm_test执行JUnit并打包|10测试、0失败/错误/跳过；5.756秒；11:45|`.runtime/java-tests.log`、`backend/target/surefire-reports/com.acme.mdm.BusinessTest.txt`|
|可靠HTTP集成|`python3 tests/integration.py`：真实8081测试后端与独立9091持久化ERP联调|7测试通过，10.395秒；11:46|`.runtime/http-integration.log`、`integration-evidence.json`|
|最终类型检查/构建|`scripts/install.sh`：锁定npm ci、vue-tsc、Vite生产构建、后端打包|退出0；14:45；后端打包不重复运行数据库测试|`.runtime/install.log`|
|最终运行验证|`scripts/test-start.sh` 后 `python3 tests/operations.py`：200行恢复、文件/租户/身份/解析/导出/指标/导入版本绑定|13测试通过，27.169秒；14:47|`.runtime/operations.log`、`operations-evidence.json`（追加历史，最后13项为本轮）|
|最终浏览器|在 `frontend` 运行 `node node_modules/@playwright/test/cli.js test`：锁定Playwright操作真实页面与主库|3通过，0失败/跳过/重试，38.1秒；14:48|`.runtime/browser.log`、`browser-results.json`、截图与下载|
|当前V7恢复|`scripts/stop.sh` 后 `python3 tests/backup_verify.py`：停写、备份、创建独立恢复库并比较31表；随后 `scripts/start.sh`|退出0；全部行数/摘要一致；SQLite integrity=ok；9.624秒；14:49|`.runtime/backup.log`、`backup-evidence.json`|
|恢复后服务|start.sh实际健康响应及独立curl；再读取演示身份/租户/物料|后端UP、ERP UP、Vue入口HTML有效；原数据库继续使用|`.runtime/backend.log`、`erp.log`、`frontend.log`|

后端10项及HTTP7项所测服务在最后修改中未改变；最后针对导入类别/版本检查执行相关13项运行回归与3项浏览器复验，没有用旧浏览器结果代替当前源码。所有源码改动已重新构建。

## 业务行为

JUnit覆盖十进制规范化及PATCH缺失/null/0/false，条件/派生/单位错误，发布后不可变与历史旧Schema，退役阻止新建，同号不同租户、无流水冲突，强制事务回滚不留正式事件，DB持久幂等和并发重复请求，100个不同申请并发流水批准（100号唯一），正式变更/停启用/编码保护、引用归属/类型查询及当前成员类别权限/租户暂停。

HTTP集成验证两个目标的成功/HTTP200业务失败独立，429/503有限重试，ACK丢失和202查询后只创建一次，未知结果必须先核实，Inbox同体/异体去重和字段归属，正式事件前序与草稿版本跳号、乱序等待、防回环、实际目标对账MATCH，以及目标400ms最低发送间隔。测试等待为独立配置1秒，业务默认60/300/1800/7200秒未更改；未实际等待完整2小时周期。

运行验证的200行预检对比主表、Outbox和流水计数均不变；已有4行成功后暂停，暂停租户并重启专用测试后端，再恢复至200行成功，原成功行UUID不变。文件Outbox在本地批准后重启，继续生成JSONL并由回执确认；3行入站文件含非法与重复消息，源行结果可核对。还验证XLSX实际回读、跨租户文件404与无导出权限403、归档重开、持久后台导出、复用属性/七类型/字典快照/LEGACY、两PostgreSQL连接SKIP LOCKED不同领取与跨租户复合外键拒绝、身份版本重绑、替代关系、固定段解析和严格引用结构、按类别字段策略、错误报告字段及非法UTF8拒绝。更新导入类别或Schema版本与对象不符时，预检报错且物料保持原样。

浏览器三个流程分别验证：初始与动态后端条件、隐藏值保留、12位十进制派生只读与持久保存；租户切换、页面配置发布、玻璃布动态录入/预览/两人审批/历史/CSV导出及真实外部确认；包含通过、负数范围错误、重复和过期版本的UPDATE导入，修正后三行继续，首行申请ID不重放，4行全部成功。玻璃布指定示例 `GC-7628-1270-HONGHE` 已在TENANT-A保留；轴承与玻璃布均由通用配置生成，不引入行业专表或Controller。

最近修复：后台条件请求导致页面按钮短暂禁用、旧详情缓存看不到投递变化、导入类别默认值受新增类别影响。最后一项同时补上后端类别/版本校验，并让导入页面显式选类别/Schema。CSV负数文本的公式保护前缀导致一个测试预期不符，已按真实下载契约修正；不删业务错误断言。

## 性能与恢复范围

性能抽样时可用CPU5、测试库2488条物料，客户端并发1，详情/查询/预览各100次。详情P95 **15.92ms**，查询 **27.20ms**，预览 **19.29ms**。当时浏览器流程也在运行；这不是专用稳态压测，不能外推到百万容量、100会话50RPS或20次正式生效每秒。

备份位于 `.runtime/backups/20261006T064900Z`；恢复库 `mdm_restore_verify_20261006_064900`，与原mdm独立。31表包含用户/成员/角色、元数据、Schema、物料/历史/申请/投影、操作/审批、Outbox/投递尝试、身份、Inbox、映射、导入/暂存/文件、幂等、后台导出、序列/唯一键/替代关系、对账和Flyway迁移。备份文件SHA-256校验通过，业务逐表行数与内容摘要一致；ERP SQLite完整性通过。演练未覆盖生产时间点/WAL切换、异地故障或外部目标丢失，不能声称生产RPO/RTO达标。

运行日志中的 Python ResourceWarning 来自保留测试服务进程运行，Node NO_COLOR提示来自运行器环境；测试退出0并完成全部用例。准备8081时短暂连接拒绝由健康检查重试处理，最终就绪后执行测试。没有未解释的失败检查。

真实ERP UAT、规定生产负载/5万行预检/月度99.9%/业务确认SLA、企业Chrome/Edge基线未验证，见 [已知边界](known-issues.md) 与 [需求矩阵](requirements-matrix.md)。无Redis，不声称执行缓存故障演练。

## 重跑与环境恢复

`scripts/test.sh backend` 自动准备缺失的专用库并执行JUnit；`scripts/test.sh http` 和 `scripts/test.sh operations` 必须串行，后者会重启8081。`scripts/test.sh browser` 自动构建并启动主实例；不要同时改前端或执行npm ci导致HMR。每条命令的用途见README；演示和测试数据均不清空，重复执行使用唯一标记。

install_script/start_skill已保存到云环境草稿，未改动网络/凭证配置。保存不执行、不发布；须在环境设置中审阅、保存并发布。当前实例已验证，后续新任务快照恢复尚未独立验证。源码、原始PRD与文档留在现有工作区，没有自动提交或推送。

## 发布后重连验证（2026-10-06 14:59北京时间）

用户发布后，等待环境ready并使用保留的仓库/依赖/数据库卷。首次start暴露启动顺序问题：PostgreSQL处于starting up时后端已尝试连接，健康检查失败。start.sh、test-start.sh和test.sh的本地DB启动加入 `docker compose up -d --wait --wait-timeout 60 db`，不修改业务源码；Docker Compose v2.40.3实际支持，三个脚本bash -n通过。

停止本项目应用后，`docker compose stop db` 停止数据库容器（卷保留），再运行 `bash scripts/start.sh` 验证冷启动顺序：等待数据库Healthy后启动应用，退出0，8080/5173/9090实际响应正常。日志 `.runtime/post-publish-cold-start.log`。31表行数/摘要与发布前backup-evidence.json完全一致，证据 `.runtime/post-publish-data.json`。

在frontend用锁定Playwright执行只读浏览器检查，重新登录editor、查询指定玻璃布料号并打开历史：3条历史可查、pageErrors为空、退出0；证据 `.runtime/post-publish-browser.json` 与截图。未重新初始化或写入业务数据。既有业务源码未变，不无理由重跑全部业务测试。

发布版本与保存的安装/启动说明已读取确认；为数据库健康等待更新start_skill并保存了新草稿，仍需再次发布以纳入此次修复。发布后当前工作区重连已验证，独立新任务恢复仍未执行。

## 第二次发布确认

再次重连后读取环境配置，已发布版本包含数据库健康等待，draft_id为空；start.sh/test-start.sh/test.sh均保留--wait修复，bash -n通过。直接bash scripts/start.sh退出0，数据库先Healthy，随后8080/5173/9090就绪，首次连接问题未再出现。31表摘要与发布前完全一致、42条物料保留；只读Chromium重新登录、查指定示例与3条历史，无页面错误。证据.runtime/post-publish-2-start.log、post-publish-2-data.json及post-publish-2-browser.json。本次未改启动配置或新建草稿，无需再次发布；仍不将同会话重连说成独立新任务恢复。
