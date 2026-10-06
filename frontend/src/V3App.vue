<script setup lang="ts">
import { ref, computed, onMounted, watch } from "vue";
import { api, session, pretty, parse, labels } from "./api";
const users = ref<any[]>([]),
  tenants = ref<any[]>([]),
  userCode = ref("editor"),
  tab = ref("overview"),
  sources = ref<any[]>([]),
  objects = ref<any[]>([]),
  datasets = ref<any[]>([]),
  categories = ref<any[]>([]),
  releases = ref<any[]>([]),
  rows = ref<any[]>([]),
  selected = ref<any>(null),
  overview = ref<any>(null),
  sourceId = ref(""),
  output = ref<any>(null),
  key = ref("100001"),
  ddl = ref(""),
  configKind = ref("/field-mappings"),
  editor = ref(""),
  name = ref(""),
  category = ref(""),
  releaseForm = ref<any>({}),
  schemaId = ref(""),
  schemas = ref<any[]>([]),
  mappings = ref<any[]>([]),
  identities = ref<any[]>([]),
  rules = ref<any[]>([]);
const sourceType = ref("DATABASE"),
  categoryName = ref(""),
  categoryCode = ref(""),
  searchField = ref(""),
  searchValue = ref(""),
  searchOp = ref("eq"),
  searchTotal = ref<number | null>(null),
  searchSchemas = ref<any[]>([]);
const searchable = computed(
  () =>
    searchSchemas.value
      .find((s) => s.status === "PUBLISHED")
      ?.bundle.attributes.filter((f: any) => f.searchable) || [],
);
const paths: Record<string, string> = {
  ledger: "/assignment-ledger",
  tasks: "/writeback-tasks",
  errors: "/processing-tasks",
  scans: "/scan-jobs",
  releases: "/releases",
};
const tabs = [
  ["overview", "工作概览"],
  ["sources", "来源系统与对象"],
  ["datasets", "Dataset 构建"],
  ["configuration", "Schema 与规则"],
  ["releases", "组合发布"],
  ["ledger", "发号台账"],
  ["scans", "扫描任务"],
  ["errors", "异常工作台"],
  ["tasks", "ERP 回写"],
];
const rootObject = ref(""),
  alias = ref("g"),
  sourceKey = ref("id"),
  grain = ref("ONE_ROOT_ROW_ONE_MATERIAL"),
  joins = ref<any[]>([]),
  outputs = ref<any[]>([]),
  datasetCode = ref(""),
  resource = ref(""),
  scanResource = ref(""),
  existingNoField = ref(""),
  versionField = ref(""),
  incremental = ref("VERSION_COLUMN");
