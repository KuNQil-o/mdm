<script setup lang="ts">
import { ref, computed, onMounted, onBeforeUnmount, watch } from "vue";
import DynamicForm from "./DynamicForm.vue";
import { api, session, labels, pretty, download, decimal, clone } from "./api";
const ask = (message: string, initial?: string) =>
  window.prompt(message, initial);
const page = ref("dashboard"),
  users = ref<any[]>([]),
  tenantList = ref<any[]>([]),
  userCode = ref(""),
  categories = ref<any[]>([]),
  references = ref<any[]>([]),
  data = ref<any>({}),
  dirty = ref(false);
const modal = ref(""),
  detail = ref<any>(null),
  history = ref<any[]>([]),
  form = ref<any>({}),
  schema = ref<any>(null),
  preview = ref<any>(null),
  normalized = ref<any>(null),
  decisions = ref<any>({});
const exportJobs = ref<any[]>([]);
const query = ref<any>({
    name: "",
    categoryCode: "",
    status: "",
    filters: [],
    page: { size: 50 },
  }),
  filterField = ref(""),
  filterOp = ref("EQ"),
  filterValue = ref(""),
  filterUnit = ref(""),
  list = ref<any>({ items: [] }),
  cursorHistory = ref<any[]>([]);
const modelCode = ref("GLASS_CLOTH"),
  schemas = ref<any[]>([]),
  bundle = ref<any>(null),
  editingSchema = ref<any>(null),
  advanced = ref(false),
  advancedJson = ref("");
const metaJson = ref(
    '{"kind":"ATTRIBUTE","code":"length","data":{"label":"长度","type":"DECIMAL","unit":"mm","required":true}}',
  ),
  referenceJson = ref(
    '{"type":"SUPPLIER","id":"SUP-003","name":"新厂商","data":{}}',
  );
const uploaded = ref<any>(null),
  uploadFile = ref<File | null>(null),
  sheet = ref("CSV"),
  importMap = ref<any>({}),
  importColumns = ref<string[]>([]),
  importMode = ref("CREATE"),
  importJob = ref<any>(null),
  parseVersion = ref(""),
  parseNo = ref(""),
  parseResult = ref<any>(null);
const systems = ref<any[]>([]),
  selectedSystem = ref<any>(null),
  mappingList = ref<any[]>([]),
  mappingJson = ref(""),
  selectedMapping = ref<any>(null),
  reconcileResult = ref<any>(null),
  integrationUpload = ref<File | null>(null),
  inboundJson = ref(
    '{"sourceMessageId":"","externalId":"","categoryCode":"GLASS_CLOTH","data":{"materialName":"入站物料","attributes":{}}}',
  );
const members = ref<any[]>([]),
  roles = ref<any[]>([]),
  tenantUsers = ref<any[]>([]),
  settings = ref<any>(null),
  memberForm = ref<any>({
    roles: ["READER"],
    categoryScope: ["*"],
    active: true,
  });
