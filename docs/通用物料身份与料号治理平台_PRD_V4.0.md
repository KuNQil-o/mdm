# 通用料号与物料主数据平台

**V4.0 产品定位：统一物料身份与料号治理平台**

**产品需求文档 PRD**  
**版本：V4.0｜状态：评审稿｜API First 精简版**  
**修订日期：2026年10月7日**  
**基线文档：通用料号与物料主数据平台 PRD V3.0（2026年10月6日）**

面向产品评审、架构设计、研发实现、测试验收及 ERP/业务系统集成。

---

## 文档摘要

V4.0 对 V3.0 做结构性收敛：不再把平台建设成“MDM + 数据集成平台”，而聚焦为 **行业无关、ERP 无关、规则驱动的企业统一物料身份与料号治理服务**。

企业现有 ERP、PLM、MES 或自研业务系统继续作为物料业务数据的事实源（System of Record，SoR），继续负责物料创建、维护、表结构、多表关联、业务状态与最终保存料号。调用方在需要料号时，自行准备本次发号所需的完整逻辑属性，通过标准 HTTP API 调用本平台。平台负责输入标准化、Schema 校验、派生、Material Identity 计算、唯一性判断、Code Rule 执行、流水分配、正式发号、不可变 Assignment Ledger、幂等、防重、版本治理与解释追溯，并同步返回 `materialNo`。

V4.0 P0 不再负责主动读取 ERP 数据库、不解析 ERP DDL、不建模 ERP 表结构、不配置多表 Join、不扫描待编号记录、不主动写回 ERP，也不维护写回任务和 ERP 对账。调用方如何从自身表、视图、接口或业务逻辑中组合出本次发号属性，属于 ERP/调用方适配层职责，不进入 MDM Core。

V4.0 的核心闭环为：

```text
ERP / PLM / MES / 自研业务系统
        │
        │ 自行完成业务 CRUD、多表查询与属性组合
        ▼
POST /api/v1/material-number-assignments
        │
        ▼
Schema → Normalize → Validation → Derivation
        │
        ▼
Material Identity
        │
        ▼
Code Rule / Sequence
        │
        ▼
Assignment Ledger（原子提交）
        │
        ▼
返回 materialNo
        │
        ▼
调用方自行保存 materialNo
```

本版本的目标不是建设 Golden Record、Match/Merge、Survivorship 等完整多域 MDM 能力，而是把“同一种物料如何被识别、如何唯一发号、如何保证重试不重复、如何解释历史号码”做成一个稳定、通用、可配置的企业基础服务。

---

# 01 产品定位与目标

## 1.1 产品定位

本产品定位为：

> **通用物料身份与料号治理平台（Material Identity & Number Governance Platform）。**

平台是企业统一的“物料身份判定与料号决策权威”，不是 ERP 替代品，不承担完整物料主数据 CRUD，也不承担通用 ETL、数据集成或跨系统 Golden Record 合并。

### ERP / 调用方负责

- 物料业务事实与业务状态。
- 物料新增、修改、停用等 CRUD。
- 企业自身数据库表、视图、接口和多表关系。
- 在发号前准备完整逻辑属性。
- 调用 MDM 发号 API。
- 将返回的 `materialNo` 保存到自身业务记录。
- 决定何时创建新物料记录、何时申请料号。

### MDM 负责

- Category 与标准 Schema。
- 输入字段规范化与可选轻量映射。
- 校验、默认值与派生规则。
- Material Identity 定义与规范化。
- Identity 唯一性与来源绑定。
- Code Rule 与 Sequence。
- Preview 与正式发号。
- 幂等、防重与并发一致性。
- Assignment Ledger 与 Input Snapshot。
- Schema、Identity、Rule 等版本治理与审批发布。
- 任一料号的完整解释、审计与运行监控。

## 1.2 核心设计原则

**P-01 API First**：P0 唯一正式业务入口为标准 HTTP API。MDM 不主动连接 ERP 数据库获取物料。

**P-02 ERP/Caller as SoR**：业务事实由调用方维护，MDM 不建立另一套可编辑物料主库。

**P-03 Adapter Boundary**：MDM Core 不依赖任何特定 ERP 产品、版本、表名、列名或接口协议。调用方负责把自身数据转换为标准 Assignment Request。

**P-04 Metadata Driven**：新增行业、企业或物料类别原则上只新增 Category、Schema、Validation、Identity、Code Rule 等配置，不新增行业专用 Java 业务代码。

**P-05 Identity Before Number**：先判定“什么属性组合代表同一种物料”，再决定“这个物料应该生成什么号码”。

**P-06 Immutable Published Configuration**：所有会改变“同一输入生成什么结果”的已发布配置不可原地修改，只能发布新版本。

**P-07 Number Once**：正式号码一旦发放，不回收、不复用；网络失败、客户端超时和调用重试不得产生第二个号码。

**P-08 Explain Everything**：任一 `materialNo` 必须能够追溯当时输入、标准化结果、Identity、规则版本、Segment 计算结果和发号时间。

**P-09 No Hidden Industry Logic**：Core 不允许出现 `GlassClothNumberGenerator`、`BearingRuleService` 等行业专用发号实现。

## 1.3 产品目标

- **G01 行业无关**：至少三种结构明显不同的物料类别在不修改后端核心业务代码的情况下完成发号。
- **G02 ERP 无关**：不同调用系统通过同一 API 契约完成发号，Core 不感知调用方内部表结构。
- **G03 唯一可靠**：高并发、超时和重试下不重复发号。
- **G04 可解释**：历史号码永久保留当时规则依据。
- **G05 可治理**：Schema、Identity、Code Rule 等变更必须版本化、可审批、可审计。
- **G06 易集成**：调用方只需准备标准 JSON 并调用 HTTP 接口。
- **G07 可扩展**：P0 核心未来可被完整 MDM、PLM 或多个 ERP 复用，但 P0 不预先实现这些重能力。

