# V3 测试覆盖矩阵

PRD V3.0 为唯一基线；测试用例 V1.0 的 165 条用例：P0 160/160 通过，P1 4/5 通过。均为开发 ERP 验证；真实 ERP UAT 和生产发布门槛未签收。

|用例|优先级|场景|结果|实际证据|
|---|---|---|---|---|
|CORE-001|P0|新增类别不修改代码|通过（开发）|HTTP：七个玻璃布业务属性从七个关联展开并按Golden发号|
|CORE-002|P0|新增完全不同类别|通过（开发）|HTTP：铜箔共用引擎，基重与二维尺寸按配置编码|
|CORE-003|P0|Schema 必填校验|通过（开发）|HTTP：必填null错误含字段路径|
|CORE-004|P0|非编码属性缺失|通过（开发）|HTTP：非编码字段缺失或备注变更保持原号|
|CORE-005|P0|字典字段映射|通过（开发）|HTTP：编码明确使用厂商业务码而不是数据库ID|
|CORE-006|P0|数值标准化|通过（开发）|HTTP：127 cm 与1270 mm标准化后同Identity|
|CORE-007|P0|Identity 重复|通过（开发）|HTTP：不同ERP键的相同完整Identity不重复发号|
|CORE-008|P0|Source Record 不同但 Material Identity 相同|通过（开发）|HTTP：不同ERP键的相同完整Identity不重复发号|
|CORE-009|P0|同 Source Record 重复申请|通过（开发）|HTTP：来源重复与相同请求幂等返回原号|
|CORE-010|P0|并发发号|通过（开发）|HTTP：100并发不同Identity正式发号唯一|
|CORE-011|P0|并发相同 Identity|通过（开发）|HTTP：100并发不同来源键同Identity最多一个台账|
|CORE-012|P0|流水规则|通过（开发）|HTTP：单字符流水容量耗尽拒绝，不回收已发编号|
|CORE-013|P0|无流水规则冲突|通过（开发）|HTTP：不同Identity同码返回冲突，不加后缀|
|CORE-014|P0|规则缺失|通过（开发）|HTTP：无有效Code Rule无法发布或正式发号|
|CORE-015|P0|Mapping 缺失|通过（开发）|HTTP：缺必填属性映射拒绝发布|
|CORE-016|P0|Reference 失效|通过（开发）|HTTP：停用参考值阻止新号，历史解释保留|
|CORE-017|P0|描述字段不参与编码|通过（开发）|HTTP：非编码字段缺失或备注变更保持原号|
|CORE-018|P0|已发号后修改 Identity 属性|通过（开发）|HTTP：已发号后身份变化进入异常，不重编码|
|CORE-019|P0|已发号后修改普通属性|通过（开发）|HTTP：非编码字段缺失或备注变更保持原号|
|CORE-020|P0|规则版本升级|通过（开发）|HTTP：新版规则服务新记录，旧发号及解析永久绑定旧版|
|CORE-021|P0|Schema 版本升级|通过（开发）|HTTP：新增必填字段的Schema升级：旧记录固定v1，新记录严格v2|
|CORE-022|P0|料号预览|通过（开发）|HTTP：预览返回明确分段而不正式占号|
|CORE-023|P0|正式生成|通过（开发）|HTTP：原子台账与完整版本/分段/trace解释|
|CORE-024|P0|重试|通过（开发）|HTTP：来源重复与相同请求幂等返回原号|
|CORE-025|P0|审计追踪|通过（开发）|HTTP：原子台账与完整版本/分段/trace解释|
|SRC-001|P0|导入单表 DDL|通过（开发）|HTTP：DDL仅形成元数据，FK建议需确认，不创建ERP表|
|SRC-002|P0|导入多表 DDL|通过（开发）|HTTP：DDL仅形成元数据，FK建议需确认，不创建ERP表|
|SRC-003|P0|外键识别|通过（开发）|HTTP：DDL仅形成元数据，FK建议需确认，不创建ERP表|
|SRC-004|P0|无外键表|通过（开发）|HTTP：无主表FK仍可显式关联|
|SRC-005|P0|DDL 变化|通过（开发）|HTTP：结构新版本显示依赖影响且不篡改历史|
|SRC-006|P0|删除已映射字段|通过（开发）|HTTP：独立ERP View与Join等价，删除已映射字段生成依赖告警并阻止读取|
|SRC-007|P1|JDBC 元数据导入|通过（开发）|HTTP：独立ERP View与Join等价，删除已映射字段生成依赖告警并阻止读取|
|DS-001|P0|单表 Dataset|通过（开发）|HTTP：无Join单表API Dataset成功形成逻辑输入|
|DS-002|P0|多表 N:1 Join|通过（开发）|HTTP：七个玻璃布业务属性从七个关联展开并按Golden发号|
|DS-003|P0|Join 缺失|通过（开发）|HTTP：缺失来源厂商定位Join路径|
|DS-004|P0|Join 重复|通过（开发）|HTTP：真实重复业务键Join检测粒度冲突，绝不取第一行|
|DS-005|P0|1:N 导致粒度扩张|通过（开发）|HTTP：明确声明1:N粒度扩张拒绝|
|DS-006|P0|明确 Material Grain|通过（开发）|HTTP：七个玻璃布业务属性从七个关联展开并按Golden发号|
|DS-007|P0|多层 Join|通过（开发）|HTTP：七个玻璃布业务属性从七个关联展开并按Golden发号|
|DS-008|P0|Join 字段类型不一致|通过（开发）|HTTP：关联字符与数字字段明确拒绝|
|DS-009|P1|ERP View 模式|通过（开发）|HTTP：独立ERP View与Join等价，删除已映射字段生成依赖告警并阻止读取|
|DS-010|P1|API Source|通过（开发）|HTTP：API Dataset和成胶按明确Parse版本共用同一引擎|
|GC-001|P0|标准玻璃布发号|通过（开发）|HTTP：七个玻璃布业务属性从七个关联展开并按Golden发号|
|GC-002|P0|主表+多参考表取值|通过（开发）|HTTP：七个玻璃布业务属性从七个关联展开并按Golden发号|
|GC-003|P0|厂商编码参与料号|通过（开发）|HTTP：七个玻璃布业务属性从七个关联展开并按Golden发号|
|GC-004|P0|布种代码来自基重表|通过（开发）|HTTP：七个玻璃布业务属性从七个关联展开并按Golden发号|
|GC-005|P0|表面处理变化|通过（开发）|HTTP：GC 属性变化区分Identity和编码|
|GC-006|P0|幅宽变化|通过（开发）|HTTP：GC 属性变化区分Identity和编码|
|GC-007|P0|等级变化|通过（开发）|HTTP：GC 属性变化区分Identity和编码|
|GC-008|P0|厂商记录缺失|通过（开发）|HTTP：缺失来源厂商定位Join路径|
|GC-009|P0|幅宽 reference 停用|通过（开发）|HTTP：停用参考值阻止新号，历史解释保留|
|GC-010|P0|完全相同规格重复申请|通过（开发）|HTTP：不同ERP键的相同完整Identity不重复发号|
|GC-011|P0|多表 Join 重复|通过（开发）|HTTP：真实重复业务键Join检测粒度冲突，绝不取第一行|
|GC-012|P0|规则升级|通过（开发）|HTTP：新版规则服务新记录，旧发号及解析永久绑定旧版|
|CHEM-001|P0|完整字段生成料号|通过（开发）|HTTP：CHEM 独立Golden编码|
|CHEM-002|P0|包装方式不同|通过（开发）|HTTP：CHEM 属性变化区分Identity和编码|
|CHEM-003|P0|厂商不同|通过（开发）|HTTP：CHEM 属性变化区分Identity和编码|
|CHEM-004|P0|等级不同|通过（开发）|HTTP：CHEM 属性变化区分Identity和编码|
|CHEM-005|P0|分类与型号不合法组合|通过（开发）|HTTP：分类和型号组合不合法|
|CHEM-006|P0|厂商字典停用|通过（开发）|HTTP：字典项停用发布后不能用于新号|
|CHEM-007|P0|描述自动派生|通过（开发）|HTTP：描述由属性派生且不属于Identity|
|CF-001|P0|标准铜箔生成|通过（开发）|HTTP：铜箔共用引擎，基重与二维尺寸按配置编码|
|CF-002|P0|基重/厚度映射|通过（开发）|HTTP：铜箔共用引擎，基重与二维尺寸按配置编码|
|CF-003|P0|表面处理改变|通过（开发）|HTTP：CF 属性变化区分Identity和编码|
|CF-004|P0|颜色改变|通过（开发）|HTTP：CF 属性变化区分Identity和编码|
|CF-005|P0|尺寸由二维数据组成|通过（开发）|HTTP：铜箔共用引擎，基重与二维尺寸按配置编码|
|CF-006|P0|相同尺寸不同显示描述|通过（开发）|HTTP：二维尺寸描述变化不影响Identity|
|CF-007|P0|等级非法|通过（开发）|HTTP：非法等级拒绝|
|CF-008|P0|组合冲突|通过（开发）|HTTP：不同Identity同码返回冲突，不加后缀|
|PP-001|P0|标准压片发号|通过（开发）|HTTP：PP 独立Golden编码|
|PP-002|P0|跨类别引用玻璃布属性|通过（开发）|HTTP：PP 独立Golden编码<br>HTTP：半固化片玻璃属性由固定Dataset读取ERP玻璃布并展开|
|PP-003|P0|压片特性规格|通过（开发）|HTTP：PP 独立Golden编码|
|PP-004|P0|引用成胶料号|通过（开发）|HTTP：PP 独立Golden编码|
|PP-005|P0|流水码参与规则|通过（开发）|HTTP：PP 独立Golden编码|
|PP-006|P0|尺寸变化|通过（开发）|HTTP：PP 属性变化区分Identity和编码|
|PP-007|P0|下游引用对象失效|通过（开发）|HTTP：失效ERP引用阻止新发号，恢复修改ERP事实源|
|FILM-001|P0|使用尺寸|通过（开发）|HTTP：FILM 独立Golden编码|
|FILM-002|P0|使用裁切|通过（开发）|HTTP：FILM 尺寸/裁切严格XOR|
|FILM-003|P0|两者都有|通过（开发）|HTTP：FILM 尺寸/裁切严格XOR|
|FILM-004|P0|两者都无|通过（开发）|HTTP：FILM 尺寸/裁切严格XOR|
|FILM-005|P0|产品规格 R/C|通过（开发）|HTTP：FILM 独立Golden编码|
|FILM-006|P0|生产制程变化|通过（开发）|HTTP：FILM 属性变化区分Identity和编码|
|FILM-007|P0|流水码|通过（开发）|HTTP：FILM 独立Golden编码|
|FILM-008|P0|相同业务 Identity 重复|通过（开发）|HTTP：不同ERP键的相同完整Identity不重复发号|
|BOARD-001|P0|标准基板生成|通过（开发）|HTTP：BOARD 独立Golden编码|
|BOARD-002|P0|厚度代码|通过（开发）|HTTP：BOARD 独立Golden编码|
|BOARD-003|P0|基板叠构|通过（开发）|HTTP：BOARD 属性变化区分Identity和编码|
|BOARD-004|P0|铜箔类型|通过（开发）|HTTP：BOARD 独立Golden编码|
|BOARD-005|P0|尺寸模式|通过（开发）|HTTP：BOARD 独立Golden编码|
|BOARD-006|P0|裁切模式|通过（开发）|HTTP：BOARD 尺寸/裁切严格XOR|
|BOARD-007|P0|尺寸和裁切同时存在|通过（开发）|HTTP：BOARD 尺寸/裁切严格XOR|
|BOARD-008|P0|两者均不存在|通过（开发）|HTTP：BOARD 尺寸/裁切严格XOR|
|BOARD-009|P0|流水码并发|通过（开发）|HTTP：100并发基板XOR尺寸模式按流水唯一|
|RESIN-001|P0|显式配置生成规则|通过（开发）|HTTP：RESIN 独立Golden编码|
|RESIN-002|P0|未配置生成规则|通过（开发）|HTTP：无有效Code Rule无法发布或正式发号|
|RESIN-003|P1|料号反向解析|通过（开发）|HTTP：API Dataset和成胶按明确Parse版本共用同一引擎|
|MAT-001|P0|中/小类别组合|通过（开发）|HTTP：MAT 独立Golden编码|
|MAT-002|P0|规格参与编码|通过（开发）|HTTP：MAT 属性变化区分Identity和编码|
|MAT-003|P0|状态字段|通过（开发）|HTTP：非编码状态变更不重编号|
|MAT-004|P0|流水号|通过（开发）|HTTP：MAT 独立Golden编码|
|MAT-005|P0|单位|通过（开发）|HTTP：单位量纲错误拒绝|
|CONS-001|P0|中/小类别|通过（开发）|HTTP：CONS 独立Golden编码|
|CONS-002|P0|非法小类别|通过（开发）|HTTP：非法中小类别组合拒绝|
|CONS-003|P0|领用方式|通过（开发）|HTTP：CONS 属性变化区分Identity和编码|
|CONS-004|P0|流水号|通过（开发）|HTTP：CONS 独立Golden编码|
|CONS-005|P0|重复业务组合|通过（开发）|HTTP：不同ERP键的相同完整Identity不重复发号|
|PARSE-001|P0|合法玻璃布料号|通过（开发）|HTTP：GC 明确版本反解析与生成输入一致|
|PARSE-002|P0|合法铜箔料号|通过（开发）|HTTP：CF 明确版本反解析与生成输入一致|
|PARSE-003|P0|合法压片料号|通过（开发）|HTTP：PP 明确版本反解析与生成输入一致|
|PARSE-004|P0|合法胶片料号|通过（开发）|HTTP：FILM 明确版本反解析与生成输入一致|
|PARSE-005|P0|合法基板料号|通过（开发）|HTTP：BOARD 明确版本反解析与生成输入一致|
|PARSE-006|P0|未知格式|通过（开发）|HTTP：未知料号格式明确失败|
|PARSE-007|P0|多规则都可匹配|通过（开发）|HTTP：两个明确指定解析版本均匹配时拒绝猜测|
|PARSE-008|P0|历史规则|通过（开发）|HTTP：新版规则服务新记录，旧发号及解析永久绑定旧版|
|PARSE-009|P0|Parse 与 Generate 一致性|通过（开发）|HTTP：GC 明确版本反解析与生成输入一致<br>HTTP：CF 明确版本反解析与生成输入一致<br>HTTP：PP 明确版本反解析与生成输入一致<br>HTTP：FILM 明确版本反解析与生成输入一致<br>HTTP：BOARD 明确版本反解析与生成输入一致|
|INT-001|P0|ERP 请求 MDM 发号|通过（开发）|HTTP：ERP来源客户端申请正式料号|
|INT-002|P0|ERP 保存成功|通过（开发）|HTTP：真实ERP回写当前值确认，重复不会新发号|
|INT-003|P0|ERP 保存失败|通过（开发）|JUnit：WritebackTest.businessFailureIsNotSuccess|
|INT-004|P0|ERP 超时重试|通过（开发）|HTTP：来源重复与相同请求幂等返回原号|
|INT-005|P0|ERP 已有相同料号|通过（开发）|HTTP：真实ERP回写当前值确认，重复不会新发号|
|INT-006|P0|ERP 已有不同非空料号|通过（开发）|JUnit：WritebackTest.currentERPConflictNeverOverwritten|
|INT-007|P0|回写接口 5xx|通过（开发）|JUnit：WritebackTest.retryPreservesAssignmentAndNumber|
|INT-008|P0|回写结果未知|通过（开发）|JUnit：WritebackTest.unknownWithoutIdempotencyCannotBlindRetry|
|INT-009|P0|料号进入库存件|通过（开发）|HTTP：下游库存/BOM/工艺在ERP保存，变更不影响台账|
|INT-010|P0|BOM 按料号查询|通过（开发）|HTTP：下游库存/BOM/工艺在ERP保存，变更不影响台账|
|INT-011|P0|工艺路线按零件号查询|通过（开发）|HTTP：下游库存/BOM/工艺在ERP保存，变更不影响台账|
|INT-012|P0|MDM 不维护 BOM|通过（开发）|HTTP：下游库存/BOM/工艺在ERP保存，变更不影响台账|
|INT-013|P0|MDM 不维护工艺|通过（开发）|HTTP：下游库存/BOM/工艺在ERP保存，变更不影响台账|
|CFG-001|P0|Dataset 草稿|通过（开发）|HTTP：可编辑草稿不会影响默认生产版本|
|CFG-002|P0|Mapping 发布|通过（开发）|HTTP：已发布Mapping不可原地篡改|
|CFG-003|P0|Identity Rule 发布|通过（开发）|HTTP：没有样本不能启用Identity/组合版本|
|CFG-004|P0|Code Rule 发布|通过（开发）|HTTP：字符集非法的Golden阻止发布|
|CFG-005|P0|规则回归|通过（开发）|HTTP：独立Golden不符合预期时阻止发布|
|CFG-006|P0|新规则不兼容旧数据|通过（开发）|HTTP：结构新版本显示依赖影响且不篡改历史|
|CFG-007|P0|回滚默认规则|通过（开发）|HTTP：默认组合版本回退，已发号新旧版本均不改写|
|CFG-008|P0|配置审批|通过（开发）|HTTP：同时有设计和发布岗位也不能自我审批|
|ERR-001|P0|必填字段 null|通过（开发）|HTTP：必填null错误含字段路径|
|ERR-002|P0|字符超长|通过（开发）|HTTP：超长文本拒绝，绝不截断|
|ERR-003|P0|非法字符|通过（开发）|HTTP：字符集非法的Golden阻止发布|
|ERR-004|P0|数值精度溢出|通过（开发）|HTTP：超出数值精度拒绝，绝不四舍五入|
|ERR-005|P0|单位量纲错误|通过（开发）|HTTP：单位量纲错误拒绝|
|ERR-006|P0|规则除零|通过（开发）|HTTP：业务除零失败，不产生正式号|
|ERR-007|P0|派生循环依赖|通过（开发）|HTTP：派生循环在配置阶段阻止|
|ERR-008|P0|未知字段|通过（开发）|HTTP：源API未登记字段拒绝|
|ERR-009|P0|reference 跨租户|通过（开发）|HTTP：跨租户Reference所有权明确拒绝|
|ERR-010|P0|Dataset 无 Root Record Key|通过（开发）|HTTP：Root来源键为空禁止保存|
|ERR-011|P0|Identity 为空|通过（开发）|HTTP：空Identity拒绝发布|
|ERR-012|P0|Code Rule 输出超过最大长度|通过（开发）|HTTP：超过128字符的料号不能通过发布样本|
|ERR-013|P0|序列耗尽|通过（开发）|HTTP：单字符流水容量耗尽拒绝，不回收已发编号|
|ERR-014|P0|数据库事务失败|通过（开发）|JUnit：NumberingTest.databaseFailureRollsBackSequenceLedgerTaskAndOutbox|
|PERF-001|P0|单次校验+预览|通过（开发）|HTTP：实测稳态预览/发号P95|
|PERF-002|P0|单次正式发号|通过（开发）|HTTP：实测稳态预览/发号P95|
|PERF-003|P0|100 并发预览|通过（开发）|HTTP：100并发预览返回稳定结果|
|PERF-004|P0|100 并发正式发号|通过（开发）|HTTP：100并发不同Identity正式发号唯一|
|PERF-005|P0|100 并发相同 Identity|通过（开发）|HTTP：100并发不同来源键同Identity最多一个台账|
|PERF-006|P0|服务重启|通过（开发）|重启：PENDING扫描/回写恢复，游标与原台账保留|
|PERF-007|P0|Outbox 重复投递|通过（开发）|HTTP：重复消费同一回写任务不重复发送ERP写入|
|PERF-008|P0|DB 短暂故障|通过（开发）|JUnit：NumberingTest.transientDatabaseConnectionLossRecoversWithoutDirtyAssignments|
|PERF-009|P1|5 万条批量校验|未执行|PERF-009：5万条容量验收待执行|
|SEC-001|P0|两租户相同料号|通过（开发）|JUnit：NumberingTest.identicalNumberIsLegalInDifferentTenants|
|SEC-002|P0|跨租户读取 Assignment|通过（开发）|HTTP：跨租户对象和台账不可读|
|SEC-003|P0|跨租户引用厂商|通过（开发）|HTTP：跨租户对象和台账不可读<br>HTTP：来源客户端不能越过自己来源边界|
|SEC-004|P0|无规则发布权限|通过（开发）|HTTP：无发布岗位不能审批配置|
|SEC-005|P0|无集成权限|通过（开发）|HTTP：保留类别读权限时，无集成权限仍拒绝发号|
|SEC-006|P0|审计记录|通过（开发）|HTTP：有权限的审计记录可定位发号操作者和trace|

