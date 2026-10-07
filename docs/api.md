# V4 API 与配置

所有业务 API 以 `/api/v1` 为前缀。租户由 `X-Tenant-Code` 明确指定，服务端不从 payload 信任 tenantId。普通成员使用 HttpOnly / SameSite=Strict 会话；调用方使用 `X-Caller-Key` 或 `Authorization: Bearer`。API Key 由平台随机生成，业务表只保存 SHA-256 哈希，创建和轮换时显示一次。

## 发号、预览和检索

|方法|路径|用途|
|---|---|---|
|POST|`/material-number-assignments`|正式原子发号；必须使用 Caller 凭据和 Idempotency-Key|
|POST|`/material-number-previews`|标准化、校验、Identity 和 Segment 预览；不占号|
|GET|`/material-number-assignments`|分页检索；按 categoryCode、materialNo、callerSystemCode、sourceRecordKey、identityHash、identityCanonical、from/to|
|GET|`/material-number-assignments/{id}`|永久账本及完整解释|
|GET|`/material-number-assignments/{id}/explain`|同上|
|GET|`/material-number-assignments/by-no/{materialNo}`|按料号解释|
|GET|`/material-number-assignments/by-source/{callerCode}/{sourceKey}?categoryCode=...`|按来源记录查询；跨类别同来源键时须明确类别|

查询默认 limit=50，最大 200；时间使用带时区的 ISO 8601。路径参数必须 URL 编码；料号或来源键包含斜杠时优先使用列表查询参数。Caller 只能查询其已绑定且在当前授权类别内的 Assignment。

正式输入仅允许 `callerSystemCode`、`sourceRecordKey`、`categoryCode`、`attributes`。不接受指定历史 releaseId 或强制重编码参数。预览可额外使用 `releasePackageVersionId`；草稿/待审版本仅向有设计权限的成员开放。

请求最大 1MB，属性快照最大 200KB。源记录键是原样保留的稳定、不透明字符串（1–1000 字符），平台不会把它与物料 Identity 混淆。

## 精确数值和 Identity

INTEGER / DECIMAL 属性可用 JSON 数字、十进制字符串或 `{ "value": "127", "unit": "CM" }`。简单数字和字符串默认按 Schema 标准单位解释。后端从原始 JSON 直接解析 Decimal，保持十进制定点语义；超过允许精度、不精确换算和丢失精度的格式化会被阻止。前端样本中的原始数值 token 转为字符串传输，以避免浏览器浮点精度损失。

Identity canonical 使用**有序 `[属性代码,规范化值]` 数组的无空白 JSON**，而不是容易产生分隔符碰撞的裸字符串拼接。单位、稳定枚举/引用代码、字段顺序、空值策略、Trim、大小写和数值 scale 均在已发布配置中冻结。Hash 用于选择索引桶，仍比较完整 canonical；不同 canonical 即使 hash 碰撞也可以使用不同 collision_index，不会被错误复用。数据库触发器再次校验哈希和完整身份唯一性。

来源重试使用**最初发号的发布快照**重新判定身份；后续发布不能改变历史来源绑定或解释。非 Identity 属性改变时返回原号码，也不会覆盖账本快照。

Idempotency-Key 以 tenant + caller 为作用域。请求语义指排序后的完整请求 JSON，数值等价拼写如 `1` 和 `1.0` 被归一化；属性文本变化仍视作不同请求，建议原样保存原请求供网络重试使用。

## 配置管理

以下资源均支持 GET / POST 和 `PATCH /{id}`：

`/categories`、`/schemas`、`/input-profiles`、`/validation-rules`、`/derivation-rules`、`/identity-definitions`、`/code-rules`、`/reference-data`。

创建请求：

```json
{"code":"WIDTH_SCHEMA","categoryCode":"MY_CATEGORY","definition":{"attributes":[
  {"attributeCode":"width","name":"幅宽","type":"DECIMAL","required":true,"precision":20,"scale":3,"min":100,"max":3000,"unit":"MM","searchable":true,"displayOrder":1}
],"units":{"MM":{"dimension":"LENGTH","factor":1},"CM":{"dimension":"LENGTH","factor":10}}}}
```

Category 的 code 即 categoryCode；definition 中包含 name、description、identityReusePolicy、numberingPolicy。复用策略为 `REUSE_EXISTING` / `REVIEW_ON_DUPLICATE`，P0 编号策略为 `NUMBER_ONCE`。Category 的暂停/恢复属于可审计的运行开关：`POST /categories/{versionId}/suspend|activate` 使用返回的 availabilityVersion 作为 If-Match。

同 kind + code 再次 POST 创建下一版本，不覆盖旧版本。PATCH 只接受 definition，必须携带 `If-Match: "rowVersion"`；缺失为 428，冲突为 409。已提交配置冻结，已发布配置永久不可修改。`GET /{resource}/{id}/diff?against={previousId}` 比较同一配置的定义；Release 的 tests.diff 展示整个组合的语义差异。

Schema 支持 STRING、INTEGER、DECIMAL、BOOLEAN、DATE、ENUM、REFERENCE。文本默认 trim、空白转 null；case 可为 PRESERVE / UPPER / LOWER，default 为明确配置的常量。枚举/引用可内联 options（code / name / aliases / active），或用 enumDictionaryVersionId / referenceDictionaryVersionId 依赖版本化字典。引用使用稳定 code；接受字符串和 `{type,id}` / `{code}`，不进行远程引用查询。