## 1.4 明确非目标

P0 不建设：

- ERP 数据库 JDBC 连接与扫描。
- DDL 导入、数据库元数据读取。
- Source Object / Source Object Version。
- Source Dataset / Dataset Builder。
- 多表 Join、Material Grain、增量 Cursor。
- ERP 待编号记录轮询和 CDC。
- MDM 主动物料写回。
- Writeback Task、Outbox、RESULT_UNKNOWN、写回重试和 ERP 对账。
- 完整物料 CRUD。
- Golden Record、Match/Merge、Survivorship、Unmerge。
- 任意 SQL、任意脚本、任意远程代码执行。

---

# 02 发布范围

## 2.1 P0 范围

|能力|P0|
|---|---|
|调用系统|轻量 Caller System 注册、启停、鉴权与类别范围|
|接口|同步 HTTP 发号、Preview、查询、解释|
|Category|物料类别、状态、策略、版本关系|
|Schema|动态属性、类型、必填、长度、范围、单位、枚举、引用|
|输入适配|默认直接按 Schema 属性代码入参；可选简单字段别名/常量/字典映射|
|标准化|Trim、大小写、空值、数值精度、单位、枚举标准码|
|Validation|结构化校验规则与错误定位|
|Derivation|基于已标准化属性的确定性派生|
|Identity|身份属性、规范化、canonical、hash、唯一策略|
|Code Rule|常量、属性、字典、格式化、流水、分隔符等 Segment|
|Sequence|原子递增、周期作用域、允许空洞、不回收|
|Preview|不占号的规则预览与解释|
|Assignment|正式原子发号、Identity 复用、来源绑定|
|Ledger|不可变发号事实、输入快照、规则版本与解释数据|
|幂等|Idempotency-Key + sourceRecordKey 双重防重|
|版本治理|Release Package、评审、发布、退役|
|多租户|Tenant、成员、角色、租户级唯一约束|
|查询|按料号、来源记录、类别、Identity、时间检索|
|审计|配置变更、发布、发号、人工操作日志|
|监控|请求量、延迟、失败率、冲突率、序列与数据库健康|

## 2.2 P1 范围

P1 在不改变 P0 核心模型的前提下增强：

- OBJECT / ARRAY / MULTI_VALUE 等复杂 Schema。
- 决策表和受控表达式增强。
- 可插拔脚本沙箱（如确有企业需求）。
- Reference Data 独立治理。
- 历史料号 Parse Rule / Migration Assistant。
- 异步发号 API、Webhook/Event 通知。
- 批量发号导入。
- 更复杂的 Input Profile 与嵌套 JSON 映射。
- 数据质量统计与规则命中分析。
- 组织树、配额、计费。

## 2.3 P2 / 独立产品能力

以下能力仅在出现明确商业需求后评估，不作为 V4 路线默认扩展：

- JDBC/CDC 主动采集 ERP 数据。
- 通用 Dataset/Join/ETL 编排。
- 主动 ERP Writeback 与分布式可靠投递。
- 多源实体 Match/Merge、Survivorship、Golden Record。
- 完整 Master Data Hub。

---

# 03 用户角色与业务闭环

## 3.1 角色

|角色|主要职责|
|---|---|
|平台管理员|Tenant、成员、角色、系统级安全与运行参数|
|规则设计员|Category、Schema、Validation、Derivation、Identity、Code Rule 草稿|
|规则审批员|审核 Release Package 并发布|
|集成管理员|注册 Caller System、管理 API 凭据与调用范围|
|业务查询员|查询发号记录与解释链|
|审计员|查看配置、发号与人工操作记录|
|调用系统|提交 Assignment Request、接收并保存 materialNo|

## 3.2 正常发号闭环

```text
1. 业务用户在 ERP/业务系统创建物料
2. 调用方自行查询并组合发号所需属性
3. 调用 MDM Assignment API
4. MDM 加载当前已发布 Release
5. 输入标准化与 Schema 校验
6. 执行 Validation / Derivation
7. 计算 Material Identity
8. 查找已有来源绑定与已有 Identity Assignment
9. 已存在则复用原 materialNo；不存在则执行 Code Rule
10. 原子分配 Sequence、写 Assignment Ledger 与 Source Binding
11. MDM 返回 materialNo
12. 调用方自行保存 materialNo
```

## 3.3 响应丢失与重试闭环

如果 MDM 已成功提交 Assignment，但响应在网络中丢失：

```text
ERP 第一次调用
    ↓
MDM 已发号并提交
    ↓
响应超时 / ERP 未拿到结果
    ↓
ERP 使用相同 sourceRecordKey 和 Idempotency-Key 重试
    ↓
MDM 返回原 assignmentId + 原 materialNo
```

不得重新生成第二个号码。

**BR-01**：MDM 事务成功后，号码视为正式发放，即使调用方最终未保存该号码，也不得回收该号码。

## 3.4 已发号记录发生身份变化

同一 `sourceSystemCode + sourceRecordKey + categoryCode` 已绑定正式 Assignment 后，如果再次请求时 Identity 属性变化：

- 不自动重编码。
- 不覆盖原 Assignment。
- 返回 `SOURCE_IDENTITY_CHANGED_AFTER_ISSUE`。
- 调用方应根据业务语义决定是否创建新的业务记录并重新申请。

---

# 04 Caller System 与输入契约

## 4.1 Caller System

**FR-01 P0**：平台提供轻量 Caller System 注册，不再表达数据库结构，只表达“谁在调用 MDM”。

字段至少包括：