const pages = [
  ["dashboard", "◫", "工作台"],
  ["materials", "▦", "物料目录"],
  ["requests", "✓", "审批中心"],
  ["models", "◇", "模型配置"],
  ["imports", "⇧", "导入中心"],
  ["integrations", "⇄", "集成与对账"],
  ["logs", "≡", "业务记录"],
  ["tenant", "⚙", "租户设置与成员"],
  ["platform", "◎", "平台租户管理"],
];
const currentTenant = computed(() =>
  tenantList.value.find((t) => t.code === session.tenant),
);
const can = (a: string) => session.context?.actions?.includes(a);
const status = (s: string) => labels[s] || s;
const title = computed(() => pages.find((p) => p[0] === page.value)?.[2]);
const fields = computed(() => schema.value?.bundle?.attributes || []);
const filterDef = computed(() =>
  fields.value.find((f: any) => f.code === filterField.value),
);
function leave() {
  return !dirty.value || confirm("当前表单尚未保存。放弃修改并切换？");
}
async function run(fn: () => Promise<any>) {
  try {
    return await fn();
  } catch (e: any) {
    if (e.message !== "CONTEXT_CHANGED") session.error = e.message;
    return null;
  }
}
async function boot() {
  users.value = await api("/api/dev/users");
}
async function seed() {
  await api("/api/dev/seed", "POST", {});
  await boot();
  session.notice = "演示配置已初始化；已有数据保留。请选择用户登录。";
}
async function login(code: string) {
  if (!leave()) return;
  session.generation++;
  await api("/api/dev/login", "POST", { userCode: code });
  userCode.value = code;
  session.user = users.value.find((u) => u.code === code);
  tenantList.value = await api("/me/tenants");
  await changeTenant(tenantList.value[0]?.code || "", true);
}
async function changeTenant(code: string, forced = false) {
  if (!forced && !leave()) return;
  if (!code) return;
  session.generation++;
  session.tenant = code;
  data.value = {};
  list.value = { items: [] };
  exportJobs.value = [];
  schemas.value = [];
  systems.value = [];
  mappingList.value = [];
  members.value = [];
  roles.value = [];
  form.value = {};
  modal.value = "";
  dirty.value = false;
  session.notice = "";
  session.error = "";
  detail.value = null;
  schema.value = null;
  bundle.value = null;
  query.value = {
    name: "",
    categoryCode: "",
    status: "",
    filters: [],
    page: { size: 50 },
  };
  importJob.value = null;
  uploaded.value = null;
  selectedSystem.value = null;
  session.context = await api("/tenants/current");
  categories.value = await api("/categories");
  references.value = await api("/references");
  modelCode.value = categories.value[0]?.code || "";
  if (page.value === "platform" && !session.user?.platformAdmin)
    page.value = "dashboard";
  await load();
}
async function navigate(next: string) {
  if (!leave()) return;
  session.generation++;
  data.value = {};
  list.value = { items: [] };
  page.value = next;
  modal.value = "";
  dirty.value = false;
  await load();
}
async function load() {
  if (!session.tenant && page.value !== "platform") return;
  switch (page.value) {
    case "dashboard":
      data.value = await api("/dashboard");
      break;
    case "materials":
      await search();
      if (can("EXPORT")) exportJobs.value = await api("/export-jobs");
      break;
    case "requests":
      data.value = await api("/requests");
      break;
    case "models":
      await loadModels();
      break;
    case "imports":
      data.value = await api("/import-jobs");
      break;
    case "integrations":
      systems.value = await api("/integration-systems");
      data.value = await api("/deliveries");
      break;
    case "logs":
      data.value = await api("/operation-logs");
      break;
    case "tenant":
      settings.value = await api("/tenant/settings");
      if (can("ADMIN")) {
        members.value = await api("/tenant/members");
        roles.value = await api("/tenant/roles");
        tenantUsers.value = await api("/tenant/users");
      }
      break;
    case "platform":
      data.value = await api("/platform/tenants");
      break;
  }
}
async function search(reset = false) {
  if (reset) {
    query.value.page = { size: 50 };
    cursorHistory.value = [];
  }
  list.value = await api("/materials/search", "POST", query.value);
}
async function queryCategory() {
  filterField.value = "";
  query.value.filters = [];
  schema.value = query.value.categoryCode
    ? await api("/categories/" + query.value.categoryCode + "/schema")
    : null;
  await search(true);
}
async function applyFilter() {
  if (filterField.value) {
    const def = filterDef.value;
    let value: any = filterValue.value;
    if (["INTEGER", "DECIMAL"].includes(def.type))
      value =
        filterOp.value === "BETWEEN"
          ? filterValue.value.split(",").map(decimal)
          : decimal(filterValue.value);
    if (def.type === "BOOLEAN") value = filterValue.value === "true";
    if (filterOp.value === "IN") value = filterValue.value.split(",");
    query.value.filters = [
      {
        field: "attributes." + filterField.value,
        op: filterOp.value,
        value,
        unit: filterUnit.value || def.unit,
      },
    ];
  } else query.value.filters = [];
  await search(true);
}
async function nextPage() {
  cursorHistory.value.push(query.value.page.cursor);
  query.value.page = { size: 50, cursor: list.value.nextCursor };
  await search();
}
async function previousPage() {
  query.value.page = { size: 50, cursor: cursorHistory.value.pop() };
  await search();
}
async function exportList(format: string) {
  const result = await api("/materials/export", "POST", {
    query: { ...query.value, page: { size: 200 } },
    format,
  });
  if (result.status === "PENDING") {
    exportJobs.value = await api("/export-jobs");
    session.notice = "已建立后台导出任务，可刷新查看并下载。";
  } else {
    await download(result.id, result.name);
    session.notice =
      "已导出 " + result.rowCount + " 行，使用当前筛选与租户范围。";
  }
}
async function selectSchema(code: string) {
  schema.value = await api("/categories/" + code + "/schema");
  form.value.schemaVersionId = schema.value.id;
  form.value.categoryCode = code;
  form.value.attributes = {};
  for (const f of schema.value.bundle.attributes)
    if ("default" in f) form.value.attributes[f.code] = clone(f.default);
  preview.value = null;
  normalized.value = null;
  decisions.value = {};
  dirty.value = true;
  try {
    const d = await api(
      "/materials:decisions",
      "POST",
      materialBody(),
      undefined,
      true,
    );
    decisions.value = d.fields;
    normalized.value = d.normalized;
  } catch {}
}
async function newMaterial() {
  form.value = {
    categoryCode:
      query.value.categoryCode ||
      categories.value.find((c) => c.active && c.defaultSchemaId)?.code ||
      "",
    materialName: "",
    baseUnitCode: "pcs",
    attributes: {},
  };
  if (form.value.categoryCode) await selectSchema(form.value.categoryCode);
  modal.value = "material";
  dirty.value = false;
}
function cleanAttrs(attributes: any) {
  const out: any = {};
  for (const f of schema.value.bundle.attributes) {
    if (f.derived) continue;
    if (!(f.code in attributes)) continue;
    const v = attributes[f.code];
    out[f.code] = v;
    if (v !== null && ["DECIMAL", "INTEGER"].includes(f.type)) {
      if (v.value === undefined || v.value === "") out[f.code] = null;
      else
        out[f.code] = {
          value: decimal(String(v.value)),
          ...(f.unit ? { unit: v.unit || f.unit } : {}),
        };
    }
  }
  return out;
}
function materialBody() {
  const body = { ...form.value };
  if (!body.parseRuleVersionId) delete body.parseRuleVersionId;
  return { ...body, attributes: cleanAttrs(form.value.attributes) };
}
async function previewNumber() {
  const res = await api("/material-numbers:preview", "POST", materialBody());
  preview.value = res;
  normalized.value = (
    await api("/materials:validate", "POST", materialBody())
  ).attributes;
}
async function saveMaterial(submit = false) {
  let m;
  if (form.value.id)
    m = await api(
      "/materials/" + form.value.id,
      "PATCH",
      {
        materialName: form.value.materialName,
        baseUnitCode: form.value.baseUnitCode,
        attributes: cleanAttrs(form.value.attributes),
      },
      form.value.rowVersion,
    );
  else m = await api("/materials", "POST", materialBody());
  form.value = { ...m, categoryCode: form.value.categoryCode };
  dirty.value = false;
  session.notice = "草稿已持久化，正式料号尚未占用。";
  if (submit) {
    const req = await api(
      "/materials/" + m.id + "/change-requests",
      "POST",
      { kind: "NEW" },
      m.rowVersion,
    );
    await api("/requests/" + req.id + "/submit", "POST", {}, req.rowVersion);
    modal.value = "";
    session.notice = "已提交，需由另一名授权成员审批。";
  }
  await load();
}
async function openMaterial(id: string) {
  detail.value = await api("/materials/" + id);
  history.value = await api("/materials/" + id + "/history");
  modal.value = "detail";
}
async function editMaterial() {
  const m = detail.value;
  schema.value = m.schema;
  form.value = {
    ...m,
    categoryCode: categories.value.find((c) => c.id === m.categoryId)?.code,
    attributes: clone(m.attributes),
  };
  modal.value = "material";
  dirty.value = false;
}
async function copyMaterial() {
  const m = await api("/materials/" + detail.value.id + "/copy", "POST", {});
  await openMaterial(m.id);
  await editMaterial();
}
async function newChange(kind: string) {
  const reason = ask("请填写" + status(kind) + "原因");
  if (!reason) return;
  let candidate: any = {};
  if (kind === "CHANGE") {
    const name = ask("新的物料名称", detail.value.materialName);
    if (name === null) return;
    const description = ask("新的说明（留空保留；输入 null 尝试清空）", "");
    candidate = { materialName: name };
    if (description)
      candidate.attributes = {
        description: description === "null" ? null : description,
      };
  }
  const r = await api(
    "/materials/" + detail.value.id + "/change-requests",
    "POST",
    { kind, reason, candidate },
    detail.value.rowVersion,
  );
  await api("/requests/" + r.id + "/submit", "POST", {}, r.rowVersion);
  await openMaterial(detail.value.id);
  session.notice = "申请已提交，正式记录在审批期间保持原版本。";
}
async function approve(req: any, action: string) {
  const reason = action === "reject" ? ask("请输入驳回原因") : "";
  if (action === "reject" && !reason) return;
  await api(
    "/requests/" + req.id + "/" + action,
    "POST",
    { reason: reason || "" },
    req.rowVersion,
  );
  await load();
  session.notice =
    action === "approve"
      ? "审批已完成，本地生效与外部同步分别记录。"
      : "申请状态已更新。";
}
async function inspectRequest(req: any) {
  detail.value = await api("/materials/" + req.materialId);
  form.value = req;
  modal.value = "request";
}
async function loadModels() {
  if (!modelCode.value) return;
  schemas.value = await api("/categories/" + modelCode.value + "/schemas");
  data.value = await api("/metadata");
}
async function createCategory() {
  const code = ask("稳定类别 code，例如 NEW_CATEGORY");
  if (!code) return;
  const name = ask("类别名称");
  if (!name) return;
  await api("/categories", "POST", { code, name });
  categories.value = await api("/categories");
  modelCode.value = code;
  await loadModels();
}
async function categoryActive(cat: any) {
  await api(
    "/categories/" + cat.id,
    "PATCH",
    { active: !cat.active },
    cat.rowVersion,
  );
  categories.value = await api("/categories");
}
function editBundle(s: any = null) {
  editingSchema.value = s?.status === "DRAFT" ? s : null;
  bundle.value = s
    ? clone(s.bundle)
    : {
        attributes: [
          {
            code: "name",
            label: "名称",
            type: "STRING",
            required: true,
            group: "基本信息",
            searchable: true,
          },
        ],
        codeRule: {
          separator: "-",
          segments: [
            { type: "CONST", value: "MAT" },
            { type: "ATTR", field: "name" },
          ],
        },
        samples: [],
      };
  advancedJson.value = pretty(bundle.value);
  advanced.value = false;
  modal.value = "schema";
  dirty.value = false;
}
function addField() {
  bundle.value.attributes.push({
    code: "field" + (bundle.value.attributes.length + 1),
    label: "新属性",
    type: "STRING",
    group: "基本信息",
  });
  dirty.value = true;
}
async function saveBundle() {
  if (advanced.value) bundle.value = JSON.parse(advancedJson.value);
  let s;
  if (editingSchema.value)
    s = await api(
      "/schemas/" + editingSchema.value.id,
      "PATCH",
      { bundle: bundle.value },
      editingSchema.value.rowVersion,
    );
  else
    s = await api("/categories/" + modelCode.value + "/schemas", "POST", {
      bundle: bundle.value,
    });
  editingSchema.value = s;
  bundle.value = s.bundle;
  dirty.value = false;
  await loadModels();
  session.notice = "配置草稿已保存。可运行样本回归并提交审核。";
}
async function schemaAction(s: any, action: string) {
  let reason = "";
  if (action === "reject") {
    reason = ask("请输入驳回原因") || "";
    if (!reason) return;
  }
  const result = await api(
    "/schemas/" + s.id + "/" + action,
    "POST",
    { reason },
    s.rowVersion,
  );
  if (action === "test")
    session.notice = "配置回归通过：" + result.length + " 个样本";
  else session.notice = "配置状态已更新：" + status(result.status);
  await loadModels();
  categories.value = await api("/categories");
}
async function impact(s: any) {
  detail.value = await api("/schemas/" + s.id + "/impact");
  modal.value = "json";
}
async function saveMeta() {
  await api("/metadata", "POST", JSON.parse(metaJson.value));
  await loadModels();
  session.notice = "新元数据版本已保存。";
}
async function deactivateMeta(m: any) {
  await api("/metadata/" + m.id, "PATCH", { active: !m.active }, m.rowVersion);
  await loadModels();
}
async function saveReference() {
  const b = JSON.parse(referenceJson.value);
  const old = references.value.find((r) => r.type === b.type && r.id === b.id);
  await api("/references", "POST", b, old?.rowVersion);
  references.value = await api("/references");
  session.notice = "引用已保存。";
}
async function parse() {
  parseResult.value = await api("/material-numbers:parse", "POST", {
    materialNo: parseNo.value,
    parseRuleVersionId: parseVersion.value,
  });
}
async function upload() {
  if (!uploadFile.value) throw new Error("请选择文件");
  const fd = new FormData();
  fd.append("file", uploadFile.value);
  fd.append("categoryCode", modelCode.value);
  session.busy++;
  try {
    const res = await fetch("/api/v1/import-files", {
      method: "POST",
      headers: {
        "X-Tenant-Code": session.tenant,
        "Idempotency-Key": crypto.randomUUID(),
      },
      body: fd,
    });
    const b = await res.json();
    if (!res.ok) throw new Error(b.message);
    uploaded.value = b;
    sheet.value = b.sheets[0];
    await filePreview();
  } finally {
    session.busy--;
  }
}
async function filePreview() {
  const res = await api(
    "/import-files/" + uploaded.value.fileId + "/preview",
    "POST",
    { sheet: sheet.value },
  );
  importColumns.value = res.columns;
  importMap.value = {};
  const sc = await api("/categories/" + modelCode.value + "/schema");
  schema.value = sc;
  schemas.value = await api("/categories/" + modelCode.value + "/schemas");
  for (const col of importColumns.value) {
    const f = sc.bundle.attributes.find((f: any) => f.code === col);
    importMap.value[col] = {
      target: f
        ? "attributes." + f.code
        : [
              "materialName",
              "baseUnitCode",
              "id",
              "baseVersion",
              "externalId",
              "systemId",
              "legacyNo",
              "numberSource",
              "sourceEvidence",
            ].includes(col)
          ? col
          : "",
      unit: f?.unit || "",
    };
  }
  data.value = await api("/import-jobs");
}
async function startImport() {
  const mapping: any = {};
  Object.entries(importMap.value).forEach(([k, v]: any) => {
    if (v.target) mapping[k] = v;
  });
  const j = await api("/import-jobs", "POST", {
    fileId: uploaded.value.fileId,
    categoryCode: modelCode.value,
    schemaVersionId: schema.value.id,
    sheet: sheet.value,
    mapping,
    mode: importMode.value,
    ...(parseVersion.value ? { parseRuleVersionId: parseVersion.value } : {}),
  });
  importJob.value = await api(
    "/import-jobs/" + j.id + "/validate",
    "POST",
    {},
    j.rowVersion,
  );
  await load();
}
function changeImportCategory() {
  uploaded.value = null;
  uploadFile.value = null;
  importMap.value = {};
  importColumns.value = [];
  importJob.value = null;
  schema.value = null;
  schemas.value = [];
  parseVersion.value = "";
}
async function selectImportSchema() {
  const selected = schema.value.id;
  schema.value = await api("/schemas/" + selected);
}
async function importAction(action: string) {
  importJob.value = await api(
    "/import-jobs/" + importJob.value.id + "/" + action,
    "POST",
    { onlyPassed: true, reason: "用户操作" },
    importJob.value.rowVersion,
  );
  await load();
}
async function correctRow(row: any) {
  const text = ask("修正失败行的源列值（JSON）", pretty(row.source));
  if (!text) return;
  importJob.value = await api(
    "/import-jobs/" + importJob.value.id + "/rows/" + row.rowNo,
    "PATCH",
    { source: JSON.parse(text) },
    row.rowVersion,
  );
}
async function errorReport() {
  const f = await api(
    "/import-jobs/" + importJob.value.id + "/error-report",
    "POST",
    {},
  );
  await download(f.id, "导入错误.csv");
}
async function selectSystem(s: any) {
  selectedSystem.value = s;
  mappingList.value = await api("/integration-systems/" + s.id + "/mappings");
  selectedMapping.value = mappingList.value[0];
  mappingJson.value = pretty(selectedMapping.value?.config || { fields: [] });
}
async function testSystem(s: any) {
  detail.value = await api(
    "/integration-systems/" + s.id + "/test",
    "POST",
    {},
  );
  modal.value = "json";
  systems.value = await api("/integration-systems");
  selectedSystem.value = systems.value.find((x) => x.id === s.id);
}
async function toggleSystem(s: any) {
  const config = { ...s.config };
  if (!s.active) config.contractVerified = true;
  await api(
    "/integration-systems/" + s.id,
    "PATCH",
    { active: !s.active, config },
    s.rowVersion,
  );
  await load();
  selectedSystem.value = systems.value.find((x) => x.id === s.id);
  session.notice = "集成连接状态已更新。";
}
async function editSystem(s: any) {
  const text = ask(
    "编辑集成配置 JSON（地址、结果判定、超时与查询）",
    pretty(s.config),
  );
  if (!text) return;
  await api(
    "/integration-systems/" + s.id,
    "PATCH",
    { config: JSON.parse(text) },
    s.rowVersion,
  );
  await load();
}
async function createSystem() {
  const text = ask(
    "新建系统配置 JSON",
    pretty({
      code: "NEW_ERP",
      name: "新目标系统",
      direction: "BOTH",
      config: {
        url: "http://localhost:9090/targets/new-erp/materials",
        healthUrl: "http://localhost:9090/health",
        queryUrl: "http://localhost:9090/targets/new-erp/events/{eventId}",
        identityQueryUrl:
          "http://localhost:9090/targets/new-erp/materials/{externalId}",
        idempotent: true,
      },
    }),
  );
  if (!text) return;
  await api("/integration-systems", "POST", JSON.parse(text));
  await load();
}
async function saveMapping() {
  const m = await api(
    "/integration-systems/" + selectedSystem.value.id + "/mappings",
    "POST",
    { config: JSON.parse(mappingJson.value) },
  );
  await selectSystem(selectedSystem.value);
  selectedMapping.value = m;
  session.notice = "映射新草稿已保存，历史投递继续使用原版本。";
}
async function publishMapping(m: any) {
  await api("/mappings/" + m.id + "/publish", "POST", {}, m.rowVersion);
  systems.value = await api("/integration-systems");
  await selectSystem(
    systems.value.find((s) => s.id === selectedSystem.value.id),
  );
}
async function mappingPreview() {
  const text = ask(
    "输入物料快照 JSON",
    pretty({
      materialNo: "GC-7628-1270-HONGHE",
      materialName: "玻璃布",
      status: "ACTIVE",
      attributes: { width: { value: 1270, unit: "mm" } },
    }),
  );
  if (!text) return;
  detail.value = await api(
    "/mappings/" + selectedMapping.value.id + "/preview",
    "POST",
    { input: JSON.parse(text) },
  );
  modal.value = "json";
}
async function reconcile() {
  reconcileResult.value = await api(
    "/integration-systems/" + selectedSystem.value.id + "/reconcile",
    "POST",
    {},
  );
}
async function uploadIntegration() {
  if (!integrationUpload.value) return;
  const fd = new FormData();
  fd.append("file", integrationUpload.value);
  fd.append("systemId", selectedSystem.value.id);
  const r = await fetch("/api/v1/integration-files", {
    method: "POST",
    headers: {
      "X-Tenant-Code": session.tenant,
      "Idempotency-Key": crypto.randomUUID(),
    },
    body: fd,
  });
  const result = await r.json();
  if (!r.ok) throw new Error(result.message);
  detail.value = result;
  modal.value = "json";
}
async function inbound() {
  detail.value = await api(
    "/integration-systems/" + selectedSystem.value.id + "/inbound",
    "POST",
    JSON.parse(inboundJson.value),
  );
  modal.value = "json";
}
async function resend(d: any) {
  const reason = ask("填写核对及补发原因");
  if (!reason) return;
  const confirmedNotCreated =
    d.state === "RESULT_UNKNOWN"
      ? confirm("已通过目标查询或人工核实，确认请求未创建外部物料？")
      : false;
  await api("/deliveries/" + d.id + "/resend", "POST", {
    reason,
    confirmedNotCreated,
  });
  await load();
}
async function deliveryDetail(d: any) {
  detail.value = await api("/deliveries/" + d.id);
  modal.value = "delivery";
}
async function confirmDelivery(d: any) {
  const ext = ask("已核实成功的外部物料 ID");
  if (!ext) return;
  const reason = ask("核对依据及原因");
  if (!reason) return;
  await api("/deliveries/" + d.id + "/confirm", "POST", {
    success: true,
    externalId: ext,
    reason,
  });
  await load();
  modal.value = "";
}
async function saveSettings() {
  settings.value = await api(
    "/tenant/settings",
    "PATCH",
    settings.value.settings,
    settings.value.rowVersion,
  );
  session.notice = "租户设置已保存。";
}
function editMember(m: any = null) {
  memberForm.value = m
    ? clone(m)
    : {
        userId: tenantUsers.value[0]?.id,
        roles: ["READER"],
        categoryScope: ["*"],
        active: true,
      };
  modal.value = "member";
}
async function saveMember() {
  const m = memberForm.value;
  await api(
    "/tenant/members" + (m.id ? "/" + m.id : ""),
    m.id ? "PATCH" : "POST",
    m,
    m.rowVersion,
  );
  modal.value = "";
  await load();
}
async function editRole(role: any) {
  const text = ask(
    "角色名称与动作 JSON",
    pretty(role || { code: "CUSTOM", name: "自定义角色", actions: ["READ"] }),
  );
  if (!text) return;
  const b = JSON.parse(text);
  await api(
    "/tenant/roles" + (role ? "/" + role.code : ""),
    role ? "PATCH" : "POST",
    b,
    role?.rowVersion,
  );
  await load();
}
async function platformAction(t: any, action: string) {
  const reason = ask("请填写租户状态变更原因");
  if (!reason) return;
  await api(
    "/platform/tenants/" + t.id + "/" + action,
    "POST",
    { reason },
    t.rowVersion,
  );
  await load();
  tenantList.value = await api("/me/tenants");
}
async function newTenant() {
  const code = ask("稳定租户 code");
  if (!code) return;
  const name = ask("租户名称");
  if (!name) return;
  await api("/platform/tenants", "POST", {
    code,
    name,
    adminUserId: session.user.id,
  });
  await load();
  tenantList.value = await api("/me/tenants");
}
async function showIdentities(system: any) {
  detail.value = {
    system,
    rows: await api("/integration-systems/" + system.id + "/identities"),
  };
  modal.value = "identities";
}
async function bindIdentity(old?: any) {
  const system = detail.value.system;
  const input = ask(
    "登记或核实变更外部身份；填写原因与物料UUID",
    pretty(
      old
        ? {
            materialId: old.materialId,
            externalId: old.externalId,
            previousExternalId: old.externalId,
            baseVersion: old.rowVersion,
            reason: "",
          }
        : { materialId: "", externalId: "", reason: "" },
    ),
  );
  if (!input) return;
  await api(
    "/integration-systems/" + system.id + "/identities",
    "POST",
    JSON.parse(input),
  );
  await showIdentities(system);
  session.notice = "外部身份已登记，操作原因与版本已保存。";
}
function dismiss() {
  if (!leave()) return;
  modal.value = "";
  dirty.value = false;
}
watch(
  () => form.value,
  () => {
    if (modal.value === "material") dirty.value = true;
  },
  { deep: true, flush: "sync" },
);
let timer: ReturnType<typeof setInterval>;
let decisionTimer: ReturnType<typeof setTimeout>;
watch(
  () => form.value.attributes,
  () => {
    if (modal.value !== "material" || !schema.value) return;
    clearTimeout(decisionTimer);
    decisionTimer = setTimeout(async () => {
      if (modal.value !== "material" || !schema.value) return;
      try {
        const d = await api(
          "/materials:decisions",
          "POST",
          materialBody(),
          undefined,
          true,
        );
        decisions.value = d.fields;
        normalized.value = d.normalized;
      } catch (e: any) {
        if (e.message !== "CONTEXT_CHANGED") {
        }
      }
    }, 350);
  },
  { deep: true },
);
onMounted(() => {
  run(boot);
  timer = setInterval(() => {
    if (
      page.value === "imports" &&
      importJob.value &&
      ["VALIDATING", "COMMITTING"].includes(importJob.value.status)
    )
      run(async () => {
        importJob.value = await api("/import-jobs/" + importJob.value.id);
      });
  }, 1800);
});
onBeforeUnmount(() => {
  clearInterval(timer);
  clearTimeout(decisionTimer);
});
window.addEventListener("beforeunload", (e) => {
  if (dirty.value) {
    e.preventDefault();
    e.returnValue = "";
  }
});
</script>
<template>
  <div v-if="!session.user" class="login">
    <div class="login-card">
      <div class="brand-mark">物</div>
      <p class="eyebrow">MATERIAL DATA MANAGEMENT</p>
      <h1>物序<span>物料主数据平台</span></h1>
      <p class="muted">
        让每一份物料数据有据可依。<br />配置、审批与企业系统协同，在一个工作空间完成。
      </p>
      <label
        >开发演示用户<select v-model="userCode" aria-label="开发演示用户">
          <option value="">请选择已登记用户</option>
          <option v-for="u in users" :key="u.id" :value="u.code">
            {{ u.name }}
          </option>
        </select></label
      ><button
        class="primary wide"
        :disabled="!userCode || !!session.busy"
        @click="run(() => login(userCode))"
      >
        进入工作空间 →</button
      ><button class="text" :disabled="!!session.busy" @click="run(seed)">
        首次使用：初始化专用演示数据</button
      ><small>演示登录仅在开发配置启用；权限来自后端成员登记。</small>
    </div>
  </div>
  <div v-else class="app-shell">
    <aside>
      <div class="brand">
        <div class="brand-mark">物</div>
        <div><strong>物序</strong><small>物料主数据平台</small></div>
      </div>
      <p class="nav-label">工作空间</p>
      <nav>
        <template v-for="p in pages" :key="p[0]"
          ><button
            v-if="p[0] !== 'platform' || session.user.platformAdmin"
            :class="{ active: page === p[0] }"
            @click="run(() => navigate(p[0]))"
          >
            <span>{{ p[1] }}</span
            >{{ p[2]
            }}<b v-if="p[0] === 'requests' && data.pendingRequests">{{
              data.pendingRequests
            }}</b>
          </button></template
        >
      </nav>
      <div class="aside-footer">
        <span class="live-dot"></span>开发环境 · 数据持久化<small
          >PRD V2.0 · 本地业务验证</small
        >
      </div>
    </aside>
    <main>
      <header>
        <div class="breadcrumb">工作空间 <span>/</span> {{ title }}</div>
        <div class="header-controls">
          <label class="compact"
            >当前租户<select
              aria-label="当前租户"
              :disabled="!!session.busy"
              :value="session.tenant"
              @change="
                run(() =>
                  changeTenant(($event.target as HTMLSelectElement).value),
                )
              "
            >
              <option v-for="t in tenantList" :key="t.id" :value="t.code">
                {{ t.name }} · {{ t.code }}
              </option>
            </select></label
          ><label class="compact"
            >演示成员<select
              aria-label="演示成员"
              :disabled="!!session.busy"
              :value="userCode"
              @change="
                run(() => login(($event.target as HTMLSelectElement).value))
              "
            >
              <option v-for="u in users" :key="u.id" :value="u.code">
                {{ u.name }}
              </option>
            </select></label
          >
          <div class="avatar">{{ session.user.name.slice(0, 1) }}</div>
        </div>
      </header>
      <div v-if="currentTenant?.status !== 'ACTIVE'" class="banner warning">
        {{ currentTenant?.name }}当前{{
          status(currentTenant?.status)
        }}。可查看保留数据与历史；业务修改及后续任务已暂停。
      </div>
      <div class="page-content">
        <div class="page-heading">
          <div>
            <p class="eyebrow">{{ currentTenant?.name }} / MASTER DATA</p>
            <h1>{{ title }}</h1>
            <p class="muted">
              {{
                page === "dashboard"
                  ? "掌握业务进展，让主数据持续可靠。"
                  : page === "materials"
                    ? "一个稳定身份，一份可追溯的物料事实。"
                    : "当前工作租户：" + currentTenant?.name
              }}
            </p>
          </div>
          <button :disabled="!!session.busy" @click="run(load)">
            ↻ 刷新数据
          </button>
        </div>
        <div v-if="session.error" role="alert" class="banner error">
          <strong>操作未完成</strong>
          <pre>{{ session.error }}</pre>
          <button @click="session.error = ''">关闭</button>
        </div>
        <div v-if="session.notice" role="status" class="banner success">
          {{ session.notice }}<button @click="session.notice = ''">×</button>
        </div>
        <div
          v-if="session.busy"
          class="loading-line"
          aria-label="正在加载"
        ></div>
        <template v-if="page === 'dashboard'"
          ><div class="hero">
            <div>
              <span class="pill">当前租户 · {{ currentTenant?.code }}</span>
              <h2>把物料数据，<br />变成可信的业务基础。</h2>
              <p>模型定义规格，审批保证分工，版本保存每一次变化。</p>
              <button
                class="primary"
                v-if="can('CREATE')"
                @click="run(newMaterial)"
              >
                ＋ 新建物料</button
              ><button
                v-if="can('APPROVE')"
                @click="run(() => navigate('requests'))"
              >
                查看待审批申请 →
              </button>
            </div>
            <div class="hero-art">
              <div class="orbit"></div>
              <div class="data-card">稳定身份<span>UUID</span></div>
              <div class="data-card">配置语义<span>不可变版本</span></div>
              <div class="data-card">
                业务闭环<span>审批 → 生效 → 同步</span>
              </div>
            </div>
          </div>
          <div class="stats">
            <article>
              <small>物料总数</small><strong>{{ data.materials ?? "—" }}</strong
              ><span>当前授权类别</span>
            </article>
            <article>
              <small>物料草稿</small><strong>{{ data.drafts ?? "—" }}</strong
              ><span>正式号码尚未占用</span>
            </article>
            <article>
              <small>待审批申请</small
              ><strong>{{ data.pendingRequests ?? "—" }}</strong
              ><span>需要其他成员审核</span>
            </article>
            <article>
              <small>同步异常 / 待确认</small
              ><strong>{{
                Array.isArray(data.deliveries)
                  ? data.deliveries.filter((d: any) =>
                      [
                        "FAILED",
                        "RESULT_UNKNOWN",
                        "WAIT_CONFIRMATION",
                      ].includes(d.state),
                    ).length
                  : "—"
              }}</strong
              ><span>各目标独立处理</span>
            </article>
          </div>
          <div class="two-col">
            <section class="panel">
              <h2>待处理业务</h2>
              <div class="action-row" @click="run(() => navigate('requests'))">
                <span class="icon-square">✓</span>
                <div>
                  <strong>审批与版本比较</strong
                  ><small>查看申请、基础版本和候选变化</small>
                </div>
                <span>→</span>
              </div>
              <div class="action-row" @click="run(() => navigate('imports'))">
                <span class="icon-square">⇧</span>
                <div>
                  <strong>导入与错误修正</strong
                  ><small
                    >{{
                      data.imports?.length || 0
                    }}
                    个导入任务，预检后再确认</small
                  >
                </div>
                <span>→</span>
              </div>
              <div
                class="action-row"
                @click="run(() => navigate('integrations'))"
              >
                <span class="icon-square">⇄</span>
                <div>
                  <strong>投递与对账</strong
                  ><small>失败、待确认与未知结果分别处理</small>
                </div>
                <span>→</span>
              </div>
            </section>
            <section class="panel">
              <h2>运行观测</h2>
              <dl>
                <dt>数据库锁等待</dt>
                <dd>{{ data.databaseLockWaits ?? "未采集" }}</dd>
                <dt>接口 P95 / 错误率</dt>
                <dd>{{ data.apiLatencyP95 }} ms / {{ data.apiErrorRate }} %</dd>
                <dt>规则耗时</dt>
                <dd>{{ data.ruleDuration }} ms</dd>
                <dt>最老未成功事件</dt>
                <dd>{{ data.oldestPendingEvent?.time || "暂无" }}</dd>
                <dt>容量与生产指标</dt>
                <dd>{{ data.capacity }}</dd>
              </dl>
            </section>
          </div></template
        >
        <template v-else-if="page === 'materials'"
          ><section class="panel">
            <div class="toolbar">
              <input
                aria-label="名称查询"
                v-model="query.name"
                placeholder="搜索物料名称…"
                @keyup.enter="run(() => search(true))"
              /><select
                aria-label="类别筛选"
                v-model="query.categoryCode"
                @change="run(queryCategory)"
              >
                <option value="">全部授权类别</option>
                <option v-for="c in categories" :key="c.id" :value="c.code">
                  {{ c.name }}
                </option></select
              ><select aria-label="物料状态" v-model="query.status">
                <option value="">全部状态</option>
                <option
                  v-for="s in ['DRAFT', 'IN_REVIEW', 'ACTIVE', 'INACTIVE']"
                  :key="s"
                  :value="s"
                >
                  {{ status(s) }}
                </option></select
              ><button @click="run(() => search(true))">查询</button>
              <div class="spacer"></div>
              <button
                v-if="can('EXPORT')"
                @click="run(() => exportList('CSV'))"
              >
                导出 CSV</button
              ><button
                v-if="can('EXPORT')"
                @click="run(() => exportList('XLSX'))"
              >
                导出 XLSX</button
              ><button
                v-if="can('CREATE')"
                class="primary"
                @click="run(newMaterial)"
              >
                ＋ 新建物料
              </button>
            </div>
            <div v-if="schema && query.categoryCode" class="toolbar filters">
              <small>属性筛选</small
              ><select v-model="filterField" aria-label="筛选属性">
                <option value="">不筛选属性</option>
                <option
                  v-for="f in fields.filter((f: any) => f.searchable)"
                  :key="f.code"
                  :value="f.code"
                >
                  {{ f.label }}
                </option></select
              ><select v-model="filterOp">
                <option
                  v-for="op in [
                    'EQ',
                    'IN',
                    ...(['INTEGER', 'DECIMAL', 'DATE'].includes(filterDef?.type)
                      ? ['GTE', 'LTE', 'BETWEEN']
                      : filterDef?.type === 'STRING'
                        ? ['CONTAINS']
                        : []),
                  ]"
                  :key="op"
                >
                  {{ op }}
                </option></select
              ><input
                v-model="filterValue"
                aria-label="筛选值"
                placeholder="值；区间/多选以逗号分隔"
              /><input
                v-if="filterDef?.unit"
                v-model="filterUnit"
                :placeholder="filterDef.unit"
                aria-label="筛选单位"
              /><button @click="run(applyFilter)">应用条件</button
              ><select
                :value="query.sort?.[0]?.field || 'updatedAt'"
                @change="
                  query.sort = [
                    {
                      field: ($event.target as HTMLSelectElement).value,
                      direction: 'DESC',
                    },
                  ];
                  run(() => search(true));
                "
              >
                <option value="updatedAt">更新时间排序</option>
                <option value="materialName">名称排序</option>
                <option
                  v-for="f in fields.filter((f: any) => f.sortable)"
                  :key="f.code"
                  :value="'attributes.' + f.code"
                >
                  {{ f.label }}排序
                </option>
              </select>
            </div>
            <div class="table-wrap">
              <table>
                <thead>
                  <tr>
                    <th>物料 / 正式料号</th>
                    <th>类别</th>
                    <th>业务状态</th>
                    <th>版本</th>
                    <th>更新时间</th>
                    <th></th>
                  </tr>
                </thead>
                <tbody>
                  <tr v-for="m in list.items" :key="m.id">
                    <td>
                      <strong>{{ m.materialName }}</strong
                      ><small class="mono">{{
                        m.materialNo || "草稿 · 尚未占号"
                      }}</small>
                    </td>
                    <td>{{ m.categoryName }}</td>
                    <td>
                      <span class="badge" :class="m.status">{{
                        status(m.status)
                      }}</span>
                    </td>
                    <td>v{{ m.rowVersion }}</td>
                    <td>{{ new Date(m.updatedAt).toLocaleString() }}</td>
                    <td>
                      <button
                        class="text"
                        @click="run(() => openMaterial(m.id))"
                      >
                        查看详情 →
                      </button>
                    </td>
                  </tr>
                </tbody>
              </table>
            </div>
            <div v-if="!list.items?.length" class="empty">
              暂无匹配物料。调整筛选，或创建第一份草稿。
            </div>
            <div v-if="can('EXPORT')" class="toolbar">
              <button
                @click="
                  run(async () => {
                    exportJobs = await api('/export-jobs');
                  })
                "
              >
                刷新后台导出任务
              </button>
              <div v-for="j in exportJobs" :key="j.id">
                <span
                  >{{ status(j.status) }} ·
                  {{ j.error || j.id.slice(0, 8) }}</span
                ><button
                  v-if="j.fileId"
                  @click="
                    download(
                      j.fileId,
                      '物料导出' +
                        (j.config.format === 'XLSX' ? '.xlsx' : '.csv'),
                    )
                  "
                >
                  下载结果
                </button>
              </div>
            </div>
            <div class="pagination">
              <small>本页 {{ list.items?.length || 0 }} 条 · 稳定游标分页</small
              ><button
                :disabled="!cursorHistory.length"
                @click="run(previousPage)"
              >
                上一页</button
              ><button :disabled="!list.hasMore" @click="run(nextPage)">
                下一页
              </button>
            </div>
          </section></template
        >
        <template v-else-if="page === 'requests'"
          ><section class="panel">
            <h2>
              业务审批申请
              <span class="muted">{{
                Array.isArray(data) ? data.length : 0
              }}</span>
            </h2>
            <p class="muted">
              申请人不能审批自己的申请。正式变更期间，原正式版本继续有效。
            </p>
            <div class="table-wrap">
              <table>
                <thead>
                  <tr>
                    <th>物料</th>
                    <th>申请类型</th>
                    <th>状态</th>
                    <th>基础 / 申请版本</th>
                    <th>原因</th>
                    <th>操作</th>
                  </tr>
                </thead>
                <tbody>
                  <tr v-for="r in Array.isArray(data) ? data : []" :key="r.id">
                    <td>
                      <strong>{{ r.materialName }}</strong
                      ><small>{{ r.materialNo || "新建草稿" }}</small>
                    </td>
                    <td>{{ status(r.kind) }}</td>
                    <td>
                      <span class="badge" :class="r.state">{{
                        status(r.state)
                      }}</span>
                    </td>
                    <td>v{{ r.baseVersion }} / v{{ r.rowVersion }}</td>
                    <td>{{ r.reason || "首次建立" }}</td>
                    <td>
                      <button @click="run(() => inspectRequest(r))">
                        比较差异</button
                      ><template v-if="r.state === 'REVIEW' && can('APPROVE')"
                        ><button
                          class="primary"
                          :disabled="!!session.busy"
                          @click="run(() => approve(r, 'approve'))"
                        >
                          批准</button
                        ><button @click="run(() => approve(r, 'reject'))">
                          驳回
                        </button></template
                      ><button
                        v-if="
                          r.state === 'REVIEW' &&
                          r.submittedBy === session.user.id
                        "
                        @click="run(() => approve(r, 'withdraw'))"
                      >
                        撤回</button
                      ><button
                        v-if="
                          r.state === 'DRAFT' &&
                          r.submittedBy === session.user.id
                        "
                        @click="run(() => approve(r, 'submit'))"
                      >
                        提交申请</button
                      ><button
                        v-if="
                          r.state === 'DRAFT' &&
                          r.submittedBy === session.user.id
                        "
                        @click="run(() => approve(r, 'cancel'))"
                      >
                        取消
                      </button>
                    </td>
                  </tr>
                </tbody>
              </table>
            </div>
            <div v-if="!data.length" class="empty">
              暂无申请。保存物料草稿后可提交审批。
            </div>
          </section></template
        >
        <template v-else-if="page === 'models'"
          ><div class="toolbar">
            <select
              aria-label="模型类别"
              v-model="modelCode"
              @change="run(loadModels)"
            >
              <option v-for="c in categories" :key="c.id" :value="c.code">
                {{ c.name }} · {{ c.code }}
              </option></select
            ><button v-if="can('DESIGN')" @click="run(createCategory)">
              ＋ 新增类别</button
            ><button v-if="can('DESIGN')" class="primary" @click="editBundle()">
              ＋ 新配置草稿</button
            ><button
              v-if="
                can('DESIGN') && categories.find((c) => c.code === modelCode)
              "
              @click="
                run(() =>
                  categoryActive(categories.find((c) => c.code === modelCode)),
                )
              "
            >
              {{
                categories.find((c) => c.code === modelCode)?.active
                  ? "停用类别"
                  : "启用类别"
              }}
            </button>
          </div>
          <section class="panel">
            <h2>配置发布包</h2>
            <p class="muted">
              属性、字典、单位、校验与编码规则随发布固定。历史物料保持绑定版本。
            </p>
            <table>
              <thead>
                <tr>
                  <th>配置版本</th>
                  <th>状态</th>
                  <th>属性数</th>
                  <th>操作</th>
                </tr>
              </thead>
              <tbody>
                <tr v-for="s in schemas" :key="s.id">
                  <td>
                    <strong>v{{ s.version }}</strong
                    ><small class="mono">{{ s.id.slice(0, 8) }}…</small>
                  </td>
                  <td>
                    <span class="badge" :class="s.status">{{
                      status(s.status)
                    }}</span>
                  </td>
                  <td>{{ s.bundle.attributes.length }}</td>
                  <td>
                    <button @click="impact(s)">影响比较</button
                    ><button v-if="can('DESIGN')" @click="editBundle(s)">
                      {{
                        s.status === "DRAFT" ? "编辑草稿" : "复制为新版本"
                      }}</button
                    ><button @click="run(() => schemaAction(s, 'test'))">
                      样本回归</button
                    ><button
                      v-if="s.status === 'DRAFT' && can('DESIGN')"
                      class="primary"
                      @click="run(() => schemaAction(s, 'submit'))"
                    >
                      提交审核</button
                    ><template v-if="s.status === 'REVIEW' && can('PUBLISH')"
                      ><button
                        class="primary"
                        @click="run(() => schemaAction(s, 'approve'))"
                      >
                        批准发布</button
                      ><button @click="run(() => schemaAction(s, 'reject'))">
                        驳回
                      </button></template
                    ><button
                      v-if="
                        s.status === 'REVIEW' &&
                        s.submittedBy === session.user.id
                      "
                      @click="run(() => schemaAction(s, 'withdraw'))"
                    >
                      撤回</button
                    ><button
                      v-if="s.status === 'PUBLISHED' && can('PUBLISH')"
                      @click="run(() => schemaAction(s, 'retire'))"
                    >
                      停用版本</button
                    ><button
                      v-if="s.status === 'RETIRED' && can('DESIGN')"
                      @click="run(() => schemaAction(s, 'reactivate'))"
                    >
                      申请重新启用
                    </button>
                  </td>
                </tr>
              </tbody>
            </table>
            <div v-if="!schemas.length" class="empty">
              请创建配置草稿，配置属性及规则后提交审核。
            </div>
          </section>
          <div class="two-col">
            <section class="panel">
              <h2>复用属性、字典与单位</h2>
              <p class="muted">
                新建元数据版本；Schema 可通过 definitionId 或 dictionaryCode
                绑定。高级结构采用 JSON 配置。
              </p>
              <div class="meta-list">
                <div v-for="m in Array.isArray(data) ? data : []" :key="m.id">
                  <span
                    ><strong>{{ m.code }}</strong
                    ><small
                      >{{ m.kind }} · v{{ m.version }} ·
                      {{ m.active ? "启用" : "停用" }}</small
                    ></span
                  ><button
                    v-if="can('DESIGN')"
                    @click="
                      metaJson = pretty({
                        kind: m.kind,
                        code: m.code,
                        data: m.data,
                      })
                    "
                  >
                    复制新版本</button
                  ><button
                    v-if="can('DESIGN')"
                    @click="run(() => deactivateMeta(m))"
                  >
                    {{ m.active ? "停用" : "启用" }}
                  </button>
                </div>
              </div>
              <textarea
                v-if="can('DESIGN')"
                v-model="metaJson"
                rows="6"
                aria-label="元数据配置 JSON"
              ></textarea
              ><button v-if="can('DESIGN')" @click="run(saveMeta)">
                保存元数据新版本
              </button>
            </section>
            <section class="panel">
              <h2>通用业务引用</h2>
              <div class="meta-list">
                <div v-for="r in references" :key="r.type + r.id">
                  <span
                    ><strong>{{ r.name }}</strong
                    ><small
                      >{{ r.type }} / {{ r.id }} ·
                      {{ r.active ? "启用" : "停用" }}</small
                    ></span
                  ><button
                    v-if="can('ADMIN')"
                    @click="referenceJson = pretty(r)"
                  >
                    编辑
                  </button>
                </div>
              </div>
              <template v-if="can('ADMIN')">
                <textarea
                  v-model="referenceJson"
                  rows="5"
                  aria-label="引用配置 JSON"
                ></textarea
                ><button @click="run(saveReference)">保存引用</button></template
              >
              <h3>历史料号解析</h3>
              <label
                >明确选择解析版本<select v-model="parseVersion">
                  <option value="">请选择</option>
                  <option
                    v-for="s in schemas.filter((s) => s.bundle.parseRule)"
                    :key="s.id"
                    :value="s.id"
                  >
                    v{{ s.version }} · {{ status(s.status) }}
                  </option>
                </select></label
              ><input v-model="parseNo" placeholder="输入旧料号" /><button
                :disabled="!parseVersion"
                @click="run(parse)"
              >
                仅解析，不写入物料
              </button>
              <pre v-if="parseResult">{{ pretty(parseResult) }}</pre>
            </section>
          </div></template
        >
        <template v-else-if="page === 'imports'"
          ><section class="panel">
            <h2>上传与全量预检</h2>
            <p class="muted">
              UTF-8 CSV / XLSX ·
              最大20MB、50,000行、200列。公式须先转为确定值。成功行表示草稿或申请已创建，仍需审批。
            </p>
            <div class="toolbar">
              <select
                v-model="modelCode"
                aria-label="导入类别"
                @change="changeImportCategory"
              >
                <option
                  v-for="c in categories.filter(
                    (c) => c.active && c.defaultSchemaId,
                  )"
                  :key="c.id"
                  :value="c.code"
                >
                  {{ c.name }}
                </option></select
              ><input
                type="file"
                accept=".csv,.xlsx"
                aria-label="导入文件"
                @change="
                  uploadFile =
                    ($event.target as HTMLInputElement).files?.[0] || null
                "
              /><button
                class="primary"
                :disabled="!!session.busy || !uploadFile"
                @click="run(upload)"
              >
                上传文件
              </button>
            </div>
            <template v-if="uploaded"
              ><div class="toolbar">
                <strong>{{ uploaded.name }}</strong
                ><label
                  >导入Schema版本<select
                    v-if="schema"
                    v-model="schema.id"
                    @change="run(selectImportSchema)"
                  >
                    <option
                      v-for="s in schemas.filter(
                        (s) => s.status === 'PUBLISHED',
                      )"
                      :key="s.id"
                      :value="s.id"
                    >
                      v{{ s.version }} · {{ status(s.status) }}
                    </option>
                  </select></label
                ><select v-model="sheet" @change="run(filePreview)">
                  <option v-for="s in uploaded.sheets" :key="s">
                    {{ s }}
                  </option></select
                ><label
                  >实际解析版本（可选）<select v-model="parseVersion">
                    <option value="">不解析</option>
                    <option
                      v-for="s in schemas.filter((s) => s.bundle.parseRule)"
                      :key="s.id"
                      :value="s.id"
                    >
                      v{{ s.version }} · {{ s.status }}
                    </option>
                  </select></label
                ><select v-model="importMode">
                  <option value="CREATE">创建草稿</option>
                  <option value="UPDATE">创建正式变更申请</option>
                </select>
              </div>
              <table>
                <thead>
                  <tr>
                    <th>源列</th>
                    <th>目标字段</th>
                    <th>源单位</th>
                  </tr>
                </thead>
                <tbody>
                  <tr v-for="col in importColumns" :key="col">
                    <td>{{ col }}</td>
                    <td>
                      <select v-model="importMap[col].target">
                        <option value="">不映射</option>
                        <option
                          v-for="f in [
                            'materialName',
                            'baseUnitCode',
                            'id',
                            'baseVersion',
                            'systemId',
                            'externalId',
                            'legacyNo',
                            'numberSource',
                            'sourceEvidence',
                            ...fields
                              .filter((f: any) => !f.derived)
                              .map((f: any) => 'attributes.' + f.code),
                          ]"
                          :key="f"
                          :value="f"
                        >
                          {{ f }}
                        </option>
                      </select>
                    </td>
                    <td>
                      <input
                        v-model="importMap[col].unit"
                        placeholder="如 mm"
                      />
                    </td>
                  </tr>
                </tbody>
              </table>
              <button
                class="primary"
                :disabled="!!session.busy"
                @click="run(startImport)"
              >
                建立任务并全量预检
              </button></template
            >
          </section>
          <section class="panel">
            <h2>持久化导入任务</h2>
            <table>
              <thead>
                <tr>
                  <th>任务</th>
                  <th>状态</th>
                  <th>创建时间</th>
                  <th></th>
                </tr>
              </thead>
              <tbody>
                <tr v-for="j in Array.isArray(data) ? data : []" :key="j.id">
                  <td class="mono">
                    {{ j.id.slice(0, 8) }} · {{ j.categoryCode }}
                  </td>
                  <td>
                    <span class="badge" :class="j.status">{{
                      status(j.status)
                    }}</span>
                  </td>
                  <td>{{ new Date(j.createdAt).toLocaleString() }}</td>
                  <td>
                    <button
                      @click="
                        run(
                          async () =>
                            (importJob = await api('/import-jobs/' + j.id)),
                        )
                      "
                    >
                      查看行结果
                    </button>
                  </td>
                </tr>
              </tbody>
            </table>
          </section>
          <section v-if="importJob" class="panel">
            <h2>
              任务结果
              <span class="badge" :class="importJob.status">{{
                status(importJob.status)
              }}</span>
            </h2>
            <div class="toolbar">
              <span
                v-for="count in importJob.counts"
                :key="count.state"
                class="pill"
                >{{ status(count.state) }} {{ count.count }}</span
              ><button
                @click="
                  run(
                    async () =>
                      (importJob = await api('/import-jobs/' + importJob.id)),
                  )
                "
              >
                刷新结果</button
              ><button
                v-if="
                  ['UPLOADED', 'READY', 'PARTIAL_FAILED'].includes(
                    importJob.status,
                  )
                "
                @click="run(() => importAction('validate'))"
              >
                重新预检失败行</button
              ><button
                v-if="['READY', 'PARTIAL_FAILED'].includes(importJob.status)"
                class="primary"
                :disabled="!!session.busy"
                @click="run(() => importAction('commit'))"
              >
                确认提交通过行</button
              ><button
                v-if="['VALIDATING', 'COMMITTING'].includes(importJob.status)"
                @click="run(() => importAction('pause'))"
              >
                暂停</button
              ><button
                v-if="importJob.status === 'PAUSED'"
                @click="run(() => importAction('resume'))"
              >
                恢复</button
              ><button
                v-if="!['COMPLETED', 'CANCELLED'].includes(importJob.status)"
                @click="run(() => importAction('cancel'))"
              >
                取消未处理行</button
              ><button @click="run(errorReport)">下载错误报告</button>
            </div>
            <table>
              <thead>
                <tr>
                  <th>行号</th>
                  <th>源数据 / 错误</th>
                  <th>结果</th>
                  <th>下一步</th>
                </tr>
              </thead>
              <tbody>
                <tr v-for="r in importJob.rows" :key="r.rowNo">
                  <td>{{ r.rowNo }}</td>
                  <td>
                    <pre>{{ pretty(r.source) }}</pre>
                    <p class="error-text" v-if="r.errors?.length">
                      {{ pretty(r.errors) }}
                    </p>
                  </td>
                  <td>
                    <span class="badge" :class="r.state">{{
                      r.state === "SUCCEEDED"
                        ? "草稿/申请已创建"
                        : status(r.state)
                    }}</span
                    ><small v-if="r.materialId"
                      >草稿/申请已创建；审批与同步另行处理</small
                    >
                  </td>
                  <td>
                    <button
                      v-if="['ERROR', 'FAILED', 'PENDING'].includes(r.state)"
                      @click="run(() => correctRow(r))"
                    >
                      修正此失败行</button
                    ><button
                      v-if="r.materialId"
                      @click="run(() => openMaterial(r.materialId))"
                    >
                      查看物料
                    </button>
                  </td>
                </tr>
              </tbody>
            </table>
          </section></template
        >
        <template v-else-if="page === 'integrations'"
          ><div class="toolbar">
            <p class="muted">以下连接为本地模拟；真实 ERP UAT 尚未完成。</p>
            <div class="spacer"></div>
            <button @click="run(createSystem)">＋ 新增集成系统</button>
          </div>
          <div class="system-grid">
            <article v-for="s in systems" :key="s.id" class="panel">
              <div class="row">
                <span class="icon-square">⇄</span
                ><span class="badge" :class="s.active ? 'ACTIVE' : 'DRAFT'">{{
                  s.active ? "已启用" : "未启用"
                }}</span>
              </div>
              <h2>{{ s.name }}</h2>
              <small
                >{{ s.code }} · {{ s.direction }} ·
                {{ s.config.transport || "REST" }}</small
              >
              <p class="mono">{{ s.config.url }}</p>
              <div class="toolbar">
                <button @click="run(() => selectSystem(s))">配置映射</button
                ><button @click="run(() => editSystem(s))">连接配置</button
                ><button @click="run(() => showIdentities(s))">外部身份</button
                ><button @click="run(() => testSystem(s))">
                  测试连接与契约</button
                ><button @click="run(() => toggleSystem(s))">
                  {{ s.active ? "停用" : "启用" }}</button
                ><button
                  @click="
                    run(async () => {
                      await api(
                        '/integration-systems/' + s.id,
                        'PATCH',
                        { paused: !s.paused },
                        s.rowVersion,
                      );
                      await load();
                    })
                  "
                >
                  {{ s.paused ? "恢复投递" : "暂停投递" }}
                </button>
              </div>
            </article>
          </div>
          <section v-if="selectedSystem" class="panel">
            <h2>{{ selectedSystem.name }} · 映射版本</h2>
            <div class="toolbar">
              <span v-for="m in mappingList" :key="m.id"
                ><button
                  @click="
                    selectedMapping = m;
                    mappingJson = pretty(m.config);
                  "
                >
                  v{{ m.version }} · {{ status(m.state) }}</button
                ><button
                  v-if="m.state === 'DRAFT'"
                  class="primary"
                  @click="run(() => publishMapping(m))"
                >
                  发布
                </button></span
              >
            </div>
            <p class="muted">
              字段路径、常量、字典、单位和有限格式转换；已发布版本只读，新版本另存。
            </p>
            <textarea
              v-model="mappingJson"
              rows="10"
              aria-label="映射配置 JSON"
            ></textarea>
            <div class="toolbar">
              <button @click="run(saveMapping)">另存映射草稿</button
              ><button
                :disabled="!selectedMapping"
                @click="run(mappingPreview)"
              >
                映射预览</button
              ><button class="primary" @click="run(reconcile)">
                运行实际对账
              </button>
            </div>
            <pre v-if="reconcileResult">{{ pretty(reconcileResult) }}</pre>
            <details>
              <summary>JSONL 文件入站（逐行结果、仍需审批）</summary>
              <input
                type="file"
                accept=".jsonl,.ndjson"
                @change="
                  integrationUpload =
                    ($event.target as HTMLInputElement).files?.[0] || null
                "
              /><button @click="run(uploadIntegration)">
                上传入站文件批次
              </button>
            </details>
            <details>
              <summary>REST 入站调试（仍需审批）</summary>
              <textarea v-model="inboundJson" rows="7"></textarea
              ><button @click="run(inbound)">提交入站消息</button>
            </details>
          </section>
          <section class="panel">
            <h2>出站投递与恢复</h2>
            <table>
              <thead>
                <tr>
                  <th>目标系统 / 事件</th>
                  <th>业务版本 / 前序</th>
                  <th>同步状态</th>
                  <th>尝试</th>
                  <th>最近错误</th>
                  <th></th>
                </tr>
              </thead>
              <tbody>
                <tr v-for="d in Array.isArray(data) ? data : []" :key="d.id">
                  <td>
                    <strong>{{ d.systemName }}</strong
                    ><small class="mono">{{ d.eventId.slice(0, 8) }}…</small>
                  </td>
                  <td>
                    v{{ d.eventVersion }} /
                    {{ d.previousPublishedVersion || "首次" }}
                  </td>
                  <td>
                    <span class="badge" :class="d.state">{{
                      status(d.state)
                    }}</span>
                  </td>
                  <td>{{ d.attempts }}</td>
                  <td>{{ d.error || "—" }}</td>
                  <td>
                    <button @click="run(() => deliveryDetail(d))">
                      查看追踪</button
                    ><button
                      v-if="['FAILED', 'RESULT_UNKNOWN'].includes(d.state)"
                      @click="run(() => resend(d))"
                    >
                      核对后补发
                    </button>
                  </td>
                </tr>
              </tbody>
            </table>
            <div v-if="!data.length" class="empty">
              启用目标系统后，新正式业务事件将在此展示各目标的投递结果。
            </div>
          </section></template
        >
        <template v-else-if="page === 'logs'"
          ><section class="panel">
            <h2>追加式业务操作记录</h2>
            <div class="toolbar">
              <input
                placeholder="对象 UUID 或 ID"
                v-model="form.logObject"
              /><input
                placeholder="关联请求 traceId"
                v-model="form.logTrace"
              /><button
                @click="
                  run(
                    async () =>
                      (data = await api(
                        '/operation-logs?objectId=' +
                          encodeURIComponent(form.logObject || '') +
                          '&traceId=' +
                          encodeURIComponent(form.logTrace || ''),
                      )),
                  )
                "
              >
                定位业务
              </button>
            </div>
            <table>
              <thead>
                <tr>
                  <th>时间</th>
                  <th>对象 / 版本</th>
                  <th>动作 / 原因</th>
                  <th>操作者</th>
                  <th>关联请求</th>
                  <th></th>
                </tr>
              </thead>
              <tbody>
                <tr v-for="l in Array.isArray(data) ? data : []" :key="l.id">
                  <td>{{ new Date(l.createdAt).toLocaleString() }}</td>
                  <td>
                    {{ l.objectType }} v{{ l.version
                    }}<small class="mono">{{ l.objectId }}</small>
                  </td>
                  <td>
                    {{ status(l.action) }}<small>{{ l.reason }}</small>
                  </td>
                  <td>
                    {{ users.find((u) => u.id === l.actor)?.name || l.actor }}
                  </td>
                  <td class="mono">{{ l.traceId }}</td>
                  <td>
                    <button
                      @click="
                        detail = l;
                        modal = 'json';
                      "
                    >
                      查看差异
                    </button>
                  </td>
                </tr>
              </tbody>
            </table>
          </section></template
        >
        <template v-else-if="page === 'tenant'"
          ><section class="panel">
            <h2>{{ currentTenant?.name }} · 业务设置</h2>
            <div v-if="settings" class="form-grid">
              <label
                >时区<input
                  v-model="settings.settings.timezone"
                  :disabled="!can('ADMIN')" /></label
              ><label
                >默认页数<input
                  type="number"
                  v-model.number="settings.settings.pageSize"
                  :disabled="!can('ADMIN')" /></label
              ><label
                >导入上限<input
                  type="number"
                  v-model.number="settings.settings.importLimit"
                  :disabled="!can('ADMIN')" /></label
              ><label
                >导出上限<input
                  type="number"
                  v-model.number="settings.settings.exportLimit"
                  :disabled="!can('ADMIN')" /></label
              ><label
                >联系人<input
                  v-model="settings.settings.contact"
                  :disabled="!can('ADMIN')" /></label
              ><label
                >文件保留天数<input
                  type="number"
                  v-model.number="settings.settings.retentionDays"
                  :disabled="!can('ADMIN')"
              /></label>
            </div>
            <button
              v-if="can('ADMIN')"
              class="primary"
              @click="run(saveSettings)"
            >
              保存租户设置
            </button>
          </section>
          <section v-if="can('ADMIN')" class="panel">
            <div class="row">
              <h2>成员与类别范围</h2>
              <button @click="editMember()">＋ 关联已有用户</button>
            </div>
            <table>
              <thead>
                <tr>
                  <th>成员</th>
                  <th>角色</th>
                  <th>类别范围</th>
                  <th>状态</th>
                  <th></th>
                </tr>
              </thead>
              <tbody>
                <tr v-for="m in members" :key="m.id">
                  <td>
                    {{ m.userName }}<small>{{ m.userCode }}</small>
                  </td>
                  <td>{{ m.roles.join(" / ") }}</td>
                  <td>{{ m.categoryScope.join(" / ") }}</td>
                  <td>{{ m.active ? "任职中" : "已停用" }}</td>
                  <td><button @click="editMember(m)">调整成员</button></td>
                </tr>
              </tbody>
            </table>
            <h3>角色动作范围</h3>
            <div class="meta-list">
              <div v-for="r in roles" :key="r.code">
                <span
                  ><strong>{{ r.name }}</strong
                  ><small>{{ r.actions.join(" / ") }}</small></span
                ><button @click="run(() => editRole(r))">编辑</button>
              </div>
            </div>
            <button @click="run(() => editRole(null))">新增角色</button>
          </section>
          <div v-else class="banner">
            成员与角色管理需要租户管理员岗位；当前业务角色不具备管理权限。
          </div></template
        >
        <template v-else-if="page === 'platform'"
          ><section class="panel">
            <div class="row">
              <h2>平台租户目录</h2>
              <button class="primary" @click="run(newTenant)">
                ＋ 创建租户
              </button>
            </div>
            <p class="muted">
              平台岗位不自动授予租户业务角色。归档前必须处理未结束业务；重开先进入暂停状态。
            </p>
            <table>
              <thead>
                <tr>
                  <th>租户</th>
                  <th>状态</th>
                  <th>成员 / 物料</th>
                  <th>待处理导入 / 投递</th>
                  <th>操作</th>
                </tr>
              </thead>
              <tbody>
                <tr v-for="t in Array.isArray(data) ? data : []" :key="t.id">
                  <td>
                    <strong>{{ t.name }}</strong
                    ><small>{{ t.code }}</small>
                  </td>
                  <td>
                    <span class="badge" :class="t.status">{{
                      status(t.status)
                    }}</span>
                  </td>
                  <td>{{ t.overview.members }} / {{ t.overview.materials }}</td>
                  <td>
                    {{ t.overview.imports }} /
                    {{ t.overview.pendingDeliveries }}
                  </td>
                  <td>
                    <button
                      v-if="t.status === 'DRAFT'"
                      @click="run(() => platformAction(t, 'activate'))"
                    >
                      启用</button
                    ><button
                      v-if="t.status === 'ACTIVE'"
                      @click="run(() => platformAction(t, 'suspend'))"
                    >
                      暂停</button
                    ><button
                      v-if="t.status === 'SUSPENDED'"
                      @click="run(() => platformAction(t, 'resume'))"
                    >
                      恢复</button
                    ><button
                      v-if="['ACTIVE', 'SUSPENDED'].includes(t.status)"
                      @click="run(() => platformAction(t, 'archive'))"
                    >
                      归档</button
                    ><button
                      v-if="t.status === 'ARCHIVED'"
                      @click="run(() => platformAction(t, 'reopen'))"
                    >
                      确认重开
                    </button>
                  </td>
                </tr>
              </tbody>
            </table>
          </section></template
        >
      </div>
    </main>
  </div>
  <div v-if="modal" class="modal-backdrop" @click.self="dismiss">
    <section class="modal" :class="{ 'extra-wide': modal === 'schema' }">
      <div class="modal-heading">
        <h2>
          {{
            modal === "material"
              ? "物料录入"
              : modal === "schema"
                ? "配置草稿编辑"
                : modal === "detail"
                  ? "物料详情与历史"
                  : modal === "request"
                    ? "申请版本比较"
                    : modal === "member"
                      ? "成员岗位与范围"
                      : modal === "delivery"
                        ? "投递追踪"
                        : "业务结果"
          }}
        </h2>
        <button aria-label="关闭对话框" @click="dismiss">✕</button>
      </div>
      <div class="modal-body">
        <div v-if="session.error" class="banner error" role="alert">
          <pre>{{ session.error }}</pre>
        </div>
        <template v-if="modal === 'material'"
          ><div class="form-grid">
            <label
              >类别<select
                v-model="form.categoryCode"
                :disabled="!!form.id"
                aria-label="物料类别"
                @change="run(() => selectSchema(form.categoryCode))"
              >
                <option
                  v-for="c in categories.filter(
                    (c) => c.active && c.defaultSchemaId,
                  )"
                  :key="c.id"
                  :value="c.code"
                >
                  {{ c.name }}
                </option>
              </select></label
            ><label
              >物料名称 <b class="required">*</b
              ><input
                aria-label="物料名称"
                v-model="form.materialName" /></label
            ><label>基础业务单位<input v-model="form.baseUnitCode" /></label
            ><label
              >表单锁定版本<input
                :value="schema ? 'v' + schema.version + ' · ' + schema.id : ''"
                disabled
            /></label>
          </div>
          <div v-if="can('LEGACY')" class="form-grid">
            <label
              >编号来源<select v-model="form.numberSource">
                <option value="GENERATED">按规则生成</option>
                <option value="LEGACY">保留历史料号（需审批）</option>
              </select></label
            ><template v-if="form.numberSource === 'LEGACY'"
              ><label>历史料号<input v-model="form.legacyNo" /></label
              ><label>来源凭据<input v-model="form.sourceEvidence" /></label
              ><label
                >实际解析版本 UUID（可空）<input
                  v-model="form.parseRuleVersionId" /></label
            ></template>
          </div>
          <DynamicForm
            v-if="schema"
            :bundle="schema.bundle"
            :model="form.attributes"
            :references="references"
            :normalized="normalized"
            :decisions="decisions"
          />
          <div v-if="preview" class="preview">
            <small>候选料号 · 未占用</small
            ><strong data-testid="number-preview">{{
              preview.candidateMaterialNo
            }}</strong>
            <p v-for="w in preview.warnings" :key="w">{{ w }}</p>
          </div>
          <div class="toolbar">
            <button :disabled="!!session.busy" @click="run(previewNumber)">
              校验并预览料号
            </button>
            <div class="spacer"></div>
            <button
              :disabled="!!session.busy"
              @click="run(() => saveMaterial(false))"
            >
              保存草稿</button
            ><button
              class="primary"
              :disabled="!!session.busy"
              @click="run(() => saveMaterial(true))"
            >
              保存并提交审批
            </button>
          </div></template
        >
        <template v-else-if="modal === 'detail' && detail"
          ><div class="detail-title">
            <span class="badge" :class="detail.status">{{
              status(detail.status)
            }}</span>
            <h2>{{ detail.materialName }}</h2>
            <p class="mono">
              {{ detail.materialNo || "草稿，正式料号为空" }} · UUID
              {{ detail.id }}
            </p>
          </div>
          <dl>
            <dt>当前物料版本</dt>
            <dd>v{{ detail.rowVersion }}</dd>
            <dt>固定配置版本</dt>
            <dd>v{{ detail.schema.version }} · {{ detail.schemaVersionId }}</dd>
            <dt>编号来源</dt>
            <dd>
              {{ detail.numberSource
              }}<template v-if="detail.parseRuleVersionId">
                · 实际解析版本 {{ detail.parseRuleVersionId }}</template
              >
            </dd>
          </dl>
          <h3>当前权威属性</h3>
          <table>
            <tbody>
              <tr v-for="f in detail.schema.bundle.attributes" :key="f.code">
                <th>{{ f.label || f.code }}</th>
                <td>{{ pretty(detail.attributes[f.code]) ?? "未设置" }}</td>
              </tr>
            </tbody>
          </table>
          <div class="toolbar">
            <button
              v-if="detail.status === 'DRAFT' && can('EDIT')"
              @click="run(editMaterial)"
            >
              编辑草稿</button
            ><button v-if="can('CREATE')" @click="run(copyMaterial)">
              复制为新物料</button
            ><template v-if="detail.materialNo && can('EDIT')"
              ><button @click="run(() => newChange('CHANGE'))">
                申请非编码变更</button
              ><button
                v-if="detail.status === 'ACTIVE'"
                @click="run(() => newChange('DEACTIVATE'))"
              >
                申请停用</button
              ><button
                v-if="detail.status === 'INACTIVE'"
                @click="run(() => newChange('REACTIVATE'))"
              >
                申请重新启用
              </button></template
            >
          </div>
          <h3>替代物料关系</h3>
          <button
            v-if="detail.materialNo && can('EDIT')"
            @click="
              run(async () => {
                const replacementId = ask('输入已生效替代物料的UUID');
                const reason = replacementId ? ask('替代原因') : null;
                if (reason) {
                  await api(
                    '/materials/' + detail.id + '/replacements',
                    'POST',
                    { replacementId, reason },
                  );
                  await openMaterial(detail.id);
                }
              })
            "
          >
            建立替代关系
          </button>
          <p v-for="r in detail.replacements" :key="r.replacementId">
            <button @click="run(() => openMaterial(r.replacementId))">
              查看替代物料 {{ r.replacementId }}
            </button>
            · {{ r.reason }}
          </p>
          <h3>正式数据与外部同步</h3>
          <div v-for="d in detail.deliveries" :key="d.id" class="action-row">
            <strong>{{ d.systemName }}</strong
            ><span>业务事件 v{{ d.eventVersion }}</span
            ><span class="badge" :class="d.state">{{ status(d.state) }}</span>
          </div>
          <p v-if="!detail.deliveries.length" class="muted">
            暂无出站投递。同步状态独立于本地业务状态。
          </p>
          <h3>版本历史与当时配置</h3>
          <details v-for="h in history" :key="h.rowVersion">
            <summary>
              v{{ h.rowVersion }} ·
              {{ new Date(h.createdAt).toLocaleString() }} ·
              {{ h.reason || "业务记录" }}
            </summary>
            <pre>{{ pretty(h.snapshot) }}</pre>
            <button
              v-if="detail.materialNo && can('EDIT')"
              @click="
                run(async () => {
                  const reason = ask('历史恢复申请原因');
                  if (reason) {
                    await api(
                      '/materials/' + detail.id + '/restore',
                      'POST',
                      { version: h.rowVersion, reason },
                      detail.rowVersion,
                    );
                    session.notice = '已创建恢复申请，仍需提交审批';
                  }
                })
              "
            >
              以此非编码属性发起恢复申请
            </button>
          </details></template
        >
        <template v-else-if="modal === 'request'"
          ><p>
            基础物料版本 v{{ form.baseVersion }} · 当前版本 v{{
              detail.rowVersion
            }}
          </p>
          <div class="two-col">
            <div>
              <h3>当前正式 / 草稿快照</h3>
              <pre>{{
                pretty({
                  materialName: detail.materialName,
                  attributes: detail.attributes,
                  schemaVersionId: detail.schemaVersionId,
                })
              }}</pre>
            </div>
            <div>
              <h3>冻结候选快照</h3>
              <pre>{{ pretty(form.candidate) }}</pre>
            </div>
          </div>
          <p>原因：{{ form.reason || "首次建立" }}</p>
          <button
            v-if="
              form.state === 'DRAFT' && form.submittedBy === session.user.id
            "
            @click="
              run(async () => {
                const text = ask(
                  '编辑草稿申请候选 JSON',
                  pretty(form.candidate),
                );
                if (text) {
                  form = await api(
                    '/requests/' + form.id,
                    'PATCH',
                    { candidate: JSON.parse(text) },
                    form.rowVersion,
                  );
                }
              })
            "
          >
            修正草稿候选
          </button></template
        >
        <template v-else-if="modal === 'schema'"
          ><div class="toolbar">
            <button
              @click="
                if (!advanced) {
                  advancedJson = pretty(bundle);
                } else {
                  bundle = JSON.parse(advancedJson);
                }
                advanced = !advanced;
              "
            >
              {{
                advanced ? "返回表单配置" : "高级 JSON：条件、派生与回归样本"
              }}</button
            ><span class="muted">{{
              editingSchema
                ? "编辑草稿 v" + editingSchema.version
                : "保存为新的配置版本"
            }}</span>
          </div>
          <textarea
            v-if="advanced"
            v-model="advancedJson"
            rows="24"
            aria-label="发布包 JSON"
            @input="dirty = true"
          ></textarea
          ><template v-else
            ><h3>通用属性定义</h3>
            <div class="table-wrap">
              <table>
                <thead>
                  <tr>
                    <th>稳定 code</th>
                    <th>显示名称</th>
                    <th>类型</th>
                    <th>标准单位 / 字典 / 引用类型</th>
                    <th>分组</th>
                    <th>必填 / 查询 / 排序</th>
                    <th></th>
                  </tr>
                </thead>
                <tbody>
                  <tr v-for="(f, index) in bundle.attributes" :key="index">
                    <td><input v-model="f.code" @input="dirty = true" /></td>
                    <td><input v-model="f.label" @input="dirty = true" /></td>
                    <td>
                      <select v-model="f.type" @change="dirty = true">
                        <option
                          v-for="type in [
                            'STRING',
                            'INTEGER',
                            'DECIMAL',
                            'BOOLEAN',
                            'DATE',
                            'ENUM',
                            'REFERENCE',
                          ]"
                          :key="type"
                        >
                          {{ type }}
                        </option>
                      </select>
                    </td>
                    <td>
                      <input
                        v-if="['INTEGER', 'DECIMAL'].includes(f.type)"
                        v-model="f.unit"
                        placeholder="mm（可无单位）"
                      /><input
                        v-else-if="f.type === 'ENUM'"
                        v-model="f.dictionaryCode"
                        placeholder="字典 code"
                      /><input
                        v-else-if="f.type === 'REFERENCE'"
                        v-model="f.referenceType"
                        placeholder="引用 type"
                      /><input
                        v-else
                        v-model.number="f.maxLength"
                        placeholder="文本长度"
                      />
                    </td>
                    <td><input v-model="f.group" /></td>
                    <td class="checks">
                      <label
                        ><input
                          type="checkbox"
                          v-model="f.required"
                        />必填</label
                      ><label
                        ><input
                          type="checkbox"
                          v-model="f.searchable"
                        />查询</label
                      ><label
                        ><input
                          type="checkbox"
                          v-model="f.sortable"
                        />排序</label
                      >
                    </td>
                    <td>
                      <button
                        @click="
                          bundle.attributes.splice(index, 1);
                          dirty = true;
                        "
                      >
                        移除</button
                      ><button
                        v-if="index > 0"
                        @click="
                          bundle.attributes.splice(
                            index - 1,
                            0,
                            bundle.attributes.splice(index, 1)[0],
                          );
                          dirty = true;
                        "
                      >
                        ↑
                      </button>
                    </td>
                  </tr>
                </tbody>
              </table>
            </div>
            <button @click="addField">＋ 新增通用属性</button>
            <h3>料号编码段</h3>
            <div class="toolbar">
              <label>分隔符<input v-model="bundle.codeRule.separator" /></label
              ><button
                @click="
                  bundle.codeRule.segments.push({
                    type: 'ATTR',
                    field: bundle.attributes[0]?.code,
                  });
                  dirty = true;
                "
              >
                ＋ 添加编码段
              </button>
            </div>
            <div
              v-for="(seg, i) in bundle.codeRule.segments"
              :key="i"
              class="toolbar"
            >
              <select v-model="seg.type">
                <option
                  v-for="t in ['CONST', 'ATTR', 'LOOKUP', 'SEQUENCE', 'PERIOD']"
                  :key="t"
                >
                  {{ t }}
                </option></select
              ><input
                v-if="seg.type === 'CONST'"
                v-model="seg.value"
                placeholder="固定前缀"
              /><input
                v-if="['ATTR', 'LOOKUP'].includes(seg.type)"
                v-model="seg.field"
                placeholder="属性路径，如 manufacturer.id"
              /><input
                v-if="seg.type === 'SEQUENCE'"
                type="number"
                v-model.number="seg.width"
                placeholder="流水位数"
              /><input
                v-if="seg.type === 'ATTR'"
                type="number"
                v-model.number="seg.scale"
                placeholder="输出小数位（可空）"
              /><span v-if="seg.type === 'LOOKUP'" class="muted"
                >映射表通过高级 JSON 编辑</span
              ><button
                @click="
                  bundle.codeRule.segments.splice(i, 1);
                  dirty = true;
                "
              >
                移除
              </button>
            </div></template
          >
          <div class="toolbar">
            <button
              class="primary"
              :disabled="!!session.busy"
              @click="run(saveBundle)"
            >
              保存配置草稿</button
            ><button
              v-if="editingSchema"
              @click="
                run(() => schemaAction(editingSchema, 'submit')).then(() => {
                  modal = '';
                  dirty = false;
                })
              "
            >
              提交审核
            </button>
          </div></template
        >
        <template v-else-if="modal === 'identities'"
          ><h3>{{ detail.system.name }} · 外部身份</h3>
          <button @click="run(() => bindIdentity())">登记外部身份</button>
          <table>
            <thead>
              <tr>
                <th>外部ID</th>
                <th>物料UUID</th>
                <th>确认版本 / 绑定版本</th>
                <th></th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="i in detail.rows" :key="i.externalId">
                <td>{{ i.externalId }}</td>
                <td>{{ i.materialId }}</td>
                <td>
                  {{ i.confirmedVersion || "尚未同步确认" }} / v{{
                    i.rowVersion
                  }}
                </td>
                <td>
                  <button @click="run(() => bindIdentity(i))">
                    核实并变更绑定
                  </button>
                </td>
              </tr>
            </tbody>
          </table></template
        ><template v-else-if="modal === 'member'"
          ><label
            >关联业务用户<select
              v-model="memberForm.userId"
              :disabled="!!memberForm.id"
            >
              <option v-for="u in tenantUsers" :key="u.id" :value="u.id">
                {{ u.name }}
              </option>
            </select></label
          >
          <div class="form-grid">
            <label
              >业务角色（可多选）<select multiple v-model="memberForm.roles">
                <option v-for="r in roles" :key="r.code" :value="r.code">
                  {{ r.name }}
                </option>
              </select></label
            ><label
              >类别范围（可多选）<select
                multiple
                v-model="memberForm.categoryScope"
              >
                <option value="*">全部类别</option>
                <option v-for="c in categories" :key="c.id" :value="c.code">
                  {{ c.name }}
                </option>
              </select></label
            >
          </div>
          <label class="checkbox"
            ><input
              type="checkbox"
              v-model="memberForm.active"
            />成员任职中</label
          ><button class="primary" @click="run(saveMember)">
            保存成员与范围
          </button></template
        >
        <template v-else-if="modal === 'delivery'"
          ><p class="badge" :class="detail.state">{{ status(detail.state) }}</p>
          <button
            v-if="detail.file"
            @click="download(detail.file.id, detail.file.name)"
          >
            下载出站文件批次</button
          ><button
            v-if="
              ['WAIT_CONFIRMATION', 'RESULT_UNKNOWN'].includes(detail.state)
            "
            @click="run(() => confirmDelivery(detail))"
          >
            人工核实业务成功
          </button>
          <pre>{{ pretty(detail) }}</pre>
        </template>
        <pre v-else>{{ pretty(detail) }}</pre>
      </div>
    </section>
  </div>
</template>
