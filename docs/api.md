# V3 API 与配置约定

业务前缀 `/api/v1`。人类身份由服务端 session 确定；仅 dev profile 有 `/api/dev/login`。读写携带 `X-Tenant-Code`；修改携带 `Idempotency-Key`，草稿编辑/治理动作携带 `If-Match`。`X-Request-ID` 用于追踪。ERP 调用使用绑定来源和租户的 `X-Source-Key`，只能预览/发号自己的 Dataset。

|资源|主要操作|
|---|---|
|`/source-systems`|GET / POST；`/{id}` PATCH；`/{id}/test`、`/{id}/discover` POST；`/{id}/objects` GET|
|`/source-objects:import-ddl`|POST `{sourceSystemId,ddl}`，仅解析文本|
|`/source-objects`|POST 手工/API 结构契约；`/{id}` GET；`/{id}/diff?against=UUID`、`/{id}/alerts`、`/{id}/join-suggestions` GET|
|`/source-datasets`、`/field-mappings`、`/identity-definitions`、`/code-rules`|GET / POST 新版本；`/{id}` PATCH 草稿；发布内容不可改|
|`/source-datasets/{id}/preview`|POST `{sourceRecordKey}`，只读真实来源|
|`/source-datasets/{id}/scan`|POST 创建持久扫描任务|
|`/source-datasets/{id}/runtime`|PATCH `{enabled,intervalSeconds}`，运行参数与业务版本分离|
|`/releases`|POST 固定 Dataset/Schema/Mapping/Identity/Code Rule UUID 与样本/回写契约|
|`/releases/{id}/test`、`submit`、`approve`、`withdraw`、`reject`、`retire`、`set-default`|POST；双人发布，回退不改历史台账|
|`/releases/{id}/impact`|GET，当前源输入在新版本下的影响分析，不写账|
|`/material-numbers:preview`、`:assign`|POST `{releaseId 或 datasetCode,sourceRecordKey}`；preview 无占号；人工 assign 必须由来源明确允许且读取真实 ERP|
|`/material-numbers:parse`|POST `{codeRuleVersionId 或 codeRuleVersionIds,materialNo}`；必须显式版本，多匹配拒绝；解析不证明业务真实性|
|`/assignment-ledger`|GET；`/{id}` / `/{id}/explain` GET；无编辑/删除能力|
|`/assignment-ledger/search`|POST 类别、已发布 searchable 属性及精确条件，参数绑定查询|
|`/scan-jobs`、`/processing-tasks`|GET；扫描 `/{id}/run`、处理任务 `/{id}/process`、`/{id}/retry` POST|
|`/writeback-tasks`|GET；`/{id}/process`、`retry`、`rebuild` POST；人工处理须原因，未知结果先查询|
|`/reconciliations`|POST `{releaseId}`；查询差异，不静默覆盖 ERP|
|`/v3/overview`、`/v3/monitoring`|GET，按租户及来源范围展示任务/连接/延迟/错误/指标|

来源对象字段具有类型与约束。Dataset 声明 Root 对象版本、稳定键、`ONE_ROOT_ROW_ONE_MATERIAL`、1:1/N:1 等值关联、LEFT/INNER、未命中策略、output、existingNo/sourceVersion/candidateFilter/incremental。组合来源键使用 JSON 数组字符串。任意 SQL 不接受；复杂逻辑可由 ERP 只读 View 提供。

Mapping.fields 可配置 source、constant、expression、lookup 字典、format 和 unitSource。跨 Dataset 属性展开由 `lookups:[{datasetVersionId,keySource,projection:{输出名称:引用Dataset输出名称}}]` 指定；依赖随发布固定，不由类别代码决定。

ERP REFERENCE 属性声明 `referenceSource:{datasetVersionId,activeField,activeValue,requireIssuedNumber}`，读取 ERP 判断有效性。纯配置字典引用可显式声明 `referenceTargetKind:"METADATA_CATALOG"`。业务物料事实不以 MDM 配置目录替代。

Code Rule.segments 支持 CONST/ATTR/LOOKUP/SEQUENCE/PERIOD。每个正样本必须声明独立 `expectedMaterialNo`，含流水时使用 `{流水:6}` 等占位；负样本声明 `valid:false,errorCode`。发布检查覆盖率、类型、依赖、样本 Identity/料号冲突及 ERP 契约，提交人不能自行批准。

回写定义包含 `baseUrl,path,queryPath,conditionalEmptyWrite,supportsIdempotency,contractPath,businessValidationPath`；最后两个分别是能力查询及无副作用的业务验证端点，开发 ERP 为 `/contract`、`/contract/validate`。生产适配器需提供等价契约。POST 空验证输入必须得到预期业务失败，HTTP 200 不作为业务成功依据。已发布任务保留适配器快照，重建使用新发布版本、revision/parentId，沿用原正式号和稳定幂等键。

发号 Ledger 固定来源、版本、规范属性、原始输入、canonical/hash、segment 解释和请求。流水、Ledger、Task、Outbox、日志原子提交。完整 canonical 为身份判断依据，hash 碰撞槽只定位，不能把 hash 相同当成业务相同。

错误格式 `{code,message,errors:[{fieldPath,code,message,sourceRecordKey,sourceValue,suggestion}],traceId}`。典型错误为 SOURCE_INCOMPLETE、GRAIN_CONFLICT、VALIDATION_ERROR、IDENTITY_CONFLICT、CODE_CONFLICT、IDENTITY_CHANGED_AFTER_ISSUE、ERP_VALUE_CONFLICT。回写状态：PENDING / SENDING / WAIT_CONFIRMATION / RETRY_WAIT / RESULT_UNKNOWN / SUCCEEDED / FAILED。

搜索普通条件支持 `materialNo,sourceSystemId,sourceRecordKey,categoryCode,datasetCode,issuedFrom,issuedTo,writebackStatus,errorCode`，属性 `filters:[{field,op,value}]` 使用 eq/ne/gt/gte/lt/lte/exists 和已发布类型；`pageSize≤200`。P95 指标为当前进程最近最多 2000 样本，重启后重采样；数据库锁等待是共享平台库范围。
