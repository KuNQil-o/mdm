# 开发接口说明（等价API文档）

API基址 `/api/v1`，UTF-8 JSON。除开发登录和已登记集成客户端入口外，使用服务端session cookie与 `X-Tenant-Code`。所有写入提供 `Idempotency-Key`，修改或状态流转提供 `If-Match: "rowVersion"`；返回对象的ETag/rowVersion供并发控制。详情还包含独立任务状态，业务请求设置不缓存。`X-Request-ID`传递业务traceId。

幂等范围是tenant/user或已登记客户端/HTTP操作与路径/key，摘要包含body和If-Match。同体重试返回原结果，不同体409。成功结果在PostgreSQL持久化；失败事务不保留成功结果。客户端入站另按system/sourceMessageId事务去重，不信任浏览器切换租户来确定任务归属。

典型错误：401 LOGIN_REQUIRED、403 FORBIDDEN/SELF_APPROVAL、404 NOT_FOUND、409 VERSION_CONFLICT/CODE_CONFLICT/IDEMPOTENCY_CONFLICT、422 VALIDATION_ERROR、428 VERSION_REQUIRED。结构为 `{"code":"…","message":"…","errors":[{"fieldPath":"/attributes/width","code":"UNIT_DIMENSION","message":"…","ruleVersion":1,"suggestion":"…"}],"traceId":"…"}`。

## 接口目录

表内动作均为POST；基础资源的PATCH须提供If-Match。列表只有明确授权租户与类别范围中的记录。

|对象|路径及HTTP方法|说明|
|---|---|---|
|开发入口|`/api/dev/users` GET，`/api/dev/login` POST `{userCode}`，`/api/dev/logout` POST，`/api/dev/seed` POST|仅dev；seed显式可重复|
|当前身份|`/me/tenants`、`/tenants/current` GET|实际成员、动作、类别范围|
|平台租户|`/platform/tenants` GET/POST，`/platform/tenants/{id}` GET/PATCH|创建 `{code,name,adminUserId,settings}`|
|租户状态|`/platform/tenants/{id}/{activate,suspend,resume,archive,reopen}` POST|`{reason}`；归档检查未结束申请/导入/导出/投递|
|平台计数|`/platform/tenants/{id}/overview` GET|实际物料、成员、申请、任务|
|租户设置|`/tenant/settings` GET/PATCH|timezone/pageSize/importLimit/exportLimit/retentionDays/contact/displayUnits/integrationContact|
|成员|`/tenant/users` GET；`/tenant/members` GET/POST；`/tenant/members/{id}` PATCH|`{userId,roles:[…],categoryScope:["*"],active,reason}`；身份不可替换|
|角色|`/tenant/roles` GET/POST；`/tenant/roles/{code}` PATCH|`{code,name,actions:[…]}`|
|类别|`/categories` GET/POST；`/categories/{code}` GET/PATCH/DELETE|稳定code；parentId/name/active/approvalRole/publishRole|
|Schema|`/categories/{code}/schema?versionId=UUID` GET；`/categories/{code}/schemas` GET/POST；`/schemas/{id}` GET/PATCH/DELETE|body `{bundle}`；默认或显式版本；已用发布内容不可改|
|配置发布|`/schemas/{id}/{test,submit,approve,reject,withdraw,retire,reactivate}` POST；`/schemas/{id}/impact` GET|两人审核，样本回归；delete仅未使用的从未发布草稿|
|元数据|`/metadata` GET/POST；`/metadata/{id}` PATCH|`{kind:ATTRIBUTE/DICTIONARY/UNIT,code,data}`；新版本创建；active启停|
|引用|`/references` GET/POST|`{type,id,name,active,data}`；租户私有通用引用|
|规则结论|`/materials:validate`、`/materials:decisions` POST|同一后端规则；decisions部分输入返回可见/必填，validate完整校验|
|编号|`/material-numbers:preview`、`/material-numbers:parse` POST|预览不占号；解析须 `{materialNo,parseRuleVersionId}`，不落物料|
|物料|`/materials` POST(201)；`/materials/{id}` GET/PATCH/DELETE；`/materials/by-no?no=…` GET|draft字段；PATCH缺失保留、null尝试清空；删除仅无申请草稿|
|复制/替代|`/materials/{id}/copy` POST；`/materials/{id}/replacements` POST|复制可编辑属性；替代 `{replacementId,reason}`|
|变更申请|`/materials/{id}/change-requests` POST|`{kind:NEW/CHANGE/DEACTIVATE/REACTIVATE,reason,candidate}`；基础物料If-Match|
|申请|`/requests` GET；`/requests/{id}` GET/PATCH；`/requests/{id}/{submit,approve,reject,withdraw,cancel}` POST|候选在REVIEW冻结；PATCH草稿 `{candidate,reason}`；申请If-Match|
|历史|`/materials/{id}/history` GET；`/materials/{id}/restore` POST|历史快照及当时schema；restore `{version,reason}` 创建新申请|
|查询|`/materials/search` POST|query如下；默认50/max200；动态字段须配置searchable/sortable|
|导出|`/materials/export` POST；`/export-jobs` GET；`/export-jobs/{id}` GET|`{query,format:CSV/XLSX,fields:[…]}`，<=200同步文件；更多后台，冻结截止时间|
|上传|`/import-files` POST multipart(categoryCode,file)；`/import-files/{id}/preview` POST `{sheet}`|CSV/XLSX；20MB/50k/200列，拒绝公式|
|导入|`/import-jobs` GET/POST(202)；`/import-jobs/{id}` GET|config下例；全部source/candidate/errors/行结果持久|
|任务动作|`/import-jobs/{id}/{validate,commit,pause,resume,cancel,error-report}` POST|commit `{onlyPassed:true}`，pause `{reason}`；错误报告返回文件ID|
|修正|`/import-jobs/{id}/rows/{no}` PATCH|`{source:{…}}`、行If-Match；只失败/未处理行|
|集成系统|`/integration-systems` GET/POST；`/integration-systems/{id}` GET/PATCH|`{code,name,direction:BOTH/INBOUND/OUTBOUND,config,clientKey}`；启用前持久契约测试|
|映射|`/integration-systems/{id}/mappings` GET/POST；`/mappings/{id}` GET/PATCH；`/mappings/{id}/{publish,preview}` POST|`{config}`；preview `{input}`；发布后不可改|
|连接测试|`/integration-systems/{id}/test` POST|GET healthUrl + POST contractTestUrl错误样本；不把HTTP200等同业务成功|
|身份|`/integration-systems/{id}/identities` GET/POST|`{materialId,externalId,reason}`；数据库唯一约束|
|会话入站|`/integration-systems/{id}/inbound` POST|开发/人工入站，仍是草稿/申请|
|客户端入站|`/inbound/{systemId}` POST|X-Integration-Key；系统归属由登记记录决定，不需要浏览器租户|
|文件批量|`/integration-files` POST multipart(systemId,file)|UTF-8 JSONL，逐行事务/结果/错误/源文件；重复sourceMessageId不重建|
|批量JSON|`/integration-systems/{id}/file-inbound` POST|`{messages:[…]}`；调用同一Inbox服务，整批事务|
|投递|`/deliveries`、`/deliveries/{id}` GET；`/deliveries/{id}/{resend,confirm}` POST|resend `{reason,confirmedNotCreated?,mappingId?}`；confirm `{success,externalId,reason}`|
|对账|`/integration-systems/{id}/reconcile` POST；`/integration-systems/{id}/reconciliations` GET|实际目标版本/关键字段，含无外部身份项；每日02:00UTC|
|文件|`/files/{id}` GET；`/files/clean` POST|租户+类别+岗位下载；到期且无未完导入才清理内容|
|记录/运行|`/operation-logs?objectId=…&traceId=…`、`/dashboard` GET；`/projections/rebuild` POST|前者范围受限；后者ADMIN，锁定权威物料重建投影|