- `callerSystemId`
- `code`
- `name`
- `systemType`：ERP / PLM / MES / CUSTOM / OTHER
- `environment`：DEV / TEST / PROD
- `status`：DRAFT / ACTIVE / SUSPENDED / RETIRED
- `authProfileRef`
- `allowedCategoryCodes`
- `owner`
- `description`

**BR-02**：Caller System 不保存 JDBC URL、表名、DDL、PK/FK、Join、SQL 或写回字段。

## 4.2 Source Record Key

**FR-02 P0**：每次正式发号请求必须提供 `sourceRecordKey`，用于标识调用方中的业务记录。

来源记录唯一键为：

```text
(tenantId, callerSystemCode, categoryCode, sourceRecordKey)
```

该键与 Material Identity 不同：

- Source Record Key 回答“调用方的哪条记录”。
- Material Identity 回答“这组标准属性代表哪一种物料”。

## 4.3 输入 JSON

P0 推荐调用方直接以 Schema 属性代码提交：

```json
{
  "callerSystemCode": "ERP_PROD",
  "sourceRecordKey": "100001",
  "categoryCode": "GLASS_CLOTH",
  "attributes": {
    "clothType": "7628",
    "manufacturer": "HH",
    "treatment": "A",
    "basisWeight": 210,
    "width": 1270
  }
}
```

## 4.4 轻量输入映射

**FR-03 P0**：当调用方无法直接使用标准属性代码时，可为 Caller System + Category 配置 Input Profile。

P0 仅支持：

- 字段别名。
- 常量。
- 简单字典映射。
- 基本字符串格式化。

P0 不支持任意 JSONPath 脚本、SQL、Join 和远程查询。

**BR-03**：若调用方能够直接按标准 Schema 入参，应优先不配置 Input Profile。

---

# 05 Category、Schema 与标准化

## 5.1 Category

**FR-04 P0**：Category 表示稳定的逻辑物料类别，与任何 ERP 表结构解耦。

字段至少包括：

- `categoryCode`
- `categoryName`
- `status`
- `description`
- `identityReusePolicy`
- `numberingPolicy`

新增行业原则上通过新增 Category 和配置完成。

## 5.2 Schema

**FR-05 P0**：每个 Category 通过版本化 Schema 定义允许的标准属性。

属性类型至少支持：

- STRING
- INTEGER
- DECIMAL
- BOOLEAN
- DATE
- ENUM
- REFERENCE

属性定义至少包括：

- `attributeCode`
- `name`
- `type`
- `required`
- `length`
- `precision/scale`
- `min/max`
- `unit`
- `enumDictionary`
- `searchable`
- `displayOrder`

## 5.3 标准化

**FR-06 P0**：在 Identity 与 Code Rule 之前执行确定性标准化：

```text
Raw Input
→ Input Profile（可选）
→ Type Conversion
→ Trim / Empty Normalization
→ Case Normalization
→ Unit Conversion
→ Enum / Reference Normalization
→ Default Value
→ Derivation
→ Validation
```

**BR-04**：参与 Identity 或 Code Rule 的数值使用十进制定点语义，不允许二进制浮点误差影响身份或料号。

## 5.4 Schema 版本

**FR-07 P0**：已发布 Schema Version 不可原地修改。新增/删除属性、类型变化、单位变化、是否参与 Identity/Code Rule 的变化必须进入新 Release。

---

# 06 Validation 与 Derivation

## 6.1 Validation

**FR-08 P0**：支持结构化校验：

- 必填 / exists
- eq / ne
- gt / gte / lt / lte
- in / not in
- and / or / not
- 字符串长度与基础模式
- 数值范围
- 枚举合法性
- 引用合法性
- 属性间基本比较

校验结果：

```text
ERROR   → 阻止正式发号
WARNING → 记录但允许继续
```

错误结构至少包括：

- `fieldPath`
- `errorCode`
- `message`
- `sourceValue`
- `normalizedValue`
- `ruleVersion`
- `suggestion`

## 6.2 Derivation

**FR-09 P0**：支持确定性派生属性，例如：

```text
normalizedWidth = convert(width, sourceUnit, "MM")
sizeClass = if(width >= 1000, "WIDE", "NORMAL")
```

派生依赖形成 DAG，不允许循环依赖。

**BR-05**：内部派生属性由 MDM 计算，调用方不得通过 payload 覆盖受保护派生字段。

---

# 07 Material Identity

## 7.1 Identity Definition

**FR-10 P0**：每个 Category 必须定义至少一个已发布 Identity Definition。

Identity 属性配置包括：

- 属性顺序。
- null 策略。
- Trim / Case 策略。
- 单位归一规则。
- Enum/Reference 使用稳定代码还是展示名。
- Decimal scale。

生成：

```text
identityCanonical
identityHash
```

示例：

```text
manufacturer=HH|clothType=7628|treatment=A|basisWeight=210.000|width=1270.000
```

**BR-06**：`identityHash` 仅用于索引加速，发生 Hash 相同时仍必须比较完整 `identityCanonical`。

## 7.2 Identity 复用

**FR-11 P0**：默认策略 `REUSE_EXISTING`：不同来源记录提交相同 Category + Identity 时，复用已有 `materialNo`，并建立新的 Source Binding，不再生成第二个号码。

可选策略 `REVIEW_ON_DUPLICATE`：企业若要求同 Identity 的不同来源记录必须人工确认，则返回 `IDENTITY_CONFLICT`。

## 7.3 来源身份变化

**FR-12 P0**：已有 Source Binding 再次提交时：

- Identity 未变化：返回原 Assignment。
- 仅非 Identity 属性变化：可记录最新请求信息，但不改变号码。
- Identity 发生变化：返回 `SOURCE_IDENTITY_CHANGED_AFTER_ISSUE`，不自动重编码。

