# 通用料号与物料主数据平台

开发验收 MVP，基线为 [PRD V2.0](docs/通用料号与物料主数据平台_PRD_V2.0.md)。Java/Spring Boot 模块化单体、Vue 3 中文动态界面、PostgreSQL 权威数据与 JSONB 类型投影。行业示例是配置数据，新增行业无需增加实体、表或 Controller。

## 启动

云工作区项目目录为 `/workspace/mdm`。需要 Linux x86_64、Node 24/npm 11、Python 3、Docker Compose v2（支持 `--wait`）或外部 PostgreSQL16。脚本将已校验的 Temurin JDK21.0.6 与 Maven3.9.9保存在 `/workspace/.tools`，依赖和构建版本见 `backend/pom.xml`、`frontend/package-lock.json`。本机浏览器验收使用 `/usr/bin/chromium`。

按顺序执行：

1. `cd /workspace/mdm`：进入已有项目目录。
2. `scripts/install.sh`：安装固定版本工具和锁定的前端依赖，执行前端类型检查/生产构建、后端打包。此步骤不清空或初始化业务数据。
3. `scripts/start.sh`：等待 PostgreSQL 健康，再启动 Spring Boot、Vue 开发服务器和独立 ERP 模拟器，并检查实际健康响应。
4. `scripts/seed.sh`：显式初始化两个租户、用户、玻璃布与轴承配置、字典、单位、供应商及两套 ERP 映射。可重复执行，保留已有业务与配置。

开发访问地址：前端 `http://localhost:5173`，后端 `http://localhost:8080`，ERP 模拟器 `http://localhost:9090`。云环境需使用产品提供的端口访问方式；这些地址用于本机请求，不是云预览链接。

`scripts/stop.sh` 停止本项目应用进程，保留数据库和卷；修改后端后，先运行 `scripts/test.sh backend`（准备专用测试库、编译并执行数据库业务测试），再执行 `scripts/stop.sh` 与 `scripts/start.sh` 加载新构建。启动使用内容哈希命名的运行 JAR，构建不会覆盖运行中的归档。

外部 PostgreSQL 可设置 `DATABASE_URL`（例如 `jdbc:postgresql://host:5432/mdm`）、`DATABASE_USER`、`DATABASE_PASSWORD` 后启动，脚本不再启动本地数据库。凭证在环境中配置，不放进仓库。Compose 的 `mdm_dev_only` 仅用于本地开发。`PORT` 默认8080；Vite代理固定8080，需要改端口时通过开发配置调整代理。

## 演示

开发登录入口读取数据库登记用户，后端 session 确定操作者。用户不能通过随意填写用户名获得业务岗位。入口仅在 `dev` profile 启用；生产身份提供方属于后续上线接入。

|用户|岗位|操作|
|---|---|---|
|editor|设计员、物料编辑员、集成员|设计配置、录入/提交、导入、集成管理|
|reviewer|发布员、审批员、集成员|发布他人配置、审批他人物料、同步处理|
|admin|平台管理员与租户管理员|租户生命周期、成员、角色和设置；不默认兼任业务审批|
|reader|查询成员|TENANT-A玻璃布 / TENANT-B轴承范围不同|

租户为 `TENANT-A` 华东新材、`TENANT-B` 精工制造。

1. editor进入，切换租户，模型配置选择玻璃布，复制已发布配置、修改可视属性或高级规则 JSON、保存并提交审核。reviewer批准发布。
2. editor打开物料目录新建玻璃布，型号7628、幅宽1270 mm、克重210 g/m2、厂商SUP-001宏和，校验预览得到 `GC-7628-1270-HONGHE`；草稿只有UUID。提交后reviewer审批首次占号。同号已存在时须处理冲突，系统不自动加后缀。当前TENANT-A已保留该验收物料，重复演示可换型号。
3. 查看详情、固定Schema版本、历史、审批差异；修改规格须新建物料，名称等非编码变更可走申请。查询动态幅宽并导出CSV/XLSX；超过200行形成持久后台导出任务。
4. 导入中心上传UTF-8 CSV/XLSX，明确选择导入类别、工作表、Schema版本、列映射/单位/实际解析版本，全量预检，再确认提交通过行。行成功代表草稿/申请已创建，正式审批与同步分别处理。
5. 集成中心查看ERP映射，连接配置须含健康地址及独立错误契约测试地址，测试后启用。已生效后出站事件不会因后续启用而自动重新生成；需要对账确认后人工补偿。观察业务成功、失败、待确认、未知结果，下载文件批次或核实补发。
6. admin在平台租户管理暂停/恢复；业务数据仍可查询，后续写入及任务处理停止。归档须处理未结束任务；重开进入暂停，再恢复运行。

## 验证与交付资料

`scripts/test.sh backend` 编译后端并执行真实 PostgreSQL 业务测试；首次会创建专用 `mdm_test` 库，不重置已有测试库。

`scripts/test.sh http` 启动独立8081/9091实例并执行可靠集成验证。测试等待1秒，业务默认仍是60/300/1800/7200秒。

`scripts/test.sh operations` 验证导入暂停/租户暂停/进程重启、文件、归档重开和性能抽样。只重启专用测试实例。

`scripts/test.sh browser` 执行前端类型检查/构建以及真实Chromium端到端测试。浏览器用例在演示租户创建唯一标记的记录，不删除既有数据。

测试不要并行操作同一测试租户或 Playwright 输出目录。结果、日志、截图和下载保存在 `.runtime`，不进入Git。当前结果、局限见 [测试记录](docs/testing.md)、[需求矩阵](docs/requirements-matrix.md) 和 [已知问题](docs/known-issues.md)。接口和示例见 [接口说明](docs/api.md)，恢复/清理/对账见 [运行手册](docs/operations.md)。

ERP模拟器实际保存SQLite外部记录，支持成功、业务失败、429、503、ACK丢失、202、重复请求和结果查询。模拟服务不代表真实ERP联通或UAT通过。生产容量、月度可用性及RPO/RTO须在目标环境验证。
