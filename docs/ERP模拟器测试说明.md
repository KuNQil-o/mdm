# ERP 模拟器：玻璃布保存与自动申请料号

该页面用于验证 PRD V3.0 的 ERP 主动请求模式。业务人员在 ERP 录入，MDM 负责标准化、校验、Identity 和发号；来源数据及请求状态保存在独立 ERP 数据库。演示页面只供开发测试，真实 ERP 仍需适配与 UAT。

## 打开页面

```bash
cd /workspace/mdm
bash scripts/start.sh
bash scripts/seed.sh
```

本机打开 `http://localhost:5173/erp/`。云环境打开现有前端访问入口后追加 `/erp/`，也可在 MDM 左侧点击“打开 ERP 测试页面”。前端会代理到开发 ERP 的 9092 端口，无需另行开放端口。开发 ERP 也直接提供 `/erp/` 页面。

初始化会创建 `TENANT-A` 下的 `ERP_GLASS_DEMO` 来源、类别、多表 Dataset、Schema、Mapping、Identity、Code Rule 和组合发布包。通过 `editor` 提交、`reviewer` 审批；人工 MDM 发号关闭，ERP 使用自己的来源身份请求。重复初始化保留已存在的发布规则和默认版本，不清空数据。

## 操作步骤

1. 打开 ERP 页面，选择厂商、布种、布种/基重、表面处理、幅宽、形态、等级，可填写备注。选项来自 ERP 有效参考记录。
2. 点击“保存并申请料号”。来源键由页面生成并保持稳定；ERP 首先保存未编号记录。
3. ERP 服务调用 MDM 的 `/api/v1/material-numbers:assign`，传入 Dataset code 和来源键，凭据由服务端提供。浏览器不直接调用 MDM，也不接触来源凭据。
4. MDM 通过已发布 Dataset 读取 ERP 主表及七个参考关联，按 Schema 标准化和校验，计算完整 Identity，再原子提交流水、台账、回写任务及 Outbox。
5. ERP 收到正式号后，保护已有非空值并自动保存到 `material_no`（页面显示“玻璃布料号”），提示“已回填，可继续 ERP 业务”。MDM 后台回写任务查询到相同号后确认成功。

示例发布规则为 `GC` 加六位流水，例如 `GC000001`，实际序号以当前台账为准。该规则由 MDM 元数据配置，不在 ERP 页面或服务中生成料号。身份包括类型、布种代码、基重、厂商、表面处理、形态、规范幅宽和等级；备注不参与身份。

“仅保存”只保存 ERP 记录，不申请或占用料号；之后点击“申请 / 重试料号”。列表可打开已保存记录、查看处理详情和刷新结果。页面刷新或服务重启不会清空 ERP 数据和原正式号。

## 测试建议与失败处理

| 场景 | 预期行为 |
| --- | --- |
| 第一次保存符合规则的规格 | 自动获得正式号并写入 ERP |
| 同一来源键重复申请 | 返回原号，只保留一个 MDM Assignment |
| 新建另一条相同规格 | `IDENTITY_CONFLICT`，保留未编号 ERP 记录 |
| 幅宽 127 cm 与 1270 mm | 标准化后属于相同身份，不会绕过防重 |
| 关联不存在或无效 | MDM 拒绝发号；修正后重试同一记录 |
| 已发号后修改规格 | ERP 页面锁定规格；接口拒绝修改，须新建物料 |
| 修改备注 | 保留原料号 |
| MDM 请求超时或响应丢失 | 保留 ERP 记录和请求幂等键，同一记录重试恢复原号 |
| ERP 已存在其他非空号 | 报冲突，不覆盖 |

第一次可使用默认选项。后续测试新物料请改变厂商、表面处理、幅宽或等级等真实规格组合；只改变备注不会形成新的 Identity。新建按钮会保留当前选项，方便调整规格。基重和布种代码由所选 ERP 参考记录提供。

失败时查看提示和“处理详情”，记录错误码、来源键、追踪编号；修正后点击“保存并申请料号”，或对已保存记录点击“申请 / 重试料号”。浏览器连接中断时，刷新列表并打开原来源键继续处理，不另建记录。`RESULT_UNKNOWN` 表示结果待确认，不能据此认定没有发号。

业务校验、关联有效性和发号规则始终由 MDM 发布配置执行。演示 ERP 的状态在 `erp_number_request` 中持久保存；并发操作按同一集成与来源键串行，ERP 保存事务在调用 MDM 前提交，不占住主表行锁等待回写。

## 接口与配置

| ERP 接口 | 用途 |
| --- | --- |
| `GET /erp/api/config` | 公开演示配置及有效参考选项，不下发来源凭据 |
| `GET /erp/api/cloth?integration=ERP_GLASS_DEMO` | 当前演示集成最近 100 条记录及发号状态 |
| `POST /erp/api/cloth` | `{integration,id,fields,assign}`；保存，`assign:true` 时自动申请 |
| `POST /erp/api/cloth/{id}/request-number` | `{integration}`；对已有记录申请或重试 |

`fields` 包括 `manufacturer_id,weight_id,treatment_id,width_id,form_id,grade_id,remarks`。接口不接受编辑料号字段。MDM 的目标地址由服务端 `ERP_MDM_BASE_URL` 指定，默认 `http://127.0.0.1:8080`；开发来源客户端使用 `ERP_DEMO_SOURCE_KEY`，数据库集成绑定只保存环境引用名称。默认凭据仅用于本地演示，变更凭据时需同步更新 MDM 来源绑定。

## 验证命令

```bash
bash scripts/test.sh erp      # 12 项 ERP → MDM → ERP 接口验证
bash scripts/test.sh browser  # 4 个 MDM + 3 个 ERP 浏览器流程
```

测试会创建独立前缀的 ERP 参考数据和物料，保留证据供复核。结果见 `.runtime/erp-workflow-results.json` 和 `.runtime/browser-results.json`，截图见 `.runtime/erp-page.png`。备份恢复程序会包含新增的 ERP 集成绑定和请求表，详见 [运行与恢复](operations.md)。

本次实现已验证：12 项 ERP 接口流程、7 个浏览器流程、101 组原有 V3 HTTP 验证均通过；重启后 ERP 集成与请求表数量/摘要保持一致；两库恢复验证的 63 张 public 表摘要全部匹配。前端构建、Python/JavaScript 语法及文档链接检查通过。