**AC-01**：修改备注等非 Identity 字段不会改变 `materialNo`；修改制造商等 Identity 字段会被阻止自动重编码。

---

# 08 Code Rule、Sequence、Preview 与正式发号

## 8.1 Code Rule

**FR-13 P0**：Code Rule 使用结构化 Segment 组成，不在 P0 开放任意脚本。

Segment 类型至少包括：

- CONSTANT
- ATTRIBUTE
- DICTIONARY
- REFERENCE_CODE
- NUMBER_FORMAT
- UNIT_FORMAT
- SEQUENCE
- SEPARATOR

规则可定义：

- 最大长度。
- 允许字符集。
- 大小写策略。
- 空值处理。
- Segment padding。
- Sequence 周期作用域。

## 8.2 Sequence

**FR-14 P0**：Sequence 按以下作用域原子分配：

```text
(tenantId, codeRuleVersionId, sequenceName, periodKey)
```

规则：

- 允许出现空洞。
- 不允许回退。
- 不允许复用。
- Preview 不消耗正式 Sequence。

## 8.3 Preview

**FR-15 P0**：提供 Preview API 和页面，输入样本后展示：

- 标准化属性。
- Validation 结果。
- Derivation 结果。
- Identity canonical/hash。
- Code Rule Segment 明细。
- 预期 `materialNo` 模板。

若规则包含 Sequence，Preview 使用占位符或模拟值，不占正式号。

## 8.4 正式发号

**FR-16 P0**：正式发号必须在本地数据库事务内完成：

```text
1. 校验幂等键
2. 校验 Source Binding
3. 计算并锁定 Identity
4. 查已有 Assignment
5. 若已有则复用
6. 若不存在则原子分配 Sequence
7. 生成 materialNo
8. 校验 materialNo 唯一
9. 写 Assignment Ledger
10. 写 Source Binding
11. 写 Idempotency Record
12. Commit
13. 返回结果
```

**BR-07**：MDM 不在正式事务中调用 ERP 或其他外部系统。

**BR-08**：正式 Assignment 提交后不可因客户端超时、业务回滚或网络中断而撤销和回收号码。

---

# 09 Assignment Ledger、幂等与状态

## 9.1 Assignment Ledger

**FR-17 P0**：Assignment Ledger 是发号事实的权威记录，至少保存：

- `assignmentId`
- `tenantId`
- `categoryCode`
- `materialNo`
- `identityCanonical`
- `identityHash`
- `schemaVersionId`
- `inputProfileVersionId`（可空）
- `validationRuleVersionIds`
- `derivationRuleVersionIds`
- `identityDefinitionVersionId`
- `codeRuleVersionId`
- `releasePackageVersionId`
- `inputSnapshot`
- `normalizedSnapshot`
- `derivedSnapshot`
- `codeSegmentSnapshot`
- `issuedAt`
- `issueRequestId`

Ledger 不代表 ERP 完整物料主数据，只代表“为什么这个 Identity 在当时被分配这个号码”。

## 9.2 Source Binding

**FR-18 P0**：Source Binding 保存：

```text
(tenantId, callerSystemCode, categoryCode, sourceRecordKey) → assignmentId
```

一个 Assignment 可被多个 Source Binding 引用，用于跨系统/跨记录复用同一 Material Identity。

## 9.3 双重幂等

**FR-19 P0**：正式发号同时使用：

1. `Idempotency-Key`：防止同一次业务请求因网络重试重复执行。
2. `Source Binding`：防止同一业务记录使用不同幂等键重复发号。
3. `Identity Unique Constraint`：防止不同来源记录为同一 Identity 并发生成多个号码。

**BR-09**：相同 `Idempotency-Key` 若携带不同请求语义，返回 `IDEMPOTENCY_CONFLICT`，不得复用旧响应掩盖参数变化。

## 9.4 状态模型

P0 不再保留同步/写回状态机。请求处理状态简化为：

```text
RECEIVED
   ↓
VALIDATING
   ↓
IDENTITY_RESOLVED
   ↓
ASSIGNMENT_RESOLVED
   ├─ ISSUED
   └─ REUSED

任一阶段失败 → REJECTED
```

Assignment 一旦 `ISSUED` 即为永久发号事实；P0 不提供“释放号码”状态。

---

# 10 配置版本、Release Package 与审批

## 10.1 Release Package

**FR-20 P0**：以下配置组合为一个可发布 Release Package：

- Category Version
- Schema Version
- Input Profile Version（可选）
- Validation Rule Versions
- Derivation Rule Versions
- Identity Definition Version
- Code Rule Version
- Reference/Dictionary Versions（若被依赖）

状态：

```text
DRAFT → REVIEW → PUBLISHED → RETIRED
```

## 10.2 发布前检查

**FR-21 P0**：发布前必须完成：

- Schema 依赖完整性。
- 必填属性覆盖检查。
- Identity 属性合法性。
- Derivation DAG 无环。
- Code Rule 依赖完整性。
- 样本 Validation。
- Identity 冲突样本检查。
- Code Rule 回归样本。
- materialNo 长度与字符集检查。
- 与上一发布版本的 Diff。

## 10.3 审批规则

**BR-10**：默认提交人与最终审批人不得为同一成员。

**BR-11**：任何可能改变“相同标准输入最终得到什么 Identity 或 materialNo”的配置必须进入 Release Package，不允许通过普通运行参数即时修改。

## 10.4 发布版本选择

**FR-22 P0**：正式请求默认使用 Category 当前唯一 `PUBLISHED` Release。调用方不能自行指定任意历史版本发正式号。

Preview 可由有权限的设计员选择 DRAFT/REVIEW 版本。

---

# 11 对外 API 契约

## 11.1 正式发号

