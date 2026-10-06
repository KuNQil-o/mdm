import { test, expect } from '@playwright/test';
const erp = 'http://127.0.0.1:9092';
async function weight(request: any) {
  const id = 'UI' + crypto.randomUUID().replaceAll('-', '').slice(0, 10);
  const res = await request.post(erp + '/records/cloth_weight', {data:{id,type_id:'TYPE01',cloth_code:id,weight:210,unit:'g/m2'}});
  expect(res.ok()).toBeTruthy(); return id;
}
test('ERP 页面保存并申请，自动回填、刷新持久化、重复申请原号', async ({page, request}) => {
  const id = await weight(request);
  await page.goto('/erp/');
  await expect(page.getByRole('heading', {name:'玻璃布管理'})).toBeVisible();
  await page.getByLabel('布种 / 基重', {exact:true}).selectOption(id);
  const key = await page.getByLabel('ERP 来源键').inputValue();
  await page.getByLabel('备注').fill('ERP 浏览器流程');
  await page.getByRole('button',{name:'保存并申请料号',exact:true}).click();
  await expect(page.getByRole('status')).toContainText('已自动写入 ERP');
  const no = await page.getByLabel('玻璃布料号',{exact:true}).inputValue();
  expect(no).toMatch(/^GC\d{6}$/);
  await expect(page.getByLabel('厂商',{exact:true})).toBeDisabled();
  await page.getByRole('button',{name:'申请 / 重试料号',exact:true}).click();
  await expect(page.getByRole('status')).toContainText(no);
  await page.reload();
  const row = page.getByRole('row').filter({hasText:key});
  await expect(row).toContainText(no);
  await row.getByRole('button',{name:'打开',exact:true}).click();
  await expect(page.getByLabel('玻璃布料号',{exact:true})).toHaveValue(no);
  await page.screenshot({path:'../.runtime/erp-page.png',fullPage:true});
});
test('ERP 页面身份重复显示业务错误，保留同一来源键用于修正恢复',async({page,request}) => {
  const id=await weight(request);
  await page.goto('/erp/');
  await page.getByLabel('布种 / 基重',{exact:true}).selectOption(id);
  await page.getByRole('button',{name:'保存并申请料号',exact:true}).click();
  await expect(page.getByRole('status')).toContainText('已自动写入 ERP');
  await page.getByRole('button',{name:'新建另一条',exact:true}).click();
  const key=await page.getByLabel('ERP 来源键').inputValue();
  await page.getByRole('button',{name:'保存并申请料号',exact:true}).click();
  await expect(page.getByRole('status')).toContainText('IDENTITY_CONFLICT');
  await expect(page.getByLabel('ERP 来源键')).toHaveValue(key);
  await expect(page.getByLabel('玻璃布料号',{exact:true})).toHaveValue('');
  await page.getByLabel('厂商',{exact:true}).selectOption('M02');
  await page.getByRole('button',{name:'保存并申请料号',exact:true}).click();
  await expect(page.getByRole('status')).toContainText('已自动写入 ERP');
  await expect(page.getByLabel('ERP 来源键')).toHaveValue(key);
});
test('ERP 页面仅保存后主动申请，浏览器不会接触来源凭据',async({page,request}) => {
  const id=await weight(request);
  await page.goto('/erp/');
  await page.getByLabel('布种 / 基重',{exact:true}).selectOption(id);
  await page.getByRole('button',{name:'仅保存',exact:true}).click();
  await expect(page.getByRole('status')).toContainText('ERP 记录已保存');
  await expect(page.getByLabel('玻璃布料号',{exact:true})).toHaveValue('');
  await page.getByRole('button',{name:'申请 / 重试料号',exact:true}).click();
  await expect(page.getByRole('status')).toContainText('已自动写入 ERP');
  const res=await request.get('/erp/api/config');
  expect(await res.text()).not.toContain('erp-glass-demo-dev-only');
});
