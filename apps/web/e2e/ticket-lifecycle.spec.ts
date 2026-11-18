import { expect, test } from "@playwright/test";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { adminPassword, adminUser, login } from "./helpers";

const fixture = path.join(path.dirname(fileURLToPath(import.meta.url)), "fixtures/onsite.png");

test("工单可完成派单→接单→上传现场照片→解决", async ({ page }) => {
  await login(page, adminUser, adminPassword);
  const description = `E2E 全流程 ${Date.now()}：A栋2号梯门锁异响`;

  // 1. 建单
  await page.getByRole("button", { name: "新建工单" }).click();
  const createDialog = page.getByRole("dialog");
  await expect(createDialog).toContainText("报修 / 报事");
  await page.getByPlaceholder(/请描述故障现象/).fill(description);
  const buildingItem = createDialog.locator(".el-form-item").filter({ hasText: "楼宇" });
  await buildingItem.locator(".el-select").click();
  await page.locator(".el-select-dropdown__item").filter({ hasText: "A 栋" }).first().click();
  const roomItem = createDialog.locator(".el-form-item").filter({ hasText: "房间" });
  await roomItem.locator(".el-select").click();
  await page.locator(".el-select-dropdown__item").filter({ hasText: "1单元 101" }).first().click();
  await page.getByPlaceholder("如 A栋2号梯 / 东门地下车库").fill("A栋2号梯");
  await page.getByRole("button", { name: "提交" }).click();
  await expect(page.getByText(/工单已创建/)).toBeVisible({ timeout: 15_000 });

  // 2. 定位本次工单
  await page.getByPlaceholder("工单号 / 标题").fill(description);
  await page.getByRole("button", { name: "查询" }).click();
  const row = page.locator(".el-table__row").filter({ hasText: description }).first();
  await expect(row).toBeVisible({ timeout: 10_000 });

  // 3. 派单给系统管理员
  await row.getByRole("button", { name: "派单" }).click();
  const assignDialog = page.getByRole("dialog").filter({ hasText: "派单" });
  await assignDialog.locator(".el-select").click();
  await page.locator(".el-select-dropdown__item").filter({ hasText: "系统管理员" }).first().click();
  await assignDialog.getByRole("button", { name: "确定派单" }).click();
  await expect(page.getByText("操作成功").first()).toBeVisible({ timeout: 10_000 });

  // 4. 接单
  await expect(row.getByText("待接单")).toBeVisible({ timeout: 10_000 });
  await row.getByRole("button", { name: "接单" }).click();
  await expect(page.getByText("操作成功").first()).toBeVisible({ timeout: 10_000 });

  // 5. 上传现场照片并解决
  await expect(row.getByText("处理中")).toBeVisible({ timeout: 10_000 });
  await row.getByRole("button", { name: "解决" }).click();
  const resolveDialog = page.getByRole("dialog").filter({ hasText: "提交解决" });
  await resolveDialog.getByPlaceholder("文字填写本次服务项目").fill("门锁检修与润滑");
  await resolveDialog.locator("input[type=file]").setInputFiles(fixture);
  await expect(resolveDialog).toContainText("已选 1 张");
  await resolveDialog.getByRole("button", { name: "提交解决" }).click();
  await expect(page.getByText("已提交解决").first()).toBeVisible({ timeout: 15_000 });

  // 6. 状态进入待验证
  await expect(row.getByText("待验证")).toBeVisible({ timeout: 10_000 });
});