## 示例

物料创建/校验/预览输入（Schema UUID从已发布类别接口取）：

```json
{"schemaVersionId":"SCHEMA_UUID","categoryCode":"GLASS_CLOTH","materialName":"7628玻璃布","baseUnitCode":"pcs","attributes":{"model":"7628","width":{"value":1270,"unit":"mm"},"basisWeight":{"value":210,"unit":"g/m2"},"manufacturer":{"type":"SUPPLIER","id":"SUP-001"},"certified":false}}
```

DECIMAL在数据库和后端使用BigDecimal；JSON数值对象value/unit。前端用原始JSON十进制token保持物料值精度。LEGACY增加 `numberSource:"LEGACY"`、`legacyNo`、`sourceEvidence`和可选实际 `parseRuleVersionId`，正式生成版本为空，不伪造历史。

查询例：

```json
{"categoryCode":"GLASS_CLOTH","name":"玻璃","status":"ACTIVE","filters":[{"field":"attributes.width","op":"BETWEEN","value":[1.2,1.3],"unit":"m"}],"sort":[{"field":"updatedAt","direction":"DESC"}],"page":{"size":50}}
```

响应items/hasMore/nextCursor/cutoff；下一页带同一query和cursor。游标包含条件摘要、固定截止时间、排序值、UUID；条件变化须从首屏重查。并发修改后变晚于截止时间的物料不会进入后页，历史快照导出要求需要独立报表能力。

导入任务配置例：

```json
{"fileId":"FILE_UUID","categoryCode":"GLASS_CLOTH","schemaVersionId":"SCHEMA_UUID","sheet":"CSV","mode":"CREATE","mapping":{"名称":"materialName","型号":"attributes.model","幅宽":{"target":"attributes.width","unit":"mm"},"克重":{"target":"attributes.basisWeight","unit":"g/m2"},"厂商":"attributes.manufacturer"}}
```