```http
POST /api/v1/material-number-assignments
Idempotency-Key: 0d7f1a2c-...
Content-Type: application/json
```

请求：

```json
{
  "callerSystemCode": "ERP_PROD",
  "sourceRecordKey": "100001",
  "categoryCode": "GLASS_CLOTH",
  "attributes": {
    "clothType": "7628",
    "manufacturer": "HH",
    "treatment": "A",
    "basisWeight": 210,
    "width": 1270
  }
}
```

首次成功响应：

```json
{
  "assignmentId": "ASN-20261007-000001",
  "materialNo": "GC-7628-A-210-1270-HH",
  "status": "ISSUED",
  "reused": false,
  "categoryCode": "GLASS_CLOTH",
  "releaseVersion": "4.0.12",
  "issuedAt": "2026-10-07T10:30:00+08:00"
}
```

重试或相同 Identity 复用：

```json
{
  "assignmentId": "ASN-20261007-000001",
  "materialNo": "GC-7628-A-210-1270-HH",
  "status": "REUSED",
  "reused": true
}
```

## 11.2 Preview

```http
POST /api/v1/material-number-previews
```

Preview 不要求 `sourceRecordKey`，不得写 Assignment Ledger，不得消耗 Sequence。

## 11.3 查询与解释

```http
GET /api/v1/material-number-assignments/{assignmentId}
GET /api/v1/material-number-assignments/by-no/{materialNo}
GET /api/v1/material-number-assignments/by-source/{callerSystemCode}/{sourceRecordKey}
```

## 11.4 配置管理 API

至少提供：

```text
/api/v1/caller-systems
/api/v1/categories
/api/v1/schemas
/api/v1/input-profiles
/api/v1/validation-rules
/api/v1/derivation-rules
/api/v1/identity-definitions
/api/v1/code-rules
/api/v1/release-packages
```

配置修改使用 ETag / If-Match 或等价乐观锁机制。

---

# 12 错误模型与错误码

统一错误结构：

```json
{
  "requestId": "...",
  "code": "VALIDATION_ERROR",
  "message": "Material input validation failed",
  "details": [
    {
      "fieldPath": "attributes.width",
      "code": "OUT_OF_RANGE",
      "message": "width must be between 100 and 3000"
    }
  ]
}
```

P0 核心错误码：

|错误码|语义|
|---|---|
|CALLER_SYSTEM_NOT_FOUND|调用系统不存在|
|CALLER_SYSTEM_SUSPENDED|调用系统已暂停|
|CATEGORY_NOT_FOUND|类别不存在|
|NO_PUBLISHED_RELEASE|类别无可用发布版本|
|INPUT_MAPPING_ERROR|轻量输入映射失败|
|SCHEMA_VALIDATION_ERROR|Schema 类型/必填等失败|
|VALIDATION_ERROR|业务校验失败|
|DERIVATION_ERROR|派生失败|
|IDENTITY_INCOMPLETE|Identity 必需字段不足|
|IDENTITY_CONFLICT|按类别策略需要人工处理的重复 Identity|
|SOURCE_IDENTITY_CHANGED_AFTER_ISSUE|同一来源记录已发号后身份改变|
|CODE_GENERATION_ERROR|编码规则执行失败|
|CODE_CONFLICT|生成 materialNo 与已有不同 Identity 冲突|
|IDEMPOTENCY_CONFLICT|同幂等键请求语义不一致|
|SEQUENCE_ERROR|流水分配失败|
|RATE_LIMITED|调用限流|
|CONFIGURATION_ERROR|已发布配置内部依赖异常|
|INTERNAL_ERROR|内部错误|

建议 HTTP 状态：

- 400：格式错误。
- 401/403：认证授权失败。
- 404：调用系统/类别不存在。
- 409：Identity/Source/Idempotency 冲突。
- 422：Schema/Validation/Derivation 失败。
- 429：限流。
- 503：临时不可用。

---

# 13 页面与工作台

V4.0 P0 页面收敛为：

|页面|核心内容|
|---|---|
|工作台|调用量、成功率、失败率、最近发号、主要异常|
|Caller System|系统注册、环境、状态、鉴权、允许类别|
|Category / Schema|类别与动态属性、版本 Diff|
|Input Profile|可选轻量字段别名/字典映射|
|Validation / Derivation|结构化规则与样本测试|
|Identity 设计|身份字段、规范化、canonical/hash 样本|
|Code Rule|Segment、Sequence、样本 Preview|
|Release Package|依赖版本、测试结果、提交/审批/发布|
|Assignment Ledger|来源键、Identity、materialNo、时间、是否复用|
|料号解释|完整输入→标准化→Identity→Rule→号码链路|
|审计日志|配置、发布、人工操作、发号事件|
|运行监控|API P95、错误率、冲突率、DB/Sequence 健康|
|租户与权限|Tenant、成员、角色与权限|

明确删除：

- Source Object 页面。
- DDL/JDBC 元数据页面。
- Dataset Builder。
- 同步任务页面。
- Writeback 任务页面。
- ERP 对账中心。
- Source Structure Diff 页面。

---

# 14 最小数据模型与一致性约束

## 14.1 核心实体

P0 最小实体：

```text
Tenant
User / Role / Membership
CallerSystem
Category / CategoryVersion
SchemaVersion / AttributeDefinition
InputProfileVersion        (optional)
ValidationRuleVersion
DerivationRuleVersion
IdentityDefinitionVersion
CodeRuleVersion
SequenceCounter
ReleasePackage / Approval
MaterialAssignment
SourceBinding
IdempotencyRecord
OperationLog
```

## 14.2 关键唯一约束

必须由数据库保证：

