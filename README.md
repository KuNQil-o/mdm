# 通用料号与物料主数据平台 · V3

当前唯一需求基线是 [PRD V3.0](docs/通用料号与物料主数据平台_PRD_V3.0.md)，验收依据 [测试用例 V1.0](docs/MDM料号平台_测试用例_V1.0.md)。差距分析与实施顺序见 [实施计划](docs/implementation-plan.md)，逐用例证据见 [覆盖矩阵](docs/requirements-matrix.md) 和 [验收报告](docs/acceptance-report.md)。

日常操作和配置流程见 [使用说明 V3.0](docs/使用说明_V3.0.md)，包括多表 Dataset、规则发布、发号、台账查询、ERP 回写与异常处理。

ERP 是物料事实源。MDM 保存来源结构、标准化配置、完整 Identity、料号规则、不可变发号台账、任务与必要的非权威快照。核心管线为：

```text
Source System → Source Object → Source Dataset → Field Mapping → Schema
→ Material Identity → Validation/Derivation → Code Rule → Assignment Ledger → ERP Writeback
```

实际执行时先完成类型/单位标准化、派生和校验，再计算 Identity，最后发号；这是 PRD 7.4 的执行顺序。行业差异全部位于 Schema、Mapping、AST、Identity 和 Code Rule 配置；领域代码不按玻璃布、铜箔、胶片、基板等类别分支。

## 运行

云工作区目录 `/workspace/mdm`。需要 Linux x86_64、Node 24/npm 11、Python 3、Docker Compose v2（支持 `--wait`）、PostgreSQL 16；浏览器验收使用 `/usr/bin/chromium`。Java 21.0.6、Maven 3.9.9 由脚本校验安装至 `/workspace/.tools`；前端依赖由 lockfile 锁定。

```bash
cd /workspace/mdm
bash scripts/install.sh
bash scripts/start.sh
bash scripts/seed.sh
bash scripts/test.sh http
```

最后一步创建独立前缀的 V3 类别、配置、真实开发 ERP 记录，并执行 HTTP 验收；数据持久保存，便于在页面复核。重复执行使用新前缀，不覆盖已有业务记录。初始化用户为 `editor`（设计/集成）、`reviewer`（发布/集成）、`admin`、`reader`，可登录 `TENANT-A` / `TENANT-B`；提交人与最终发布人必须不同。

端口：前端 5173、后端 8080、独立 ERP 模拟服务 9092、PostgreSQL 5432。本机可使用 `http://localhost:5173`；云端使用环境产品提供的端口访问方式。这些回环地址不代表已发布的云预览。

页面支持来源登记、DDL/JDBC/API Object、Dataset Root/Join/Grain、真实数据预览、Schema/Mapping/Identity/Rule 草稿、组合发布、扫描、台账类型化搜索、解释链、回写/异常和对账。复杂元数据通过 JSON 编辑器配置。ERP 属性由 ERP 修改，页面发号操作读取登记来源记录。

```bash
bash scripts/test.sh all          # 后端 → HTTP → 浏览器 → 重启/备份恢复，串行
bash scripts/test.sh backend      # 真实 PostgreSQL，独立 mdm_test
bash scripts/test.sh http         # V3 API 验收，开发 ERP
bash scripts/test.sh browser      # 4 个 Chromium 端到端流程
bash scripts/test.sh operations   # 重启及两库独立恢复验证
```

`stop.sh` 仅停止项目应用进程，保留数据库和卷。后端构建不会替换正在运行的内容哈希 JAR；重新打包后执行 `scripts/stop.sh` 和 `scripts/start.sh`。不要删除数据库卷。脚本保留测试退出状态和 `.runtime` 证据。

## 配置边界

本地平台库 `mdm`，测试库 `mdm_test`，独立 ERP 事实库 `mdm_erp_v3`。MDM 的 JDBC 账号仅获 ERP `SELECT` 权限；回写使用独立 HTTP 服务。Compose 和脚本中的开发密码仅供本机演示。

真实端点须由部署配置 `SOURCE_DESTINATIONS`（分号分隔完整地址）授权。`CREDENTIAL_BINDINGS` 的格式为 `租户代码|环境引用名|登记地址`，多个条目用分号分隔。Schema、来源配置中只存引用名称，不能保存密码/Token，也不能任意读取服务端环境变量。默认授权仅覆盖本地模拟 ERP。真实秘密通过环境安全配置注入。

平台库支持 `DATABASE_URL` / `DATABASE_USER` / `DATABASE_PASSWORD`；`WRITEBACK_WORKERS` / `SCAN_WORKERS` 可暂停本实例后台执行。来源暂停及租户暂停由数据库状态控制所有工作实例。生产身份提供方、真实 ERP 适配器和正式业务规则见 [已知限制](docs/known-issues.md)。

V2 物料录入、逐物料审批、导入和旧集成写入口返回 `LEGACY_V2_RETIRED`，旧后台作业默认禁用；历史数据和回归代码保留。BOM、库存、工艺仅在独立 ERP 测试源中做兼容验证。未执行 IFS/KRS 迁移或生产切换。
