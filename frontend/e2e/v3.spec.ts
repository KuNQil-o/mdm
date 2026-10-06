import { test, expect } from "@playwright/test";
import type { Page, APIRequestContext } from "@playwright/test";
import fs from "node:fs";
const fixtures = JSON.parse(
  fs.readFileSync("../.runtime/v3-acceptance-fixtures.json", "utf8"),
);
async function login(page: Page, user = "editor") {
  await page.goto("/");
  await page.getByLabel("当前用户").selectOption(user);
  await page.getByRole("button", { name: "登录 / 切换用户" }).click();
  await expect(page.getByText("从来源到发号", { exact: true })).toBeVisible();
}
async function api(
  request: APIRequestContext,
  path: string,
  method = "GET",
  data?: any,
  version?: number,
) {
  const response = await request.fetch("http://localhost:8080/api/v1" + path, {
    method,
    data,
    headers: {
      "X-Tenant-Code": "TENANT-A",
      "Idempotency-Key": crypto.randomUUID(),
      ...(version ? { "If-Match": String(version) } : {}),
    },
  });
  expect(response.ok(), await response.text()).toBeTruthy();
  return response.json();
}
async function dataset(page: Page, kind: string) {
  await page.getByRole("button", { name: "Dataset 构建", exact: true }).click();
  await page
    .getByRole("combobox")
    .filter({
      has: page.locator(
        'option[value="' + fixtures.fixtures[kind].datasetId + '"]',
      ),
    })
    .selectOption(fixtures.fixtures[kind].datasetId);
  await page.getByLabel("预览来源键").fill(fixtures.keys[kind]);
}
test("E2E-01 glass: seven joins → real ERP preview → issued ledger → typed search → explanation", async ({
  page,
}) => {
  await login(page);
  await dataset(page, "GC");
  await expect(page.locator(".v3-join")).toHaveCount(7);
  await page
    .getByRole("button", { name: "读取真实数据预览", exact: true })
    .click();
  await expect(page.locator(".v3-result pre")).toContainText("HH");
  await expect(page.locator(".v3-result pre")).toContainText("7628");
  await page
    .getByRole("button", { name: "按默认发布规则预览料号", exact: true })
    .click();
  await expect(page.locator(".v3-result pre")).toContainText(
    fixtures.issued.GC.materialNo,
  );
  await page
    .getByRole("button", { name: "从 ERP 记录申请正式号", exact: true })
    .click();
  await expect(page.locator(".v3-result pre")).toContainText(
    fixtures.issued.GC.assignmentId,
  );
  await page.getByRole("button", { name: "发号台账", exact: true }).click();
  await page
    .getByLabel("台账搜索类别")
    .selectOption(fixtures.fixtures.GC.categoryCode);
  await page.getByLabel("台账搜索字段").selectOption("manufacturer");
  await page.getByLabel("台账搜索值").fill("HH");
  await page.getByRole("button", { name: "类型化搜索" }).click();
  const row = page.getByRole("row").filter({ hasText: fixtures.keys.GC });
  await expect(row).toHaveCount(1);
  await row.getByRole("button", { name: "解释料号" }).click();
  await expect(page.locator(".v3-result pre")).toContainText(
    "identityCanonical",
  );
  await expect(page.locator(".v3-result pre")).toContainText("schemaVersionId");
  await expect(page.locator(".v3-result pre")).toContainText(
    "writebackAttempts",
  );
});
test("E2E-02 copper: draft new code rule → independent Golden → second user publish → scan", async ({
  page,
}) => {
  await login(page);
  const fi = fixtures.fixtures.CF;
  const old = await api(page.request, "/releases/" + fi.releaseId);
  const rule = await api(page.request, "/code-rules/" + fi.codeRuleId);
  const sourceRule = rule.definition;
  sourceRule.segments[0].value = fi.categoryCode + "_BROWSER";
  delete sourceRule.parseRule;
  await page
    .getByRole("button", { name: "Schema 与规则", exact: true })
    .click();
  await page
    .getByLabel("配置类型", { exact: true })
    .selectOption("/code-rules");
  await page
    .getByLabel("所属类别", { exact: true })
    .selectOption(fi.categoryCode);
  await page.getByLabel("配置 code", { exact: true }).fill(fi.categoryCode);
  await page
    .getByLabel("配置定义", { exact: true })
    .fill(JSON.stringify(sourceRule));
  await page.getByRole("button", { name: "保存为新版本草稿" }).click();
  await expect(page.locator('[role="alert"]')).toHaveCount(0);
  const versions = await api(page.request, "/code-rules");
  const latest = versions
    .filter((r: any) => r.code === fi.categoryCode)
    .sort((a: any, b: any) => b.versionNo - a.versionNo)[0];
  await page.getByRole("button", { name: "组合发布", exact: true }).click();
  await page.getByLabel("类别", { exact: true }).selectOption(fi.categoryCode);
  await page.getByLabel("Dataset", { exact: true }).selectOption(fi.datasetId);
  await page.getByLabel("Schema", { exact: true }).selectOption(fi.schemaId);
  await page.getByLabel("Mapping", { exact: true }).selectOption(old.mappingId);
  await page
    .getByLabel("Identity", { exact: true })
    .selectOption(old.identityId);
  await page.getByLabel("Code Rule", { exact: true }).selectOption(latest.id);
  const definition = structuredClone(old.definition);
  definition.samples[0].expectedMaterialNo =
    definition.samples[0].expectedMaterialNo.replace(
      fi.categoryCode,
      fi.categoryCode + "_BROWSER",
    );
  definition.writeback.contractPath = "/contract";
  definition.writeback.businessValidationPath = "/contract/validate";
  await page
    .getByLabel("Golden Samples 与回写契约", { exact: true })
    .fill(JSON.stringify(definition));
  await page.getByRole("button", { name: "新建发布草稿" }).click();
  let row = page
    .getByRole("row")
    .filter({ hasText: fi.categoryCode })
    .filter({ hasText: "草稿" })
    .last();
  await row.getByRole("button", { name: "样本验证" }).click();
  await expect(page.locator(".v3-result pre")).toContainText('"passed": true');
  await row.getByRole("button", { name: "提交", exact: true }).click();
  await expect(page.locator('[role="alert"]')).toHaveCount(0);
  await page.getByLabel("当前用户").selectOption("reviewer");
  await page.getByRole("button", { name: "登录 / 切换用户" }).click();
  await page.getByRole("button", { name: "组合发布", exact: true }).click();
  row = page
    .getByRole("row")
    .filter({ hasText: fi.categoryCode })
    .filter({ hasText: "待审批" })
    .last();
  await row.getByRole("button", { name: "审批发布" }).click();
  await expect(page.locator('[role="alert"]')).toHaveCount(0);
  await page.getByLabel("当前用户").selectOption("editor");
  await page.getByRole("button", { name: "登录 / 切换用户" }).click();
  await dataset(page, "CF");
  await page.getByRole("button", { name: "扫描当前发布版本" }).click();
  await expect(
    page.getByRole("heading", { name: "扫描任务", exact: true }),
  ).toBeVisible();
  await expect(page.locator("tbody tr")).not.toHaveCount(0);
});
for (const [kind, e2e] of [
  ["FILM", "E2E-03"],
  ["BOARD", "E2E-04"],
])
  test(
    e2e +
      " " +
      kind +
      ": exact-one size/cut, invalid ERP facts rejected, ledger persists after reload",
    async ({ page, request }) => {
      await login(page);
      await dataset(page, kind);
      await page
        .getByRole("button", { name: "按默认发布规则预览料号" })
        .click();
      await expect(page.locator(".v3-result pre")).toContainText("STD1");
      const source = await (
        await request.get(
          "http://127.0.0.1:9092/documents/" + fixtures.keys[kind],
        )
      ).json();
      for (const both of [true, false]) {
        const attrs = { ...source };
        for (const k of [
          "id",
          "category",
          "materialNo",
          "sourceVersion",
          "sourceUpdatedAt",
        ])
          delete attrs[k];
        if (both) {
          attrs.size = "STD1";
          attrs.cut = "CUT1";
        } else {
          delete attrs.size;
          delete attrs.cut;
        }
        const key = "BROWSER_" + crypto.randomUUID();
        expect(
          (
            await request.post("http://127.0.0.1:9092/documents", {
              data: {
                id: key,
                category: fixtures.fixtures[kind].categoryCode,
                data: attrs,
              },
            })
          ).ok(),
        ).toBeTruthy();
        await page.getByLabel("预览来源键").fill(key);
        await page
          .getByRole("button", { name: "按默认发布规则预览料号" })
          .click();
        await expect(page.getByRole("alert")).toContainText("VALIDATION_ERROR");
        await expect(page.getByRole("alert")).toContainText("尺寸与裁切");
      }
      await page.reload();
      await login(page);
      const a = await api(
        page.request,
        "/assignment-ledger/" + fixtures.issued[kind].assignmentId,
      );
      expect(a.materialNo).toBe(fixtures.issued[kind].materialNo);
      expect(a.identityCanonical).toContain("size");
    },
  );