```text
UNIQUE (tenant_id, material_no)

UNIQUE (
  tenant_id,
  category_id,
  identity_canonical
)

UNIQUE (
  tenant_id,
  caller_system_id,
  category_id,
  source_record_key
)

UNIQUE (
  tenant_id,
  caller_system_id,
  idempotency_key
)
```

若 `identityCanonical` 过长，可用 `identityHash + canonical` 两阶段校验，不可只靠 Hash 唯一。

## 14.3 Assignment 与 Source Binding 分离

```text
MaterialAssignment
    1
    │
    ├──── SourceBinding A (ERP-A:10001)
    ├──── SourceBinding B (ERP-B:7788)
    └──── SourceBinding C (PLM:ABC-9)
```

这样同一 Material Identity 可跨调用系统复用同一企业料号，而无需复制 Assignment。

## 14.4 Input Snapshot

**FR-23 P0**：Input Snapshot 只保存发号解释所需内容：

- 原始 `attributes`。
- 输入映射后的值。
- 标准化值。
- 派生值。
- Identity canonical/hash。
- Code Rule Segment 输入输出。
- 所有被引用的配置版本。

不复制调用方完整业务记录。

## 14.5 强一致范围

必须本地强一致：

- Identity 唯一。
- materialNo 唯一。
- Source Binding 唯一。
- Sequence 分配。
- Assignment Ledger。
- Idempotency Record。

调用方保存 materialNo 属于 MDM 事务之外；通过重试原 Assignment 实现最终业务闭环。

---

# 15 多租户、权限、安全与审计

## 15.1 多租户

**FR-24 P0**：所有业务配置和发号事实均带 `tenantId`。不同租户可拥有相同 Category Code、Identity 和 materialNo，不产生跨租户冲突。

Tenant 状态：

```text
DRAFT / ACTIVE / SUSPENDED / ARCHIVED
```

SUSPENDED 后禁止新 Preview 之外的正式发号请求。

## 15.2 权限

权限至少区分：

- Caller System 管理。
- Category/Schema 设计。
- Validation/Derivation 设计。
- Identity 设计。
- Code Rule 设计。
- Release 提交。
- Release 审批/发布。
- Preview。
- 正式手工发号（默认关闭）。
- Assignment 查询。
- 审计查询。
- 租户与成员管理。

## 15.3 API 安全

- HTTPS。
- API Key / OAuth2 Client Credentials / 企业网关接入之一。
- Caller System 与凭据绑定。
- 凭据只存引用或密文，不进入普通业务表明文。
- 按 Tenant + Caller System + Category 授权。
- 支持请求签名或网关层防重放策略。
- 日志不得记录 Token、密码和完整敏感凭据。

## 15.4 Operation Log

**FR-25 P0**：至少记录：

- Caller System 新增/启停。
- Schema/Identity/Code Rule 等配置修改。
- Release 提交/审批/发布/退役。
- Preview（可采样）。
- 正式发号。
- Source Binding 复用。
- 冲突与人工处理。
- 租户、成员、角色变化。

日志包含：对象、版本、操作人/客户端、UTC 时间、requestId/traceId、原因、Diff 摘要。

---

# 16 运行监控与非功能要求

## 16.1 核心指标

监控至少包括：

- Assignment 请求量。
- 成功率 / REUSED 比例。
- Schema Validation 失败率。
- Identity Conflict 数量。
- SOURCE_IDENTITY_CHANGED_AFTER_ISSUE 数量。
- Code Generation 失败率。
- API P50/P95/P99。
- Sequence 分配延迟。
- 数据库锁等待。
- 当前 Published Release。
- Caller System 健康调用情况。

## 16.2 NFR

**NFR-01 容量**：单租户支持至少 100 万条 MaterialAssignment、500 个 Category、多版本配置。

**NFR-02 吞吐**：单实例基础目标持续 20 次正式发号/秒；通过水平扩展提高整体吞吐。

**NFR-03 并发一致性**：100 个并发请求针对同一 Source Record 或同一 Identity 时，只允许产生一个 Assignment 和一个 materialNo。

**NFR-04 延迟**：在无外部依赖且数据库健康时，正式发号 API P95 ≤ 500ms；Preview P95 ≤ 500ms。

**NFR-05 可用性**：生产目标月可用性 99.9%。

**NFR-06 恢复**：建议 RPO ≤ 15 分钟，RTO ≤ 4 小时。

**NFR-07 可观测性**：所有正式请求具备 `requestId` 与 `traceId`，能关联 API、规则执行、事务与 Ledger。

**NFR-08 浏览器**：管理端以企业常用 Chrome / Edge 当前支持版本为基线，中文 UI。

## 16.3 数据保留

建议：

- MaterialAssignment / Ledger：长期保留。
- 被 Ledger 引用的已发布配置版本：不得删除。
- Input Snapshot：默认长期保留或至少 5 年，企业可配置但不得破坏解释链。
- Operation Log：至少 3 年。
- API 原始敏感报文：默认不长期保留；必要字段已进入脱敏 Input Snapshot。
- Idempotency Record：至少 7 天；即使过期，Source Binding 与 Identity Unique Constraint 仍保证不会重复发号。

---

# 17 验收用例

## AC-01 非 Identity 属性变化

同一 Source Record 已发号后，仅修改备注等非 Identity 属性再次请求，应返回原 `materialNo`。

## AC-02 Identity 属性变化

同一 Source Record 已发号后修改 Identity 字段，应返回 `SOURCE_IDENTITY_CHANGED_AFTER_ISSUE`，不得生成新号码。

## AC-03 网络超时重试

第一次发号在 MDM 已提交后模拟响应丢失；客户端使用相同幂等键重试，应得到原 `assignmentId/materialNo`。

## AC-04 不同幂等键重复请求