const allFields = computed(() => {
  const list: any[] = [];
  const root = objects.value.find((o) => o.id === rootObject.value);
  root?.definition.fields.forEach((f: any) =>
    list.push({
      value: alias.value + "." + f.name,
      label: alias.value + "." + f.name + " · " + f.dataType,
    }),
  );
  joins.value.forEach((j) =>
    objects.value
      .find((o) => o.id === j.objectVersionId)
      ?.definition.fields.forEach((f: any) =>
        list.push({
          value: j.alias + "." + f.name,
          label: j.alias + "." + f.name + " · " + f.dataType,
        }),
      ),
  );
  return list;
});
async function safe(work: () => Promise<any>) {
  try {
    return await work();
  } catch {}
}
async function login() {
  await api("/api/dev/login", "POST", { userCode: userCode.value });
  session.user = users.value.find((u) => u.code === userCode.value);
  tenants.value = await api("/me/tenants");
  session.tenant = tenants.value[0]?.code || "";
  await context();
}
async function context() {
  session.generation++;
  selected.value = null;
  output.value = null;
  objects.value = [];
  sourceId.value = "";
  await load();
}
async function load() {
  if (!session.tenant) return;
  const [s, d, c, r] = await Promise.all([
    api("/source-systems"),
    api("/source-datasets"),
    api("/categories"),
    api("/releases"),
  ]);
  sources.value = s;
  datasets.value = d;
  categories.value = c;
  releases.value = r;
  if (paths[tab.value]) rows.value = await api(paths[tab.value]);
  if (tab.value === "overview") overview.value = await api("/v3/overview");
  if (tab.value === "configuration")
    rows.value =
      configKind.value === "/schemas"
        ? category.value
          ? await api("/categories/" + category.value + "/schemas")
          : []
        : await api(configKind.value);
  if (sourceId.value)
    objects.value = await api("/source-systems/" + sourceId.value + "/objects");
}
async function chooseSource() {
  objects.value = await api("/source-systems/" + sourceId.value + "/objects");
  if (tab.value === "sources") {
    selected.value = sources.value.find((s) => s.id === sourceId.value);
    editor.value = pretty(selected.value?.connectionProfile);
  }
}
async function createSource() {
  const s = await api("/source-systems", "POST", {
    code: name.value,
    type: sourceType.value,
    connectionProfile: parse(editor.value),
  });
  sourceId.value = s.id;
  await load();
  await chooseSource();
}
async function activateSource() {
  const s = selected.value;
  await api("/source-systems/" + s.id + "/test", "POST", {});
  await api(
    "/source-systems/" + s.id,
    "PATCH",
    { status: "ACTIVE" },
    s.rowVersion,
  );
  await load();
  await chooseSource();
}
async function discover() {
  output.value = await api(
    "/source-systems/" + sourceId.value + "/discover",
    "POST",
    { objects: parse(editor.value) },
  );
  await load();
}
async function createCategory() {
  await api("/categories", "POST", {
    code: categoryCode.value,
    name: categoryName.value || categoryCode.value,
  });
  category.value = categoryCode.value;
  await load();
  session.notice = "类别已创建；请配置Schema、Identity和Code Rule。";
}
async function registerObject() {
  output.value = await api("/source-objects", "POST", {
    sourceSystemId: sourceId.value,
    definition: parse(ddl.value),
  });
  await load();
}
async function searchCategory() {
  searchSchemas.value = await api("/categories/" + category.value + "/schemas");
  searchField.value = "";
  searchTotal.value = null;
}
async function searchLedger() {
  const f = searchable.value.find((f: any) => f.code === searchField.value);
  let value: any = searchValue.value;
  if (["DECIMAL", "INTEGER"].includes(f?.type))
    value = parse('{"value":' + value + ',"unit":"' + (f.unit || "") + '"}');
  else if (f?.type === "BOOLEAN") value = value === "true";
  else if (f?.type === "REFERENCE") value = parse(value);
  const result = await api("/assignment-ledger/search", "POST", {
    categoryCode: category.value,
    filters: [{ field: searchField.value, op: searchOp.value, value }],
    page: 1,
    pageSize: 100,
  });
  rows.value = result.items;
  searchTotal.value = result.total;
}
async function numberPreview() {
  output.value = await api("/material-numbers:preview", "POST", {
    datasetCode: datasetCode.value,
    sourceRecordKey: key.value,
  });
}
async function assignNumber() {
  output.value = await api("/material-numbers:assign", "POST", {
    datasetCode: datasetCode.value,
    sourceRecordKey: key.value,
  });
  session.notice = "台账已持久化，ERP回写由独立任务确认。";
}
async function importDdl() {
  output.value = await api("/source-objects:import-ddl", "POST", {
    sourceSystemId: sourceId.value,
    ddl: ddl.value,
  });
  await load();
}
function addJoin() {
  joins.value.push({
    objectVersionId: "",
    alias: "r" + (joins.value.length + 1),
    type: "LEFT",
    cardinality: "N:1",
    nullPolicy: "ERROR",
    on: [{ left: "", right: "" }],
  });
}
function definition() {
  return {
    root: { objectVersionId: rootObject.value, alias: alias.value },
    sourceKey: sourceKey.value
      .split(",")
      .map((k) => alias.value + "." + k.trim()),
    grain: grain.value,
    joins: joins.value,
    output: Object.fromEntries(outputs.value.map((o) => [o.name, o.field])),
    ...(resource.value
      ? { resource: resource.value, scanResource: scanResource.value }
      : {}),
    ...(existingNoField.value
      ? { existingNoField: existingNoField.value }
      : {}),
    ...(versionField.value ? { sourceVersionField: versionField.value } : {}),
    incremental: {
      strategy: incremental.value,
      referenceStrategy: "RESCAN_UNISSUED",
    },
  };
}
async function saveDataset() {
  const b = {
    code: datasetCode.value,
    sourceSystemId: sourceId.value,
    definition: definition(),
  };
  selected.value = await api("/source-datasets", "POST", b);
  await load();
  session.notice = "已保存 Dataset 草稿。发布前必须运行样本测试。";
}
function editDataset(d: any) {
  if (!d) return;
  selected.value = d;
  sourceId.value = d.sourceSystemId;
  chooseSource();
  datasetCode.value = d.code;
  rootObject.value = d.definition.root.objectVersionId;
  alias.value = d.definition.root.alias;
  sourceKey.value = d.definition.sourceKey
    .map((k: string) => k.split(".").slice(1).join("."))
    .join(",");
  joins.value = d.definition.joins || [];
  outputs.value = Object.entries(d.definition.output).map(([name, field]) => ({
    name,
    field,
  }));
  existingNoField.value = d.definition.existingNoField || "";
  versionField.value = d.definition.sourceVersionField || "";
  resource.value = d.definition.resource || "";
  scanResource.value = d.definition.scanResource || "";
}
async function datasetPreview() {
  if (!selected.value?.id) return;
  output.value = await api(
    "/source-datasets/" + selected.value.id + "/preview",
    "POST",
    { sourceRecordKey: key.value },
  );
}
async function saveConfig() {
  if (configKind.value === "/schemas") {
    selected.value = await api(
      "/categories/" + category.value + "/schemas",
      "POST",
      { bundle: parse(editor.value) },
    );
  } else
    selected.value = await api(configKind.value, "POST", {
      code: name.value,
      categoryCode: category.value,
      definition: parse(editor.value),
    });
  await load();
}
async function categoryChange() {
  if (category.value)
    schemas.value = await api("/categories/" + category.value + "/schemas");
}
async function releaseDeps() {
  [mappings.value, identities.value, rules.value] = await Promise.all([
    api("/field-mappings"),
    api("/identity-definitions"),
    api("/code-rules"),
  ]);
  await categoryChange();
}
async function createRelease() {
  selected.value = await api("/releases", "POST", {
    ...releaseForm.value,
    categoryCode: category.value,
    schemaVersionId: schemaId.value,
    definition: parse(editor.value),
  });
  await load();
}
async function releaseAction(r: any, action: string) {
  if (action === "test")
    output.value = await api("/releases/" + r.id + "/test", "POST", {});
  else {
    await api(
      "/releases/" + r.id + "/" + action,
      "POST",
      { reason: "控制台配置操作" },
      r.rowVersion,
    );
    await load();
  }
}
async function explain(a: any) {
  selected.value = a;
  output.value = await api("/assignment-ledger/" + a.id + "/explain");
}
async function scan(d: any) {
  output.value = await api("/source-datasets/" + d.id + "/scan", "POST", {});
  tab.value = "scans";
  await load();
}
async function retry(t: any) {
  output.value = await api(
    (tab.value === "tasks" ? "/writeback-tasks/" : "/processing-tasks/") +
      t.id +
      "/retry",
    "POST",
    { reason: "源数据/接口已修正，请查询确认后重试" },
  );
  await load();
}
async function write(t: any) {
  output.value = await api("/writeback-tasks/" + t.id + "/process", "POST", {});
  await load();
}
async function reconcile(r: any) {
  output.value = await api("/reconciliations", "POST", { releaseId: r.id });
}
watch(tab, () => safe(load));
watch(configKind, () => safe(load));
onMounted(() =>
  safe(async () => {
    users.value = await api("/api/dev/users");
  }),
);
</script>
<template>
  <div class="v3-layout">
    <aside class="v3-sidebar">
      <div class="v3-brand">MDM <span>V3.0</span></div>
      <p class="v3-subtitle">物料标准化与料号平台</p>
      <div class="v3-source-label">事实源 · ERP</div>
      <a href="/erp/" style="display: block; margin: 16px; color: inherit">打开 ERP 测试页面 ↗</a>
      <nav>
        <button
          v-for="[id, label] in tabs"
          :key="id"
          :class="{ active: tab === id }"
          @click="
            tab = id;
            output = null;
            selected = null;
          "
        >
          {{ label }}
        </button>
      </nav>
      <p class="v3-foot">
        Identity · Rules · Ledger<br />配置发布后保持版本可追溯
      </p>
    </aside>
    <main class="v3-main">
      <header>
        <div>
          <small>ERP → Dataset → Identity → 料号 → ERP</small>
          <h1>{{ tabs.find((t) => t[0] === tab)?.[1] }}</h1>
        </div>
        <div class="v3-session">
          <select v-model="userCode" aria-label="当前用户">
            <option v-for="u in users" :value="u.code">
              {{ u.name }}
            </option></select
          ><button @click="safe(login)">登录 / 切换用户</button
          ><select
            v-model="session.tenant"
            aria-label="当前租户"
            @change="safe(context)"
          >
            <option v-for="t in tenants" :value="t.code">
              {{ t.name }}
            </option></select
          ><button @click="safe(load)">刷新</button>
        </div>
      </header>
      <div v-if="session.error" class="v3-alert" role="alert">
        {{ session.error }}
      </div>
      <div v-if="session.notice" class="v3-notice">{{ session.notice }}</div>
      <p v-if="!session.tenant" class="v3-empty">
        请选择开发演示用户登录，进入所属租户。
      </p>
      <template v-else>
        <section v-if="tab === 'overview' && overview">
          <div class="v3-cards">
            <article>
              <small>来源系统</small><strong>{{ overview.sources }}</strong>
            </article>
            <article>
              <small>组合发布版本</small
              ><strong>{{ overview.releases }}</strong>
            </article>
            <article>
              <small>发号台账 · 最近 1000 条</small
              ><strong>{{ overview.assignments }}</strong>
            </article>
            <article>
              <small>回写异常</small
              ><strong>{{
                overview.tasks.filter((t: any) =>
                  ["FAILED", "RESULT_UNKNOWN"].includes(t.state),
                ).length
              }}</strong>
            </article>
          </div>
          <article class="v3-panel">
            <div class="v3-buttons">
              <h2>从来源到发号</h2>
              <button
                @click="
                  safe(async () => {
                    output = await api('/v3/monitoring');
                  })
                "
              >
                运行指标与连接状态
              </button>
            </div>
            <p>
              登记只读来源与对象结构，构建 Dataset，配置
              Mapping、Schema、Identity 和 Code
              Rule；样本验证后由另一位成员发布。扫描发现候选记录并发号，ERP
              回写独立确认。
            </p>
            <div class="v3-flow">
              <span
                v-for="s in [
                  'Source',
                  'Dataset',
                  'Mapping',
                  'Schema',
                  'Identity',
                  'Validation',
                  'Code Rule',
                  'Ledger',
                  'ERP',
                ]"
                >{{ s }}</span
              >
            </div>
          </article>
        </section>
        <section v-if="tab === 'sources'" class="v3-two">
          <article class="v3-panel">
            <h2>来源系统</h2>
            <label
              >已登记来源<select
                v-model="sourceId"
                @change="safe(chooseSource)"
              >
                <option value="">选择来源</option>
                <option v-for="s in sources" :value="s.id">
                  {{ s.code }} · {{ labels[s.status] || s.status }}
                </option>
              </select></label
            ><label
              >来源类型<select v-model="sourceType">
                <option>DATABASE</option>
                <option>REST</option>
                <option>INTERFACE_PLATFORM</option>
              </select></label
            ><label
              >新来源 code<input v-model="name" placeholder="ERP_DEMO" /></label
            ><label
              >连接配置 / 扫描对象清单<textarea
                v-model="editor"
                rows="9"
                spellcheck="false"
                placeholder='{"jdbcUrl":"jdbc:postgresql://localhost:5432/mdm_erp_v3","userEnv":"ERP_READ_USER","passwordEnv":"ERP_READ_PASSWORD","writebackBaseUrl":"http://127.0.0.1:9092"}'
              />
            </label>
            <div class="v3-buttons">
              <button @click="safe(createSource)">登记来源</button
              ><button :disabled="!sourceId" @click="safe(activateSource)">
                测试连接并启用</button
              ><button :disabled="!sourceId" @click="safe(discover)">
                读取 JDBC 元数据
              </button>
            </div>
            <p class="v3-muted">
              凭据仅保存环境变量名称。扫描清单示例：[ { "objectName": "cloth" }
              ]
            </p>
          </article>
          <article class="v3-panel">
            <h2>Source Object · DDL</h2>
            <textarea
              v-model="ddl"
              rows="8"
              placeholder="CREATE TABLE ..."
              spellcheck="false"
            /><button :disabled="!sourceId" @click="safe(importDdl)">
              解析 DDL 为草稿</button
            ><button :disabled="!sourceId" @click="safe(registerObject)">
              登记 API Object JSON 契约
            </button>
            <table>
              <thead>
                <tr>
                  <th>对象</th>
                  <th>版本</th>
                  <th>字段数</th>
                  <th>状态</th>
                </tr>
              </thead>
              <tbody>
                <tr v-for="o in objects" @click="output = o">
                  <td>{{ o.objectName }}</td>
                  <td>{{ o.versionNo }}</td>
                  <td>{{ o.definition.fields.length }}</td>
                  <td>{{ labels[o.status] }}</td>
                </tr>
              </tbody>
            </table>
          </article>
        </section>
        <section v-if="tab === 'datasets'" class="v3-panel">
          <div class="v3-buttons">
            <select
              @change="
                editDataset(
                  datasets.find(
                    (d) => d.id === ($event.target as HTMLSelectElement).value,
                  ),
                )
              "
            >
              <option value="">打开已有 Dataset</option>
              <option v-for="d in datasets" :value="d.id">
                {{ d.code }} · v{{ d.versionNo }}
              </option></select
            ><button :disabled="!selected" @click="safe(() => scan(selected))">
              扫描当前发布版本
            </button>
          </div>
          <div class="v3-grid">
            <label
              >来源<select v-model="sourceId" @change="safe(chooseSource)">
                <option value="">选择来源</option>
                <option v-for="s in sources" :value="s.id">{{ s.code }}</option>
              </select></label
            ><label>Dataset code<input v-model="datasetCode" /></label
            ><label
              >Root Object<select v-model="rootObject">
                <option value="">选择对象版本</option>
                <option v-for="o in objects" :value="o.id">
                  {{ o.objectName }} · v{{ o.versionNo }}
                </option>
              </select></label
            ><label>Root alias<input v-model="alias" /></label
            ><label
              >来源键 · 多字段用逗号分隔<input v-model="sourceKey" /></label
            ><label
              >物料粒度<select v-model="grain">
                <option>ONE_ROOT_ROW_ONE_MATERIAL</option>
              </select></label
            >
          </div>
          <h3>参考表关联</h3>
          <div v-for="(j, i) in joins" class="v3-join">
            <select v-model="j.objectVersionId">
              <option value="">关联对象版本</option>
              <option v-for="o in objects" :value="o.id">
                {{ o.objectName }} v{{ o.versionNo }}
              </option></select
            ><input v-model="j.alias" placeholder="alias" /><select
              v-model="j.type"
            >
              <option>LEFT</option>
              <option>INNER</option></select
            ><select v-model="j.cardinality">
              <option>N:1</option>
              <option>1:1</option></select
            ><select v-model="j.nullPolicy">
              <option>ERROR</option>
              <option>NULL</option>
            </select>
            <div v-for="on in j.on">
              <select v-model="on.left">
                <option value="">左字段</option>
                <option v-for="f in allFields" :value="f.value">
                  {{ f.label }}
                </option>
              </select>
              =
              <select v-model="on.right">
                <option value="">右字段</option>
                <option v-for="f in allFields" :value="f.value">
                  {{ f.label }}
                </option>
              </select>
            </div>
            <button @click="j.on.push({ left: '', right: '' })">
              增加等值条件</button
            ><button @click="joins.splice(i, 1)">移除关联</button>
          </div>
          <button @click="addJoin">增加参考表</button>
          <h3>逻辑输出字段</h3>
          <div v-for="(o, i) in outputs" class="v3-buttons">
            <input v-model="o.name" placeholder="输出名称" /><select
              v-model="o.field"
            >
              <option value="">来源字段</option>
              <option v-for="f in allFields" :value="f.value">
                {{ f.label }}
              </option></select
            ><button @click="outputs.splice(i, 1)">移除</button>
          </div>
          <button @click="outputs.push({ name: '', field: '' })">
            增加输出
          </button>
          <div class="v3-grid">
            <label
              >已有 ERP 料号字段<select v-model="existingNoField">
                <option value="">未设置</option>
                <option v-for="f in allFields" :value="f.value">
                  {{ f.label }}
                </option>
              </select></label
            ><label
              >来源版本 / 时间字段<select v-model="versionField">
                <option value="">未设置</option>
                <option v-for="f in allFields" :value="f.value">
                  {{ f.label }}
                </option>
              </select></label
            ><label
              >增量策略<select v-model="incremental">
                <option>VERSION_COLUMN</option>
                <option>UPDATED_AT</option>
                <option>PENDING_PREDICATE</option>
                <option>REQUEST_TRIGGER</option>
                <option>MANUAL_RESCAN</option>
              </select></label
            ><label
              >API 单记录资源<input
                v-model="resource"
                placeholder="/documents/{key}" /></label
            ><label
              >API 扫描资源<input
                v-model="scanResource"
                placeholder="/documents"
            /></label>
          </div>
          <div class="v3-buttons">
            <button @click="safe(saveDataset)">保存为新版本草稿</button
            ><input v-model="key" aria-label="预览来源键" /><button
              @click="safe(datasetPreview)"
            >
              读取真实数据预览</button
            ><button @click="safe(numberPreview)">按默认发布规则预览料号</button
            ><button @click="safe(assignNumber)">从 ERP 记录申请正式号</button>
          </div>
        </section>
        <section v-if="tab === 'configuration'" class="v3-two">
          <article class="v3-panel">
            <h2>类别与独立配置版本</h2>
            <div class="v3-buttons">
              <input
                v-model="categoryCode"
                aria-label="新类别代码"
                placeholder="新类别代码"
              /><input
                v-model="categoryName"
                aria-label="新类别名称"
                placeholder="新类别名称"
              /><button @click="safe(createCategory)">创建类别</button>
            </div>
            <label
              >配置类型<select aria-label="配置类型" v-model="configKind">
                <option value="/field-mappings">Field Mapping</option>
                <option value="/identity-definitions">Material Identity</option>
                <option value="/code-rules">Code Rule</option>
                <option value="/schemas">Schema</option>
              </select></label
            ><label
              >所属类别<select
                aria-label="所属类别"
                v-model="category"
                @change="safe(categoryChange)"
              >
                <option v-for="c in categories" :value="c.code">
                  {{ c.name }} · {{ c.code }}
                </option>
              </select></label
            ><label
              >配置 code<input aria-label="配置 code" v-model="name" /></label
            ><label
              >配置定义<textarea
                aria-label="配置定义"
                v-model="editor"
                rows="20"
                spellcheck="false"
              /></label
            ><button @click="safe(saveConfig)">保存为新版本草稿</button>
            <p class="v3-muted">
              发布内容不可原地修改。表达式使用受限
              AST；业务规则由元数据配置，不按类别分支。
            </p>
          </article>
          <article class="v3-panel">
            <h2>版本清单</h2>
            <table>
              <thead>
                <tr>
                  <th>Code</th>
                  <th>版本</th>
                  <th>状态</th>
                </tr>
              </thead>
              <tbody>
                <tr
                  v-for="r in rows"
                  @click="
                    editor = pretty(r.bundle || r.definition);
                    selected = r;
                  "
                >
                  <td>{{ r.code }}</td>
                  <td>{{ r.versionNo }}</td>
                  <td>{{ labels[r.status] }}</td>
                </tr>
              </tbody>
            </table>
          </article>
        </section>
        <section v-if="tab === 'releases'">
          <article class="v3-panel">
            <h2>新建组合发布包</h2>
            <div class="v3-grid">
              <label
                >类别<select
                  aria-label="类别"
                  v-model="category"
                  @change="safe(releaseDeps)"
                >
                  <option v-for="c in categories" :value="c.code">
                    {{ c.name }}
                  </option>
                </select></label
              ><label
                >Dataset<select
                  aria-label="Dataset"
                  v-model="releaseForm.datasetVersionId"
                >
                  <option v-for="d in datasets" :value="d.id">
                    {{ d.code }} v{{ d.versionNo }}
                  </option>
                </select></label
              ><label
                >Schema<select aria-label="Schema" v-model="schemaId">
                  <option v-for="s in schemas" :value="s.id">
                    v{{ s.version }} · {{ labels[s.status] }}
                  </option>
                </select></label
              ><label
                >Mapping<select
                  aria-label="Mapping"
                  v-model="releaseForm.mappingVersionId"
                >
                  <option v-for="r in mappings" :value="r.id">
                    {{ r.code }} v{{ r.versionNo }}
                  </option>
                </select></label
              ><label
                >Identity<select
                  aria-label="Identity"
                  v-model="releaseForm.identityDefinitionVersionId"
                >
                  <option v-for="r in identities" :value="r.id">
                    {{ r.code }} v{{ r.versionNo }}
                  </option>
                </select></label
              ><label
                >Code Rule<select
                  aria-label="Code Rule"
                  v-model="releaseForm.codeRuleVersionId"
                >
                  <option v-for="r in rules" :value="r.id">
                    {{ r.code }} v{{ r.versionNo }}
                  </option>
                </select></label
              >
            </div>
            <label
              >Golden Samples 与回写契约<textarea
                aria-label="Golden Samples 与回写契约"
                v-model="editor"
                rows="8"
                spellcheck="false"
              /></label
            ><button @click="safe(createRelease)">新建发布草稿</button>
          </article>
          <article class="v3-panel">
            <table>
              <thead>
                <tr>
                  <th>发布包</th>
                  <th>版本</th>
                  <th>状态</th>
                  <th>动作</th>
                </tr>
              </thead>
              <tbody>
                <tr v-for="r in rows">
                  <td>{{ r.code }}</td>
                  <td>{{ r.versionNo }}</td>
                  <td>{{ labels[r.status] }}</td>
                  <td class="v3-buttons">
                    <button @click="safe(() => releaseAction(r, 'test'))">
                      样本验证</button
                    ><button
                      v-if="r.status === 'DRAFT'"
                      @click="safe(() => releaseAction(r, 'submit'))"
                    >
                      提交</button
                    ><button
                      v-if="r.status === 'REVIEW'"
                      @click="safe(() => releaseAction(r, 'approve'))"
                    >
                      审批发布</button
                    ><button
                      v-if="r.everPublished"
                      @click="safe(() => releaseAction(r, 'set-default'))"
                    >
                      设为默认</button
                    ><button
                      v-if="r.everPublished"
                      @click="safe(() => reconcile(r))"
                    >
                      对账
                    </button>
                  </td>
                </tr>
              </tbody>
            </table>
          </article>
        </section>
        <section
          v-if="['ledger', 'tasks', 'errors', 'scans'].includes(tab)"
          class="v3-panel"
        >
          <div v-if="tab === 'ledger'" class="v3-buttons">
            <select
              v-model="category"
              aria-label="台账搜索类别"
              @change="safe(searchCategory)"
            >
              <option value="">选择类别</option>
              <option v-for="c in categories" :value="c.code">
                {{ c.name }} · {{ c.code }}
              </option></select
            ><select v-model="searchField" aria-label="台账搜索字段">
              <option value="">可搜索属性</option>
              <option v-for="f in searchable" :value="f.code">
                {{ f.code }} · {{ f.type }}
              </option></select
            ><select v-model="searchOp" aria-label="台账搜索操作">
              <option value="eq">等于</option>
              <option value="gte">大于等于</option>
              <option value="lte">小于等于</option></select
            ><input v-model="searchValue" aria-label="台账搜索值" /><button
              :disabled="!category || !searchField"
              @click="safe(searchLedger)"
            >
              类型化搜索</button
            ><span v-if="searchTotal !== null">{{ searchTotal }} 条匹配</span>
          </div>
          <p class="v3-muted">
            {{
              tab === "ledger"
                ? "不可变台账 · 来源键、完整 Identity 和料号均有租户内唯一约束"
                : "任务按当前权限执行；暂停租户或来源会保留处理进度。"
            }}
          </p>
          <table>
            <thead>
              <tr>
                <th>来源键 / 任务</th>
                <th>{{ tab === "ledger" ? "料号" : "状态" }}</th>
                <th>{{ tab === "ledger" ? "发号时间" : "错误 / 数量" }}</th>
                <th>操作</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="r in rows">
                <td>{{ r.sourceRecordKey || r.id }}</td>
                <td>
                  {{ r.materialNo || labels[r.state || r.status] || r.status }}
                </td>
                <td>
                  {{
                    r.issuedAt ||
                    r.errorCode ||
                    r.scannedCount + " 条扫描 / " + r.failedCount + " 条失败"
                  }}
                </td>
                <td>
                  <button
                    v-if="tab === 'ledger'"
                    @click="safe(() => explain(r))"
                  >
                    解释料号</button
                  ><button v-else @click="output = r">详情</button
                  ><button
                    v-if="
                      tab === 'tasks' &&
                      !['SUCCEEDED', 'RESULT_UNKNOWN', 'FAILED'].includes(
                        r.state,
                      )
                    "
                    @click="safe(() => write(r))"
                  >
                    查询并处理</button
                  ><button
                    v-if="
                      ['ERROR', 'FAILED', 'RESULT_UNKNOWN'].includes(
                        r.status || r.state,
                      )
                    "
                    @click="safe(() => retry(r))"
                  >
                    修正后重试
                  </button>
                </td>
              </tr>
            </tbody>
          </table>
          <p v-if="!rows.length" class="v3-empty">
            暂无记录。先发布组合版本，再运行 Dataset 扫描。
          </p>
        </section>
        <article v-if="output" class="v3-panel v3-result">
          <div class="v3-buttons">
            <h2>验证结果 / 记录详情</h2>
            <button @click="output = null">关闭</button>
          </div>
          <pre>{{ pretty(output) }}</pre>
        </article>
      </template>
      <footer>
        通用料号与物料主数据平台 · PRD V3.0 · BOM / 库存 / 工艺由下游系统维护
      </footer>
    </main>
  </div>