字典 definition.entries 含稳定 code、name、aliases、numberCode、active。引用到的字典必须显式加入发布包 dictionaryVersionIds。发布后修改字典必须创建新版本。

Input Profile：

```json
{"callerSystemCode":"ERP_A","fields":[
 {"source":"vendor","target":"manufacturer","trim":true,"case":"UPPER"},
 {"constant":"A","target":"treatment"},
 {"source":"kind","target":"clothType","map":{"C7628":"7628"}}
]}
```

只支持平面字段别名、常量、字典、Trim/Case、prefix/suffix；同时提交同一目标的标准字段和别名会被拒绝。不能映射派生字段；不支持 SQL、Join、脚本、JSONPath 或外部查询。

Validation：`{ "rules": [{ "field":"width", "severity":"ERROR", "assert":{ "op":"gte", "args":[{"field":"width"},100] }, "message":"幅宽至少100" }] }`。

Derivation：`{ "attributes":[{ "field":"sizeClass", "expression":{ "op":"if", "args":[{"op":"gte","args":[{"field":"width"},1000]},"WIDE","NORMAL"] } }] }`。目标属性必须已存在于 Schema；依赖必须构成 DAG。客户端提交目标字段会返回 DERIVED_READONLY。

受控表达式支持 exists、eq/ne、gt/gte/lt/lte、in/not_in、and/or/not、add/sub/mul/div、concat、coalesce、if、length、matches、convert、upper/lower/trim。数组常量使用 `{literal:[...]}`。每个表达式限制 200 节点、20 层深度。模式只支持基础正则子集，禁止分组、回溯引用和多重复算子。

Code Rule Segment 支持 CONSTANT、ATTRIBUTE、DICTIONARY、REFERENCE_CODE、NUMBER_FORMAT、UNIT_FORMAT、SEQUENCE、SEPARATOR、PERIOD。支持规则 separator、maxLength（最大128）、allowedPattern、case；Segment 支持 padding/padChar、nullPolicy（ERROR / EMPTY / DEFAULT）、scale、unit。SEQUENCE 按 tenant + codeRuleVersionId + name + periodKey 原子递增，周期为 NEVER / DAILY / MONTHLY / YEARLY；周期流水必须同时编码同一时区、同一周期的 PERIOD 段。

**新 Code Rule Version 有独立流水作用域。** 新规则保留旧前缀并从 1 开始时可能与既有正式号码冲突；平台返回 CODE_CONFLICT。设计员应在发布的新编码规则中保留足够区分信息，不得借运行参数修改计数器或回收旧号码。

## 发布包与审批

创建 `/release-packages`：

```json
{
 "code":"MY_RELEASE","categoryCode":"MY_CATEGORY",
 "refs":{"categoryVersionId":"...","schemaVersionId":"...","identityDefinitionVersionId":"...","codeRuleVersionId":"...",
 "inputProfileVersionIds":[],"validationRuleVersionIds":[],"derivationRuleVersionIds":[],"dictionaryVersionIds":[]},
 "samples":[{"callerSystemCode":"ERP_A","attributes":{"width":1270},"expectedMaterialNo":"MT-{SEQUENCE:MAIN:6}"}]
}
```

至少一个有效正向样本；失败样本可用 expectedError 指定预期码，重复 Identity 样本必须显式 allowDuplicateIdentity。可附 expectedIdentityCanonical 和 expectedMaterialNo。

`POST /release-packages/{id}/test|submit|publish|reject|retire` 都必须提供 If-Match。提交重新编译、跑样本、计算版本 Diff 并冻结快照；审批前再次检查依赖与回归。提交人和最终发布人必须不同。发布新版本会在同一事务中退役旧当前版本；数据库保证每类别至多一个 PUBLISHED Release。正式请求无法指定草稿或任意历史版本。

## 错误

统一格式为 `{requestId,code,message,details:[]}`。字段错误含 fieldPath、errorCode/code、sourceValue、normalizedValue、ruleVersion、suggestion。400 请求格式；401/403 鉴权授权；404 不存在；409 身份、来源、幂等、编码或版本冲突；422 校验/派生/配置；429 限流；503 临时数据库错误。只有成功的发号事务持久化 Idempotency Record；临时失败可用相同请求和幂等键重试。

## 生产签名与防重放

生产模式强制 HTTPS 和 Caller 签名；开发模式可选，提交签名后同样校验。headers：X-Timestamp（Unix 秒）、X-Nonce（16–100字符）、X-Signature。

签名为以 Caller API Key 作密钥的 HMAC-SHA256 十六进制值，消息按以下五项用换行符连接：

```text
X-Timestamp
X-Nonce
HTTP method（大写）
/api/v1/实际路径（存在 query 时含 ?query）
SHA-256（原始 UTF-8 HTTP body）
```

允许 5 分钟时间窗口；nonce 按 tenant + caller 唯一，重放返回 REPLAY_REJECTED。网络重试生成新 timestamp / nonce / signature，保持原 payload、sourceRecordKey 和 Idempotency-Key。参考 `scripts/caller_example.py` 的签名实现。

请求关联：JSON对象响应与响应头同时提供 requestId / traceId（X-Request-ID / X-Trace-ID）。traceId 为有效 requestId 的 SHA-256 前32个十六进制字符，作为单个请求的追踪根；同一请求贯穿事务审计和指标，永久账本保留 issueRequestId / issueTraceId。重试有自身追踪根，并通过 assignmentId 关联原发号，不实现跨服务 W3C span 传播。