同一 Source Record 使用不同 Idempotency-Key 再次调用，仍只能得到原 Assignment。

## AC-05 同 Identity 跨来源复用

两个不同 Source Record 提交相同 Identity，在 `REUSE_EXISTING` 策略下获得同一个 materialNo，并形成两个 Source Binding。

## AC-06 并发唯一性

100 个并发请求针对同一 Identity，只允许一个事务创建 Assignment，其余请求全部复用同一结果。

## AC-07 materialNo 唯一

并发生成大量不同 Identity 时，不允许出现同一 tenant 下两个不同 Assignment 使用同一 materialNo。

## AC-08 Preview 不占号

连续执行 100 次 Preview，不得改变 Sequence 正式计数。

## AC-09 历史可解释

根据任一 materialNo 可以完整展示：

```text
Caller System / Source Record
→ Raw Input
→ Input Profile
→ Normalized Attributes
→ Validation / Derivation
→ Identity Definition
→ Identity Canonical
→ Code Rule Version
→ Segment Details
→ materialNo
→ Release Package
```

## AC-10 发布版本不可变

已发布 Schema/Identity/Code Rule 不可原地修改；新规则必须创建新版本并通过 Release。

## AC-11 行业无关

至少使用三种结构显著不同的物料类别，例如：

- 玻璃布。
- 轴承。
- 电子元器件。

要求不新增或修改任何行业专用后端业务代码，仅通过配置完成 Schema、Validation、Identity、Code Rule 和发号。

## AC-12 ERP 无关

两个不同 Caller System 使用同一标准 API 契约调用 MDM，MDM Core 不包含调用系统专用判断分支。

## AC-13 原子事务恢复

在 Sequence 已获取但事务尚未提交时模拟进程异常，不得留下部分 Assignment；重启后再次请求能够安全完成。

## AC-14 配置审批

提交人与审批人为不同成员；未发布 Release 不能用于正式发号。

---

# 18 P0 发布门槛

V4.0 P0 上线前必须满足：

1. AC-01 至 AC-14 全部通过。
2. 至少一个真实业务系统完成“创建业务记录 → HTTP 请求 → MDM 发号 → 调用方保存 materialNo”UAT。
3. 三种结构明显不同的 Category 证明无行业硬编码。
4. 100 并发同 Identity 无重复发号。
5. 模拟响应丢失后重试不产生第二个号码。
6. 数据库备份恢复后 Assignment、Sequence、Source Binding 一致。
7. 任一正式 materialNo 的解释链完整。
8. Release 发布、版本 Diff、回归样本与权限审批通过。
9. 安全扫描、日志脱敏、凭据管理满足企业基线。

---

# 19 实施计划与交付物

参考团队：2 名后端 + 1 名前端 + 1 名 QA + 共享产品/架构支持。

|阶段|周次|主要交付|
|---|---:|---|
|M0 架构与 API 契约|1|领域模型、DB 约束、Assignment API、错误模型|
|M1 Category / Schema|1–3|动态 Schema、标准化、Input Profile|
|M2 Validation / Identity|2–5|规则执行、canonical/hash、Source Binding|
|M3 Numbering Core|4–7|Code Rule、Sequence、Preview、正式发号、Ledger、幂等|
|M4 Governance|6–9|Release Package、审批、版本 Diff、审计|
|M5 UI / Explain / Monitor|7–10|管理页面、解释链、监控指标|
|M6 UAT / Hardening|10–12|并发、故障注入、备份恢复、真实系统联调|

目标 10–12 周完成 P0 评审版到生产候选版；实际周期按团队规模和企业安全流程调整。

---

# 20 V3.0 → V4.0 删除 / 保留 / 简化映射

|V3.0 能力|V4.0 处理|说明|
|---|---|---|
|Source System|**简化保留**|改为 Caller System，仅表示调用方身份、鉴权与类别范围|
|JDBC 数据源|**P0 删除**|调用方自行准备数据|
|DDL 导入|**删除**|MDM 不再建模 ERP 表结构|
|JDBC 元数据扫描|**删除**|同上|
|Source Object / Version|**删除**|不再进入 Core|
|Source Dataset|**删除**|HTTP payload 即逻辑输入|
|Root Object|**删除**|由调用方业务记录决定|
|Material Grain|**删除**|调用方负责形成“一次请求代表一个逻辑物料”|
|多表 Join|**删除**|调用方适配层负责|
|增量 Cursor / 扫描|**删除**|无主动拉取|
|候选记录 Filter|**删除**|何时申请料号由调用方决定|
|Dataset Preview|**删除**|改为 Number Preview|
|Category|**保留并强化**|行业无关逻辑类别|
|Schema|**保留并强化**|成为标准输入契约核心|
|Field Mapping|**大幅简化**|改为可选 Input Profile；默认直接使用 Schema 属性代码|
|Validation|**保留**|核心能力|
|Derivation|**保留**|核心能力|
|Material Identity|**保留并强化**|成为产品核心之一|
|Identity Conflict|**保留并重新定义**|以 Source Binding + Identity 唯一策略处理|
|Code Rule|**保留并强化**|核心能力|
|Sequence|**保留**|原子、不回收|
|正式发号|**保留并成为唯一主流程**|HTTP 请求触发|
|Assignment Ledger|**保留并强化**|发号事实权威|
|Material Source Snapshot|**删除/合并**|只保留发号 Input Snapshot，不做持续来源镜像|
|同步状态机|**删除**|替换为极简请求/Assignment 状态|
|主动请求模式|**从备选升级为唯一 P0 主模式**|V4 核心变化|
|轮询模式|**删除**|不进入 P0/P1 默认路线|
|Writeback Adapter|**删除**|调用方自行保存返回号码|
|Writeback Task|**删除**|不再需要|
|Outbox for ERP Writeback|**删除**|不再有 MDM 主动外部写回|
|RESULT_UNKNOWN|**删除**|通过响应丢失后幂等查询/重试解决|
|ERP Reconciliation|**删除**|调用方自己负责业务保存结果；MDM 只保证自身 Assignment|
|Source Structure Impact|**删除**|改为配置版本依赖 Diff|
|Release Package|**保留并简化**|只包含影响身份与发号的配置|
|同步任务页面|**删除**|无扫描任务|
|异常中心|**简化**|聚焦 Validation、Identity、Code、Idempotency 冲突|
|对账中心|**删除**|P0 不需要|
|发号解释|**保留并强化**|从任一 materialNo 追溯完整决策链|
|历史料号解析|**降为 P1**|不阻塞新系统核心发号闭环|
|多租户 / RBAC|**保留**|企业化基础能力|
|审计 / 监控|**保留并简化**|聚焦 API、规则、发号与配置治理|

