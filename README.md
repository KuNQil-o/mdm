# 统一物料身份与料号治理平台 · V4.0

这是根据 [PRD V4.0](docs/通用物料身份与料号治理平台_PRD_V4.0.md) 从零实现的独立项目，位于 GitHub `v4.0` 分支。V3 的 Java 工程、数据采集、Dataset、扫描、写回和对账实现没有进入本项目；原项目仍保留在原分支和 Git 历史。

调用方是业务事实源：自行创建和维护物料、自行组织完整属性、自行保存返回的料号。平台通过统一 HTTP 契约完成标准化、校验、派生、Identity、编码和原子发号，保留永久解释账本。

```text
Caller HTTP JSON → Schema / Normalize → Derivation / Validation
→ Material Identity → Code Rule / Sequence → Atomic Assignment Ledger → materialNo
```

技术栈：Python 3.12 / FastAPI、PostgreSQL 16、Vue 3 / TypeScript / Vite。行业差异全部位于版本化 JSON 配置。已提供玻璃布、轴承、电子元器件三种示例类别，核心没有行业或 ERP 专用分支。

## 本地运行

需要 Python 3.12+、Node 22.12+、Docker Compose v2 和 Linux 的 `setsid`。开发环境数据库使用独立 `mdm-v4_v4-data` 卷，端口 **5433**；不会复用 V3 数据库。

```bash
bash scripts/install.sh
bash scripts/start.sh
```

打开 `http://localhost:5173`。后端为 `http://localhost:8080`，OpenAPI 契约为 `/api/openapi.json`，接口文档为 `/api/docs`。这些地址是本地运行入口，不代表已发布到公网。

开发模式自动初始化 `TENANT-A` / `TENANT-B`、`admin`、`editor`、`reviewer`、`reader`、`auditor` 和三个经过双人发布的配置示例。前端默认选择规则设计员；创建草稿并提交后，以审批员登录完成发布。新 Caller API Key 只显示一次；默认演示 Caller 的随机凭据保存在权限为 0600 的 `.runtime/dev-callers.json`，该文件不进入 Git。

`scripts/stop.sh` 只停止 V4 应用进程，保留数据库和卷。修改后端后执行 `scripts/stop.sh` 和 `scripts/start.sh`；前端构建使用 `npm --prefix frontend run build`。

## 正式调用

```http
POST /api/v1/material-number-assignments
X-Tenant-Code: TENANT-A
X-Caller-Key: <创建或轮换时获得的 API Key>
Idempotency-Key: <调用方持久保存的请求 UUID>
Content-Type: application/json
```

```json
{
  "callerSystemCode": "ERP_DEMO",
  "sourceRecordKey": "100001",
  "categoryCode": "GLASS_CLOTH",
  "attributes": {
    "clothType": "7628", "manufacturer": "HH", "treatment": "A",
    "basisWeight": 210, "width": 1270
  }
}
```

首次响应为 `ISSUED`。重复幂等键、重复来源键或相同 Identity 返回原 `assignmentId/materialNo` 和 `REUSED`；不同请求内容复用同一幂等键返回 409。已发号来源的 Identity 变化返回 `SOURCE_IDENTITY_CHANGED_AFTER_ISSUE`。

预览为 `POST /api/v1/material-number-previews`，不要求来源键，不创建账本或计数器。管理成员不能凭登录会话正式发号；正式入口必须使用绑定 Caller 和类别范围的凭据。

接入流程、精确小数、配置格式、签名与错误模型见 [API 与配置说明](docs/api.md)。可独立运行 [调用方示例](scripts/caller_example.py)：先创建调用方本地业务记录，再请求 MDM，最后将料号保存到调用方自己的 SQLite 数据库，重复执行使用同一持久化幂等键。

## 验证

```bash
bash scripts/test.sh backend       # 独立 mdm_v4_test，真实 PostgreSQL / HTTP / 进程崩溃
bash scripts/test.sh browser       # 系统 Chromium，4 个完整操作流程
bash scripts/test.sh operations    # 独立恢复目标与应用重启重试
bash scripts/test.sh security      # npm audit + pip-audit
backend/.venv/bin/python scripts/performance.py  # 持续 20 次正式发号/秒，200 次样本
backend/.venv/bin/python scripts/capacity.py     # 新建独立库，100万账本 / 500类别容量
```

浏览器测试默认使用 `/usr/bin/chromium`，其他机器可通过 `CHROMIUM_PATH` 指定路径。测试产生独立前缀和新数据库，不覆盖现有正式记录。容量测试使用保留全部约束的合成账本，**不等价于 100 万次 HTTP 发号或生产企业真实数据验收**。

P0 覆盖和实测证据见 [验收报告](docs/acceptance.md)；运行、安全配置及上线边界见 [运维说明](docs/operations.md)。真实业务系统 UAT、企业安全签收、99.9% 可用性及企业 RPO/RTO 仍需在目标环境验收，本项目没有进行生产部署。