</template>
<style scoped>
.v3-layout {
  display: flex;
  min-height: 100vh;
  background: #f5f6fa;
  color: #192438;
  font-size: 14px;
}
.v3-sidebar {
  width: 224px;
  flex-shrink: 0;
  background: #13243b;
  color: #d8e0eb;
  padding: 30px 20px;
  display: flex;
  flex-direction: column;
}
.v3-brand {
  font-size: 32px;
  font-weight: 750;
  letter-spacing: 2px;
  color: white;
}
.v3-brand span {
  font-size: 12px;
  background: #304c70;
  padding: 5px;
  border-radius: 4px;
  letter-spacing: 0;
}
.v3-subtitle {
  font-size: 12px;
  color: #9aabc3;
}
.v3-source-label {
  font-size: 12px;
  padding: 10px 0;
  color: #8dd1bf;
}
.v3-sidebar nav {
  display: grid;
  gap: 6px;
  margin-top: 24px;
}
.v3-sidebar nav button {
  border: 0;
  background: transparent;
  color: #c5d0df;
  text-align: left;
  padding: 13px;
  border-radius: 7px;
}
.v3-sidebar nav button.active {
  background: #2a4568;
  color: white;
}
.v3-foot {
  margin-top: auto;
  padding-top: 60px;
  color: #8a9eb9;
  font-size: 11px;
  line-height: 1.8;
}
.v3-main {
  padding: 28px 32px;
  width: calc(100% - 224px);
  max-width: 1800px;
}
.v3-main header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 20px;
  margin-bottom: 25px;
}
.v3-main h1 {
  font-size: 25px;
  margin: 8px 0;
}
.v3-main h2 {
  font-size: 17px;
}
.v3-main small,
.v3-muted {
  color: #68768a;
}
.v3-session {
  display: flex;
  gap: 6px;
  flex-wrap: wrap;
  justify-content: flex-end;
}
.v3-main button {
  cursor: pointer;
  border: 1px solid #ccd5e1;
  background: white;
  color: #234e82;
  padding: 8px 11px;
  border-radius: 5px;
  white-space: nowrap;
}
.v3-main button:hover {
  background: #edf3fa;
}
.v3-main button:disabled {
  opacity: 0.45;
  cursor: default;
}
.v3-main input,
.v3-main select,
.v3-main textarea {
  border: 1px solid #ccd5e1;
  border-radius: 5px;
  padding: 9px;
  color: #25344b;
  background: white;
  max-width: 100%;
  font-family: inherit;
}
.v3-main textarea {
  width: 100%;
  font-family: ui-monospace, monospace;
  font-size: 12px;
  line-height: 1.7;
  box-sizing: border-box;
}
.v3-main label {
  display: grid;
  gap: 7px;
  font-size: 12px;
  color: #647184;
  margin-bottom: 14px;
}
.v3-panel {
  background: white;
  border: 1px solid #e1e5ed;
  border-radius: 9px;
  padding: 22px;
  margin-bottom: 20px;
}
.v3-two {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 20px;
}
.v3-grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 12px;
  margin: 20px 0;
}
.v3-cards {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 18px;
  margin-bottom: 20px;
}
.v3-cards article {
  background: white;
  border: 1px solid #e1e5ed;
  border-radius: 8px;
  padding: 23px;
}
.v3-cards strong {
  display: block;
  font-size: 33px;
  margin-top: 15px;
  font-weight: 600;
}
.v3-flow {
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
  margin: 25px 0;
}
.v3-flow span {
  background: #edf3fa;
  border-radius: 5px;
  padding: 10px;
  color: #315f93;
}
.v3-buttons {
  display: flex;
  gap: 8px;
  align-items: center;
  flex-wrap: wrap;
  margin: 10px 0;
}
.v3-join {
  border: 1px solid #e2e7ef;
  border-radius: 6px;
  padding: 14px;
  margin: 12px 0;
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
}
.v3-join input {
  max-width: 90px;
}
.v3-main table {
  width: 100%;
  border-collapse: collapse;
  margin-top: 18px;
  table-layout: auto;
}
.v3-main th,
.v3-main td {
  padding: 13px 8px;
  text-align: left;
  border-bottom: 1px solid #e9edf3;
  overflow-wrap: anywhere;
}
.v3-main th {
  color: #6a778b;
  font-size: 12px;
  font-weight: 500;
}
.v3-main tbody tr:hover {
  background: #f8fafd;
}
.v3-main pre {
  white-space: pre-wrap;
  overflow-wrap: anywhere;
  font-size: 12px;
  line-height: 1.65;
  background: #f7f9fc;
  padding: 18px;
  border-radius: 5px;
  max-height: 700px;
  overflow: auto;
}
.v3-alert {
  border: 1px solid #edbcc0;
  background: #fff0f1;
  color: #98313c;
  padding: 15px;
  white-space: pre-wrap;
  margin-bottom: 15px;
}
.v3-notice {
  background: #eaf5ef;
  padding: 12px;
  color: #377553;
  margin-bottom: 15px;
}
.v3-empty {
  padding: 30px;
  color: #8190a4;
  text-align: center;
}
.v3-main footer {
  font-size: 11px;
  color: #8996a8;
  margin-top: 25px;
}
.v3-result {
  border-left: 3px solid #4e7cab;
}
@media (max-width: 1000px) {
  .v3-sidebar {
    width: 180px;
  }
  .v3-main {
    width: calc(100% - 180px);
    padding: 20px;
  }
  .v3-two {
    grid-template-columns: 1fr;
  }
  .v3-main header {
    align-items: flex-start;
    flex-direction: column;
  }
  .v3-grid {
    grid-template-columns: repeat(2, 1fr);
  }
}
</style>
