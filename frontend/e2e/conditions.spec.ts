import { test, expect } from "@playwright/test";
const tag = "FORM" + Date.now();
async function call(
  request: any,
  path: string,
  method = "GET",
  data?: any,
  version?: any,
) {
  const headers: any = {
    "X-Tenant-Code": "TENANT-B",
    "Idempotency-Key": crypto.randomUUID(),
  };
  if (version !== undefined) headers["If-Match"] = '"' + version + '"';
  const r = await request.fetch("/api/v1" + path, { method, headers, data });
  expect(r.ok(), await r.text()).toBeTruthy();
  return r.json();
}
test("动态条件使用后端结论；隐藏字段保留，派生值只读", async ({ page }) => {
  await page.goto("/");
  await page.request.post("/api/dev/login", { data: { userCode: "editor" } });
  await call(page.request, "/categories", "POST", {
    code: tag,
    name: "条件表单验收",
  });
  const bundle = {
    attributes: [
      { code: "flag", label: "显示补充", type: "BOOLEAN", default: false },
      {
        code: "note",
        label: "补充信息",
        type: "STRING",
        visibleWhen: { op: "eq", args: [{ field: "flag" }, true] },
      },
      { code: "amount", label: "数值", type: "DECIMAL", default: { value: 0 } },
      {
        code: "derived",
        label: "派生结果",
        type: "DECIMAL",
        derived: { op: "add", args: [{ field: "amount" }, 5] },
      },
    ],
    uiSchema: { order: ["flag", "note", "amount", "derived"] },
    codeRule: { segments: [{ type: "CONST", value: tag }] },
    samples: [{ attributes: { flag: false, amount: { value: 0 } } }],
  };
  let s = await call(page.request, "/categories/" + tag + "/schemas", "POST", {
    bundle,
  });
  s = await call(
    page.request,
    "/schemas/" + s.id + "/submit",
    "POST",
    {},
    s.rowVersion,
  );
  await page.request.post("/api/dev/login", { data: { userCode: "reviewer" } });
  await call(
    page.request,
    "/schemas/" + s.id + "/approve",
    "POST",
    {},
    s.rowVersion,
  );
  await page.getByLabel("开发演示用户").selectOption("editor");
  await page.getByRole("button", { name: "进入工作空间 →" }).click();
  await page.getByLabel("当前租户", { exact: true }).selectOption("TENANT-B");
  await expect(page.locator(".loading-line")).toHaveCount(0);
  await page.getByRole("button", { name: "物料目录", exact: false }).click();
  await page.getByRole("button", { name: "＋ 新建物料" }).click();
  await page.getByLabel("物料类别", { exact: true }).selectOption(tag);
  await expect(page.getByLabel("补充信息", { exact: true })).toHaveCount(0);
  await page
    .getByLabel("显示补充", { exact: true })
    .selectOption({ label: "是" });
  await expect(page.getByLabel("补充信息", { exact: true })).toBeVisible();
  await page.getByLabel("补充信息", { exact: true }).fill("隐藏后保留");
  await page
    .getByLabel("显示补充", { exact: true })
    .selectOption({ label: "否" });
  await expect(page.getByLabel("补充信息", { exact: true })).toHaveCount(0);
  await page.getByLabel("数值", { exact: true }).fill("1.000000000001");
  await expect(
    page
      .locator("input[disabled]")
      .filter({ hasNot: page.locator('[type="hidden"]') })
      .last(),
  ).toHaveValue("6.000000000001");
  await page.getByLabel("物料名称", { exact: true }).fill(tag);
  await page.getByRole("button", { name: "保存草稿", exact: true }).click();
  await expect(page.getByRole("status")).toContainText("草稿已持久化");
  const result = await call(page.request, "/materials/search", "POST", {
    categoryCode: tag,
    name: tag,
  });
  expect(result.items[0].attributes.note).toBe("隐藏后保留");
  expect(result.items[0].attributes.flag).toBe(false);
  expect(result.items[0].attributes.derived.value).toBe(6.000000000001);
});