---

# 21 已锁定架构决策

以下决策作为 V4.0 基线，不再在 P0 评审中反复打开：

1. **ERP/业务系统仍是业务事实源。**
2. **P0 只做 HTTP/API First 主动发号。**
3. **MDM 不主动读取 ERP 数据库。**
4. **MDM 不理解 ERP 多表结构。**
5. **调用方负责将多表业务数据组合成一次完整逻辑请求。**
6. **MDM 不主动写回 ERP；调用方保存返回的 materialNo。**
7. **MDM Core 不绑定任何具体 ERP 厂商或版本。**
8. **Category、Schema、Validation、Derivation、Identity、Code Rule 必须元数据驱动。**
9. **正式号码不可回收、不可静默重编码。**
10. **Assignment Ledger 是发号事实权威。**
11. **网络超时与重试不得产生第二个号码。**
12. **新增物料类别原则上不得修改 Core Java 业务代码。**
13. **Golden Record / Match-Merge 不属于 V4 P0。**
14. **历史料号解析降为 P1，不阻塞核心交付。**

---

# 附录 A 示例：玻璃布

请求：

```json
{
  "callerSystemCode": "ERP_PROD",
  "sourceRecordKey": "100001",
  "categoryCode": "GLASS_CLOTH",
  "attributes": {
    "clothType": "7628",
    "manufacturer": "HH",
    "treatment": "A",
    "basisWeight": 210,
    "width": 1270
  }
}
```

Identity：

```text
manufacturer + clothType + treatment + basisWeight + width
```

Code Rule 示例：

```text
"GC"
+ "-"
+ clothType
+ "-"
+ treatment
+ "-"
+ format(basisWeight)
+ "-"
+ format(width)
+ "-"
+ manufacturerDictionary(manufacturer)
```

结果：

```text
GC-7628-A-210-1270-HH
```

---

# 附录 B 示例：轴承

```json
{
  "callerSystemCode": "ERP_PROD",
  "sourceRecordKey": "B-9988",
  "categoryCode": "BEARING",
  "attributes": {
    "bearingType": "DEEP_GROOVE",
    "innerDiameter": 25,
    "outerDiameter": 52,
    "width": 15,
    "sealType": "2RS",
    "manufacturer": "SKF"
  }
}
```

无需新增轴承专用 Java 类，仅新增配置。

---

# 附录 C 示例：电子元器件

```json
{
  "callerSystemCode": "PLM_PROD",
  "sourceRecordKey": "EC-20260001",
  "categoryCode": "RESISTOR",
  "attributes": {
    "resistance": "10K",
    "tolerance": "1%",
    "power": "0.25W",
    "package": "0603",
    "manufacturer": "YAGEO"
  }
}
```

与玻璃布、轴承共用同一个 Core 流程：

```text
Schema → Normalize → Validate → Identity → Code Rule → Assignment
```

---

# 附录 D 术语

|术语|定义|
|---|---|
|Caller System|调用 MDM 发号 API 的 ERP、PLM、MES 或自研系统|
|Source Record Key|调用系统中业务记录的稳定标识|
|Category|行业无关的逻辑物料类别|
|Schema|Category 的标准属性契约|
|Input Profile|调用方字段到标准 Schema 的轻量映射，可选|
|Material Identity|判断两次输入是否代表同一种物料的规范化业务身份|
|Identity Canonical|Identity 属性按固定顺序与规范化规则生成的完整可比较表示|
|Code Rule|把标准属性、字典、流水等组合成料号的确定性规则|
|Sequence|正式号码使用的原子流水计数器|
|Material Assignment|一个 Material Identity 被正式分配某个 materialNo 的事实|
|Source Binding|调用方业务记录到 Material Assignment 的绑定|
|Assignment Ledger|所有正式发号事实及其规则依据的不可变账本|
|Input Snapshot|发号当时参与标准化、Identity 与 Code Rule 的输入快照|
|Release Package|一起评审、发布并决定正式发号语义的一组配置版本|
|SoR|System of Record，业务事实来源系统|

---

# 结论

V4.0 将产品从 V3.0 的“ERP 数据读取 + 数据集建模 + 发号 + 写回”收敛为一个更清晰、更通用的核心：

> **调用方负责准备事实，MDM 负责判定身份与唯一发号。**

其最小稳定链路为：

```text
Caller System
    ↓ HTTP
Category / Schema
    ↓
Normalize / Validate / Derive
    ↓
Material Identity
    ↓
Code Rule / Sequence
    ↓
Assignment Ledger
    ↓
materialNo
```

只要不同企业和行业能够把自己的业务事实转换为这一标准请求，MDM Core 就无需理解其底层 ERP、数据库表结构或行业专用数据模型，从而实现真正的“行业可配置、ERP 无关、规则集中、号码唯一、历史可解释”。
