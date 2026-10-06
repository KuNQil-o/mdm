import { test, expect } from "@playwright/test";
import { Buffer } from "node:buffer";
const tag = "IMPORT" + Date.now();
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
  const res = await request.fetch("/api/v1" + path, { method, headers, data });
  expect(res.ok(), await res.text()).toBeTruthy();
  return res.json();
}
async function apiLogin(request: any, user: string) {
  const r = await request.post("/api/dev/login", { data: { userCode: user } });
  expect(r.ok()).toBeTruthy();
}
async function asset(request: any, index: number) {
  await apiLogin(request, "editor");
  const schema = await call(request, "/categories/GLASS_CLOTH/schema");
  const m = await call(request, "/materials", "POST", {
    schemaVersionId: schema.id,
    materialName: tag + index,
    attributes: {
      model: tag + index,
      width: { value: 1270, unit: "mm" },
      basisWeight: { value: 210, unit: "g/m2" },
      manufacturer: { type: "SUPPLIER", id: "SUP-001" },
    },
  });
  let r = await call(
    request,
    "/materials/" + m.id + "/change-requests",
    "POST",
    { kind: "NEW" },
    m.rowVersion,
  );
  r = await call(
    request,
    "/requests/" + r.id + "/submit",
    "POST",
    {},
    r.rowVersion,
  );
  await apiLogin(request, "reviewer");
  await call(
    request,
    "/requests/" + r.id + "/approve",
    "POST",
    {},
    r.rowVersion,
  );
  await apiLogin(request, "editor");
  return call(request, "/materials/" + m.id);
}
test("浏览器导入：通过、错误、重复、版本冲突，逐行修正与继续，不重放成功行", async ({
  page,
}) => {
  await page.goto("/");
  await page.getByLabel("开发演示用户").selectOption("editor");
  await page.getByRole("button", { name: "进入工作空间 →" }).click();
  await page.getByLabel("当前租户", { exact: true }).selectOption("TENANT-B");
  await expect(page.locator(".page-heading")).toContainText("精工制造");
  const fixtures = [];
  for (let i = 0; i < 4; i++) fixtures.push(await asset(page.request, i));
  await page.getByRole("button", { name: "导入中心", exact: false }).click();
  await page
    .getByLabel("导入类别", { exact: true })
    .selectOption("GLASS_CLOTH");
  const header = "id,baseVersion,materialName,description,width\n";
  const csv =
    header +
    `${fixtures[0].id},3,导入通过,通过行,1270\n${fixtures[1].id},3,导入错误,错误行,-1\n${fixtures[0].id},3,重复行,重复,1270\n${fixtures[2].id},1,版本冲突行,冲突,1270\n`;
  await page.getByLabel("导入文件").setInputFiles({
    name: "业务验收.csv",
    mimeType: "text/csv",
    buffer: Buffer.from(csv),
  });
  await page.getByRole("button", { name: "上传文件", exact: true }).click();
  await expect(page.getByText("业务验收.csv", { exact: true })).toBeVisible();
  await page
    .getByRole("combobox")
    .filter({ has: page.locator('option[value="UPDATE"]') })
    .selectOption("UPDATE");
  await page.getByRole("button", { name: "建立任务并全量预检" }).click();
  const result = page
    .locator("section.panel")
    .filter({ has: page.getByRole("heading", { name: /任务结果/ }) });
  await expect(result.locator("h2 .badge")).toHaveText("预检完成");
  await expect(result).toContainText("DUPLICATE_ROW");
  await expect(result).toContainText("VERSION_CONFLICT");
  await expect(result).toContainText("OUT_OF_RANGE");
  await page.getByRole("button", { name: "确认提交通过行" }).click();
  await expect(result.locator("h2 .badge")).toHaveText("部分失败");
  const successRow = result
    .locator("tbody tr")
    .filter({ has: page.locator("td").filter({ hasText: /^1$/ }) });
  await expect(successRow).toContainText("草稿/申请已创建");
  const jobRows = await call(page.request, "/import-jobs");
  const j = jobRows[0];
  const firstState = await call(page.request, "/import-jobs/" + j.id);
  expect(firstState.rows[0].state).toBe("SUCCEEDED");
  const firstRequest = firstState.rows[0].requestId;
  for (const rowNo of [2, 3, 4]) {
    const row = result.locator("tbody tr").filter({
      has: page
        .locator("td")
        .filter({ hasText: new RegExp("^" + rowNo + "$") }),
    });
    page.once("dialog", async (dialog) => {
      const source = JSON.parse(dialog.defaultValue());
      source.width = "1270";
      source.baseVersion = "3";
      if (rowNo === 3) source.id = fixtures[3].id;
      await dialog.accept(JSON.stringify(source));
    });
    await row.getByRole("button", { name: "修正此失败行" }).click();
    await expect(result.locator("h2 .badge")).toHaveText("已上传");
  }
  await page.getByRole("button", { name: "重新预检失败行" }).click();
  await expect(result.locator("h2 .badge")).toHaveText("预检完成");
  await page.getByRole("button", { name: "确认提交通过行" }).click();
  await expect(result.locator("h2 .badge")).toHaveText("已完成");
  const after = await call(page.request, "/import-jobs/" + j.id);
  expect(after.rows.every((r: any) => r.state === "SUCCEEDED")).toBeTruthy();
  expect(after.rows[0].requestId).toBe(firstRequest);
  expect(new Set(after.rows.map((r: any) => r.requestId)).size).toBe(4);
  const file = page.waitForEvent("download");
  await page.getByRole("button", { name: "下载错误报告" }).click();
  await (await file).saveAs("../.runtime/import-errors.csv");
  await page.screenshot({
    path: "../.runtime/browser-import.png",
    fullPage: true,
  });
});