PRD 验收与端到端：

|编号|结果|证据|
|---|---|---|
|AC-01|通过（开发）|开发环境：SRC-001 SRC-002 SRC-003 SRC-007|
|AC-02|通过（开发）|开发环境：DS-002 DS-004 DS-006 DS-007 GC-011|
|AC-03|通过（开发）|开发环境：CORE-003 CORE-005 CORE-006 CORE-015|
|AC-04|通过（开发）|开发环境：CORE-007 CORE-017 CORE-018|
|AC-05|通过（开发）|开发环境：CORE-010 CORE-020 CORE-023 PARSE-008|
|AC-06|通过（开发）|开发环境：自动轮询：仅ERP新建，Worker发号并确认回写|
|AC-07|通过（开发）|开发环境：SEC-001 SEC-002 SEC-003 CFG-002 CFG-008|
|AC-08|通过（开发）|开发环境：CORE-018 CORE-019 GC-012 SRC-006|
|AC-09|通过（开发）|开发环境：INT-007 INT-008 PERF-006|
|AC-10|通过（开发）|开发环境：CORE-010 CORE-011 ERR-014 PERF-008|
|AC-11|通过（开发）|开发环境：INT-001 CORE-022 CORE-023|
|AC-12|通过（开发）|开发环境：审计/指标、重启、两库61表恢复摘要一致|
|AC-13|通过（开发）|开发环境：CORE-018|
|AC-14|通过（开发）|开发环境：INT-006|
|E2E-01|通过（开发）|Chromium：frontend/e2e/v3.spec.ts，4/4通过|
|E2E-02|通过（开发）|Chromium：frontend/e2e/v3.spec.ts，4/4通过|
|E2E-03|通过（开发）|Chromium：frontend/e2e/v3.spec.ts，4/4通过|
|E2E-04|通过（开发）|Chromium：frontend/e2e/v3.spec.ts，4/4通过|

证据原件位于 .runtime/v3-acceptance-results.json、Surefire XML、browser-results.json、v3-operations-evidence.json、v3-restart-evidence.json、v3-backup-evidence.json。日志和真实业务快照不提交 Git。

INT-003/006/007/008 采用真实 HTTP ERP 故障注入 JUnit；ERR-014 注入实际事务插入失败；PERF-008 终止测试自身数据库连接再恢复；SEC-001 在两个租户实际发相同号。PERF-005 为100不同ERP键、同完整Identity，最多1条正式台账。

WritebackTest 继承 NumberingTest，测试执行次数不能直接等同独立用例数量。
