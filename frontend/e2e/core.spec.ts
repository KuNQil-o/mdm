import { test, expect } from "@playwright/test";
const tenant = "TENANT-B";
const suffix = Date.now().toString();
const model = "B" + suffix;
const name = "浏览器物料" + suffix;
async function login(page: any, user: string) {
  await page.getByLabel("演示成员", { exact: true }).selectOption(user);
  await expect(page.locator(".loading-line")).toHaveCount(0);
  await page.getByLabel("当前租户", { exact: true }).selectOption(tenant);
  await expect(page.locator(".page-heading")).toContainText("精工制造");
  await expect(page.locator(".loading-line")).toHaveCount(0);
}
test("真实浏览器：租户切换、配置发布、动态录入、审批、历史、导出、外部同步", async ({
  page,
}) => {
  const errors: string[] = [];
  page.on("pageerror", (e) => errors.push(e.message));
  await page.goto("/");
  await page.getByLabel("开发演示用户").selectOption("editor");
  await page.getByRole("button", { name: "进入工作空间 →" }).click();
  await expect(page.locator(".page-heading")).toContainText("华东新材");
  await page.getByLabel("当前租户", { exact: true }).selectOption(tenant);
  await expect(page.locator(".page-heading")).toContainText("精工制造");
  await page.getByRole("button", { name: "◇ 模型配置", exact: false }).click();
  await page.getByLabel("模型类别").selectOption("GLASS_CLOTH");
  await expect(
    page.getByRole("button", { name: "复制为新版本" }).first(),
  ).toBeVisible();
  await page.getByRole("button", { name: "复制为新版本" }).first().click();
  await page
    .getByRole("button", { name: "高级 JSON：条件、派生与回归样本" })
    .click();
  const box = page.getByLabel("发布包 JSON");
  const bundle = JSON.parse(await box.inputValue());
  bundle.attributes.push({
    code: "memo" + suffix,
    label: "验收备注",
    type: "STRING",
    group: "补充信息",
  });
  await box.fill(JSON.stringify(bundle));
  await page.getByRole("button", { name: "保存配置草稿", exact: true }).click();
  await expect(page.getByRole("status")).toContainText("配置草稿已保存");
  await page.getByRole("button", { name: "关闭对话框" }).click();
  const draft = page
    .locator("tbody tr")
    .filter({ has: page.locator(".badge.DRAFT") })
    .first();
  await draft.getByRole("button", { name: "提交审核" }).click();
  await expect(page.locator(".badge.REVIEW").first()).toBeVisible();
  await login(page, "reviewer");
  await page.getByLabel("模型类别").selectOption("GLASS_CLOTH");
  const review = page
    .locator("tbody tr")
    .filter({ has: page.locator(".badge.REVIEW") })
    .first();
  await review.getByRole("button", { name: "批准发布" }).click();
  await expect(page.getByRole("status")).toContainText("已发布");
  await page.getByRole("button", { name: "集成与对账", exact: false }).click();
  const system = page
    .locator(".system-grid article")
    .filter({ hasText: "ERP 模拟 · 物料接口" });
  await expect(system).toBeVisible();
  if (await system.getByRole("button", { name: "启用", exact: true }).count()) {
    const configPrompt = async (dialog: any) => {
      const cfg = JSON.parse(dialog.defaultValue());
      cfg.contractTestUrl = "http://localhost:9090/targets/erp-a/validate";
      await dialog.accept(JSON.stringify(cfg));
    };
    page.once("dialog", configPrompt);
    await system.getByRole("button", { name: "连接配置", exact: true }).click();
    await expect(page.locator(".loading-line")).toHaveCount(0);
    await system.getByRole("button", { name: "测试连接与契约" }).click();
    await expect(page.locator(".modal pre")).toContainText(
      "businessErrorSampleVerified",
    );
    await page.getByRole("button", { name: "关闭对话框" }).click();
    await system.getByRole("button", { name: "启用", exact: true }).click();
    await expect(system.locator(".badge")).toHaveText("已启用");
  }
  await login(page, "editor");
  await page.getByRole("button", { name: "物料目录", exact: false }).click();
  await page.getByRole("button", { name: "＋ 新建物料" }).click();
  await page
    .getByLabel("物料类别", { exact: true })
    .selectOption("GLASS_CLOTH");
  await page.getByLabel("物料名称", { exact: true }).fill(name);
  await page.getByLabel("型号", { exact: true }).fill(model);
  await page.getByLabel("幅宽", { exact: true }).fill("1270");
  await page.getByLabel("克重", { exact: true }).fill("210");
  await page.getByLabel("厂商", { exact: true }).selectOption("SUP-001");
  await page
    .getByLabel("已认证", { exact: true })
    .selectOption({ label: "否" });
  await page.getByRole("button", { name: "校验并预览料号" }).click();
  await expect(page.getByTestId("number-preview")).toHaveText(
    "GC-" + model + "-1270-HONGHE",
  );
  await expect(page.locator(".loading-line")).toHaveCount(0);
  await page.getByRole("button", { name: "保存并提交审批" }).click();
  await expect(page.getByRole("status")).toContainText("已提交");
  await login(page, "reviewer");
  await page.getByRole("button", { name: "审批中心", exact: false }).click();
  const req = page.locator("tbody tr").filter({ hasText: name });
  await req.getByRole("button", { name: "比较差异" }).click();
  await expect(page.locator(".modal")).toContainText("冻结候选快照");
  await page.getByRole("button", { name: "关闭对话框" }).click();
  await req.getByRole("button", { name: "批准", exact: true }).click();
  await expect(req.locator(".badge")).toHaveText("已批准");
  await page.getByRole("button", { name: "物料目录", exact: false }).click();
  await page.getByLabel("名称查询").fill(name);
  await page.getByRole("button", { name: "查询", exact: true }).click();
  await expect(page.locator("tbody")).toContainText(
    "GC-" + model + "-1270-HONGHE",
  );
  await page.getByRole("button", { name: "查看详情 →" }).click();
  await expect(page.locator(".modal")).toContainText("版本历史与当时配置");
  await expect(page.locator(".modal summary")).toHaveCount(3);
  await expect
    .poll(
      async () => {
        await page.getByRole("button", { name: "关闭对话框" }).click();
        await page.getByRole("button", { name: "查看详情 →" }).click();
        await expect(page.locator(".modal")).toBeVisible();
        return await page.locator(".modal .badge.SUCCEEDED").count();
      },
      { timeout: 20000 },
    )
    .toBeGreaterThan(0);
  await page.getByRole("button", { name: "关闭对话框" }).click();
  const filePromise = page.waitForEvent("download");
  await page.getByRole("button", { name: "导出 CSV" }).click();
  const file = await filePromise;
  await file.saveAs("../.runtime/browser-export.csv");
  await page.screenshot({
    path: "../.runtime/browser-materials.png",
    fullPage: true,
  });
  expect(errors).toEqual([]);
});