UPDATE必须通过id(UUID)或systemId+externalId解析对象，并映射baseVersion；不按名称/料号猜测。所选类别和 Schema 必须与对象绑定版本一致，否则行报 SCHEMA_CATEGORY / SCHEMA_VERSION_CONFLICT。不同版本对象应分别建立任务；不会通过导入自动升级历史语义。parseRuleVersionId可选，源料号映射legacyNo。预检不创建物料/正式号/Outbox，确认后CREATE草稿、UPDATE变更申请。

错误报告逐条展开错误，包含 rowNo/sourceColumn/targetAttribute/errorCode/originalValue/message/suggestion/state。UTF-8 输入采用严格解码。CSV 输出对可能执行公式的文本加单引号，包括负数源文本；该前缀用于下载安全，暂存源数据保持原值。

发布包包含attributes、单位/字典/属性版本快照、codeRule、parseRule、samples、dataSchema及uiSchema。attributes是结构与业务约束的设计来源，数据Schema由其生成并由NetworkNT执行结构校验；UI Schema可单独指定 `order:["model","width"]` 与 `fields:{"width":{"group":"尺寸","label":"幅宽"}}`。visibleWhen与requiredWhen在后端分别执行；派生AST依赖拓扑排序，客户端不得写入派生值。

入站消息（同一sourceMessageId重试保持整个请求体）：

```json
{"sourceMessageId":"ERP-A-1001","externalId":"1001","categoryCode":"GLASS_CLOTH","sourceVersion":1,"previousPublishedVersion":null,"data":{"materialName":"导入布","attributes":{"model":"IMPORT1001","width":{"value":1270,"unit":"mm"},"basisWeight":{"value":210,"unit":"g/m2"},"manufacturer":{"type":"SUPPLIER","id":"SUP-001"}}}}
```

字段策略在mapping.config.policies（扁平路径，如attributes.description）登记ACCEPT/IGNORE/REJECT/REVIEW。可用 policiesByCategory 按类别覆盖，例如 `{"GLASS_CLOTH":{"attributes.description":"IGNORE"},"BEARING":{"attributes.description":"REVIEW"}}`。已绑定对象使用真实类别，消息类别不符会拒绝；身份、料号、schemaVersionId只能REJECT。接受仍需审批；更新还要平台baseVersion。`WAIT_PREDECESSOR`缓存相同消息，前序补齐后再次提交原体；不同体409。

外部身份首次绑定使用 `{materialId,externalId,reason}`；修改已有绑定必须显式携带 `{materialId,externalId,previousExternalId,baseVersion,reason}`，版本过期或 ID 已占用返回409。变更会递增身份 rowVersion、清空旧确认版本与摘要并记录原因；不会用新增接口静默覆盖。

集成系统 config.minimumIntervalMs 支持0—3600000，默认0。发送任务领取同时锁定目标系统并更新 next_send_at，跨进程限制同目标发送频率。

固定段解析提供 start/length，越界返回422。覆盖全部字符为 EXACT_SYNTAX；额外未解析区间为 PARTIAL_SYNTAX，并返回 unparsedSegments。两者均只说明语法。

Outbox固定事件结构：eventId/tenantId/eventType/aggregate(id,rowVersion)/previousPublishedVersion/schemaVersionId/data/sourceSystem/correlationId/traceId/occurredAt/changedFields。物料、投影、版本、操作记录和事件在一个本地事务内；每目标delivery固定mappingId，补发创建parentId关联的新revision。

REST出站使用Idempotency-Key=eventId并发送：

```json
{"eventId":"EVENT_UUID","tenantId":"TENANT_UUID","materialId":"MATERIAL_UUID","rowVersion":3,"previousPublishedVersion":null,"mappingVersionId":"MAPPING_UUID","data":{"ITEM_CODE":"GC-7628-1270-HONGHE","ITEM_NAME":"玻璃布"}}
```

默认成功判定body.success=true及非空externalId；可配置successPath/successValue/externalIdPath。202轮询queryUrl；超时先查询或凭已声明幂等能力核对。HTTP200业务失败仍FAILED。FILE transport生成JSONL，每个文件批次包含一个固定事件与映射输出，WAIT_CONFIRMATION直到人工记录文件回执。

模拟器场景：POST `/scenarios/{target}` body `{mode:"SUCCESS/BUSINESS_FAIL/RATE_LIMIT/SERVER_ERROR/LOST_RESPONSE/ACCEPTED/UNKNOWN",remaining:1}`；GET `/targets/{target}/materials`查看真实保存记录，GET事件/身份查询用于确认/对账；POST `/targets/{target}/validate`验证错误契约。`remaining:-1`持续场景，1只影响一次。
